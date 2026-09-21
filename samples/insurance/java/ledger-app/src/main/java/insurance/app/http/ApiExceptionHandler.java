package insurance.app.http;

import insurance.app.http.Api.ErrorResponse;
import insurance.contract.v001.PolicyTable;
import insurance.ledger.ExpectedManifest;
import insurance.ledger.generation.GenerationStore;
import insurance.ledger.typed.TypedEnvelopeException;
import insurance.legacy.codec.Records;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Envelope/lifecycle failures are HTTP errors. Legacy domain outcomes (DATE, OVER, NPOL, ...) are
 * never mapped here: they are HTTP 200 with the status carried in the result record.
 *
 * <ul>
 *   <li>400: the request could not be turned into legal legacy bytes or a legal manifest.
 *   <li>404: the namespace/generation/policy is not visible.
 *   <li>409: lifecycle rejection (fence, CAS, wrong status, name in use).
 *   <li>422: publication validation against the pinned manifest failed (generation discarded).
 * </ul>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

  @ExceptionHandler({
    TypedEnvelopeException.class,
    HttpMessageNotReadableException.class,
    Records.PartialRecordException.class,
    PolicyTable.BadMasterException.class
  })
  public ResponseEntity<ErrorResponse> badRequest(RuntimeException e) {
    return error(HttpStatus.BAD_REQUEST, "envelope", e);
  }

  @ExceptionHandler(NotFoundException.class)
  public ResponseEntity<ErrorResponse> notFound(NotFoundException e) {
    return error(HttpStatus.NOT_FOUND, "not-found", e);
  }

  @ExceptionHandler(GenerationStore.FencedException.class)
  public ResponseEntity<ErrorResponse> fenced(GenerationStore.FencedException e) {
    return error(HttpStatus.CONFLICT, "fenced", e);
  }

  @ExceptionHandler(GenerationStore.CheckpointException.class)
  public ResponseEntity<ErrorResponse> checkpoint(GenerationStore.CheckpointException e) {
    return error(HttpStatus.CONFLICT, "checkpoint", e);
  }

  @ExceptionHandler(GenerationStore.CasException.class)
  public ResponseEntity<ErrorResponse> cas(GenerationStore.CasException e) {
    return error(HttpStatus.CONFLICT, "cas", e);
  }

  @ExceptionHandler(GenerationStore.GenerationException.class)
  public ResponseEntity<ErrorResponse> lifecycle(GenerationStore.GenerationException e) {
    return error(HttpStatus.CONFLICT, "lifecycle", e);
  }

  @ExceptionHandler(ExpectedManifest.ManifestException.class)
  public ResponseEntity<ErrorResponse> manifest(ExpectedManifest.ManifestException e) {
    return error(HttpStatus.UNPROCESSABLE_ENTITY, "manifest", e);
  }

  private static ResponseEntity<ErrorResponse> error(
      HttpStatus status, String kind, RuntimeException e) {
    String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    return ResponseEntity.status(status).body(new ErrorResponse(kind, message));
  }
}
