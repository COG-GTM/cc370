package insurance.ledger.typed;

/**
 * The typed envelope cannot be turned into a legal 40-byte request. This is an HTTP-level (400)
 * failure and never a legacy domain status: it means the request never reached the contract.
 */
public final class TypedEnvelopeException extends IllegalArgumentException {
  private static final long serialVersionUID = 1L;

  public TypedEnvelopeException(String message) {
    super(message);
  }
}
