package insurance.app.http;

/** A namespace, generation or policy that is not visible through the published API. */
public final class NotFoundException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public NotFoundException(String what) {
    super(what + " not found");
  }
}
