package insurance.contract.v001;

/**
 * Raised when an arithmetic result would exceed the declared packed-decimal width of a work field.
 * Under the frozen rate table such states are unreachable for validated inputs; the Java
 * implementation fails closed instead of emulating a program interruption or reporting a legacy
 * status that the assembler would not have produced.
 */
public final class ContractDomainException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public ContractDomainException(String message) {
    super(message);
  }
}
