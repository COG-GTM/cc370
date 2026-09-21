package insurance.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A TCP proxy between a real HTTP client and the service that injects network faults between the
 * two, without touching either end:
 *
 * <ul>
 *   <li>{@link Mode#PASS}: transparent;
 *   <li>{@link Mode#DROP_REQUEST}: swallows the client's bytes (the service never receives the
 *       request, so nothing is committed) and answers nothing: the client times out;
 *   <li>{@link Mode#DROP_RESPONSE}: forwards the request, swallows the service's response (the
 *       commit is durable, the response is lost): the client times out;
 *   <li>{@link Mode#REPLY_503_UNSENT}: answers {@code 503} immediately without forwarding (a
 *       gateway failure before the service saw the request);
 *   <li>{@link Mode#REPLY_503_AFTER}: forwards the request, waits for the service's response,
 *       discards it and answers {@code 503} (a gateway failure after the commit).
 * </ul>
 */
public final class FaultProxy implements AutoCloseable {
  public enum Mode {
    PASS,
    DROP_REQUEST,
    DROP_RESPONSE,
    REPLY_503_UNSENT,
    REPLY_503_AFTER
  }

  private static final byte[] REPLY_503 =
      ("HTTP/1.1 503 Service Unavailable\r\n"
              + "Content-Type: text/plain\r\n"
              + "Content-Length: 13\r\n"
              + "Connection: close\r\n\r\n"
              + "proxy fault\r\n")
          .getBytes(StandardCharsets.US_ASCII);

  private final ServerSocket listener;
  private final String upstreamHost;
  private final int upstreamPort;
  private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.PASS);
  private final AtomicInteger upstreamResponsesSeen = new AtomicInteger();
  private final Thread acceptor;

  public FaultProxy(String upstreamHost, int upstreamPort) throws IOException {
    this.listener = new ServerSocket(0);
    this.upstreamHost = upstreamHost;
    this.upstreamPort = upstreamPort;
    this.acceptor = new Thread(this::acceptLoop, "fault-proxy-acceptor");
    acceptor.setDaemon(true);
    acceptor.start();
  }

  public int port() {
    return listener.getLocalPort();
  }

  public void mode(Mode m) {
    mode.set(m);
  }

  /** Number of upstream responses the proxy received (forwarded, dropped or replaced). */
  public int upstreamResponsesSeen() {
    return upstreamResponsesSeen.get();
  }

  private void acceptLoop() {
    while (!listener.isClosed()) {
      try {
        Socket client = listener.accept();
        Thread t = new Thread(() -> serve(client), "fault-proxy-conn");
        t.setDaemon(true);
        t.start();
      } catch (IOException e) {
        return;
      }
    }
  }

  private void serve(Socket client) {
    Mode m = mode.get();
    try (client) {
      client.setSoTimeout(30_000);
      if (m == Mode.REPLY_503_UNSENT) {
        // consume the request head so the client has definitely sent it, then fail it
        readSome(client.getInputStream());
        client.getOutputStream().write(REPLY_503);
        client.getOutputStream().flush();
        return;
      }
      if (m == Mode.DROP_REQUEST) {
        // read and discard until the client gives up (timeout) and closes
        drain(client.getInputStream());
        return;
      }
      try (Socket upstream = new Socket(upstreamHost, upstreamPort)) {
        upstream.setSoTimeout(30_000);
        Thread up = new Thread(() -> pipe(client, upstream), "fault-proxy-up");
        up.setDaemon(true);
        up.start();
        InputStream from = upstream.getInputStream();
        OutputStream to = client.getOutputStream();
        byte[] buf = new byte[16 * 1024];
        boolean first = true;
        int n;
        while ((n = from.read(buf)) > 0) {
          if (first) {
            upstreamResponsesSeen.incrementAndGet();
            first = false;
            if (m == Mode.REPLY_503_AFTER) {
              stopReading(upstream);
              to.write(REPLY_503);
              to.flush();
              return;
            }
          }
          if (m == Mode.PASS) {
            to.write(buf, 0, n);
            to.flush();
          }
          // DROP_RESPONSE: swallow
        }
      }
    } catch (IOException ignored) {
      // connection torn down by one side
    }
  }

  private static void pipe(Socket client, Socket upstream) {
    try {
      InputStream from = client.getInputStream();
      OutputStream to = upstream.getOutputStream();
      byte[] buf = new byte[16 * 1024];
      int n;
      while ((n = from.read(buf)) > 0) {
        to.write(buf, 0, n);
        to.flush();
      }
      upstream.shutdownOutput();
    } catch (IOException ignored) {
      // client gone
    }
  }

  private static void readSome(InputStream in) throws IOException {
    byte[] buf = new byte[16 * 1024];
    int n = in.read(buf);
    if (n <= 0) {
      throw new IOException("client sent nothing");
    }
  }

  private static void drain(InputStream in) {
    try {
      byte[] buf = new byte[16 * 1024];
      while (in.read(buf) > 0) {
        // discard
      }
    } catch (IOException ignored) {
      // client closed
    }
  }

  private static void stopReading(Socket upstream) {
    // the service has answered, i.e. committed; the rest of its response is irrelevant
    try {
      upstream.shutdownInput();
    } catch (IOException ignored) {
      // fine
    }
  }

  @Override
  public void close() throws IOException {
    listener.close();
  }
}
