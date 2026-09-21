package insurance.app;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The stateful HTTP service running in a separate JVM against the test PostgreSQL container. The
 * child is the real {@code LedgerApplication} (Spring Boot, Flyway, JDBC store) started from the
 * test class path; {@code ledger.kill-switch} arms a {@link insurance.app.persistence.KillSwitch}.
 */
public final class ChildService implements AutoCloseable {
  /** Exit status of {@code Runtime.halt} in the kill switch. */
  public static final int HALTED = insurance.app.persistence.KillSwitch.EXIT;

  private final Process process;
  private final int port;
  private final StringBuilder output = new StringBuilder();
  private final Thread pump;

  private ChildService(Process process, int port) {
    this.process = process;
    this.port = port;
    this.pump =
        new Thread(
            () -> {
              try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = process.getInputStream().read(buf)) > 0) {
                  synchronized (output) {
                    output.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                  }
                }
              } catch (IOException ignored) {
                // process ended
              }
            },
            "child-service-output");
    pump.setDaemon(true);
    pump.start();
  }

  public static int freePort() throws IOException {
    try (ServerSocket s = new ServerSocket(0)) {
      return s.getLocalPort();
    }
  }

  public static ChildService start(Duration lease, String killSwitch, String... extraArgs)
      throws IOException, InterruptedException {
    int port = freePort();
    List<String> cmd = new ArrayList<>();
    cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    cmd.add("-Xmx256m");
    cmd.add("-cp");
    String surefire = System.getProperty("surefire.test.class.path");
    cmd.add(surefire != null ? surefire : System.getProperty("java.class.path"));
    cmd.add(LedgerApplication.class.getName());
    cmd.add("--server.port=" + port);
    cmd.add("--spring.datasource.url=" + PostgresSupport.PG.getJdbcUrl());
    cmd.add("--spring.datasource.username=" + PostgresSupport.PG.getUsername());
    cmd.add("--spring.datasource.password=" + PostgresSupport.PG.getPassword());
    cmd.add("--ledger.writer-lease=" + lease);
    cmd.add("--ledger.kill-switch=" + (killSwitch == null ? "" : killSwitch));
    cmd.add("--logging.level.root=WARN");
    cmd.addAll(List.of(extraArgs));
    Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
    ChildService child = new ChildService(p, port);
    child.awaitReady();
    return child;
  }

  private void awaitReady() throws InterruptedException {
    HttpClient probe = HttpClient.newHttpClient();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
    while (System.nanoTime() < deadline) {
      if (!process.isAlive()) {
        throw new IllegalStateException("child exited " + process.exitValue() + ":\n" + output());
      }
      try {
        HttpResponse<String> r =
            probe.send(
                HttpRequest.newBuilder(URI.create(url("/v1/namespaces/probe/current")))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 404 || r.statusCode() == 200) {
          return;
        }
      } catch (IOException e) {
        Thread.sleep(200);
      }
    }
    throw new IllegalStateException("child did not become ready:\n" + output());
  }

  public String url(String path) {
    return "http://127.0.0.1:" + port + path;
  }

  public int port() {
    return port;
  }

  public boolean isAlive() {
    return process.isAlive();
  }

  public long pid() {
    return process.pid();
  }

  /** Waits for the process to end and returns its exit status. */
  public int waitFor() throws InterruptedException {
    if (!process.waitFor(60, TimeUnit.SECONDS)) {
      throw new IllegalStateException("child still running:\n" + output());
    }
    pump.join(5_000);
    return process.exitValue();
  }

  public String output() {
    synchronized (output) {
      return output.toString();
    }
  }

  @Override
  public void close() {
    process.destroyForcibly();
  }
}
