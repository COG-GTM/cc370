package insurance.app.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Defines the HTTP admission order of request-applying calls per generation.
 *
 * <p>The first thing this instance does with a {@code POST .../requests}, {@code .../requests:raw},
 * {@code .../batch} or {@code .../claim} call is take an admission ticket for its generation;
 * tickets are dense, start at 1 and are handed out in the order the servlet container delivers
 * requests to this filter. The call then runs only when every lower ticket of the same generation
 * has finished, so the ordinal a request is committed under follows admission order exactly, and
 * every request (applied or rejected) consumes its ticket. The ticket is echoed in the {@link
 * #HEADER} response header.
 *
 * <p>This defines "ingress order" at the servlet chain of one instance. It says nothing about the
 * order in which TCP connections were accepted or bytes arrived on the wire, which the application
 * cannot observe; nor is it shared across instances.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AdmissionSequencer extends OncePerRequestFilter {
  public static final String HEADER = "X-Admission-Sequence";

  private static final Pattern APPLY =
      Pattern.compile(
          "^/v1/namespaces/([^/]+)/generations/([^/]+)/(requests(?::raw)?|batch|claim)$");

  private final Map<String, Turnstile> turnstiles = new ConcurrentHashMap<>();

  private static final class Turnstile {
    private final AtomicLong next = new AtomicLong(1);
    private long serving = 1;

    long take() {
      return next.getAndIncrement();
    }

    /** Uninterruptible: a ticket must always be served in turn, never skipped. */
    synchronized void await(long ticket) {
      boolean interrupted = false;
      while (serving != ticket) {
        try {
          wait();
        } catch (InterruptedException e) {
          interrupted = true;
        }
      }
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }

    synchronized void release() {
      serving++;
      notifyAll();
    }
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !"POST".equals(request.getMethod()) || !APPLY.matcher(request.getRequestURI()).matches();
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Matcher m = APPLY.matcher(request.getRequestURI());
    if (!m.matches()) {
      chain.doFilter(request, response);
      return;
    }
    Turnstile t = turnstiles.computeIfAbsent(m.group(1) + "/" + m.group(2), k -> new Turnstile());
    long ticket = t.take();
    response.setHeader(HEADER, Long.toString(ticket));
    t.await(ticket);
    try {
      chain.doFilter(request, response);
    } finally {
      t.release();
    }
  }
}
