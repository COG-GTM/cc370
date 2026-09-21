package insurance.app.persistence;

import insurance.app.persistence.JdbcGenerationStore.Boundary;
import insurance.app.persistence.JdbcGenerationStore.FaultPoint;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Test hook that fires once, the first time the configured lifecycle boundary (and ordinal) is
 * reached, either halting the JVM ({@code Runtime.halt(137)}: no shutdown hooks, no transaction
 * commit, no response) or throwing a {@link FaultException} (a real server 5xx; inside a
 * transaction the work rolls back, after one it stays committed while the caller gets the error).
 *
 * <p>Spec {@code [halt:|fail:]BOUNDARY[:ORDINAL]}, e.g. {@code after-commit:3} halts right after
 * the third request has been durably committed and before its response is written; {@code
 * fail:before-commit:2} throws inside the second commit's transaction; {@code before-publish-flip}
 * halts inside the publication transaction. Empty spec = no fault point.
 */
public final class KillSwitch implements FaultPoint {
  public static final int EXIT = 137;

  /** Thrown by a {@code fail:} fault point; not handled by the API, so it surfaces as HTTP 500. */
  public static final class FaultException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    FaultException(String message) {
      super(message);
    }
  }

  private final Boundary boundary;
  private final long ordinal;
  private final boolean halt;
  private final AtomicBoolean fired = new AtomicBoolean();

  private KillSwitch(Boundary boundary, long ordinal, boolean halt) {
    this.boundary = boundary;
    this.ordinal = ordinal;
    this.halt = halt;
  }

  public static FaultPoint parse(String spec) {
    if (spec == null || spec.isBlank()) {
      return FaultPoint.NONE;
    }
    String s = spec.trim();
    boolean halt = true;
    if (s.startsWith("halt:")) {
      s = s.substring(5);
    } else if (s.startsWith("fail:")) {
      halt = false;
      s = s.substring(5);
    }
    String[] parts = s.split(":", 2);
    Boundary boundary = Boundary.valueOf(parts[0].toUpperCase(Locale.ROOT).replace('-', '_'));
    long ordinal = parts.length == 2 ? Long.parseLong(parts[1]) : -1;
    return new KillSwitch(boundary, ordinal, halt);
  }

  @Override
  public void reached(Boundary at, long ord) {
    if (at != boundary || (ordinal >= 0 && ord != ordinal) || !fired.compareAndSet(false, true)) {
      return;
    }
    String line = (halt ? "kill switch " : "fault ") + at + " at ordinal " + ord;
    System.err.println(line);
    System.err.flush();
    if (halt) {
      Runtime.getRuntime().halt(EXIT);
    }
    throw new FaultException(line);
  }
}
