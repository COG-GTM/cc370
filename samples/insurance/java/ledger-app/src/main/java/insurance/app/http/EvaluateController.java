package insurance.app.http;

import insurance.app.http.Api.EvaluateRequest;
import insurance.app.http.Api.EvaluateResponse;
import insurance.contract.v001.Evaluation;
import insurance.ledger.PolicyLedgerService;
import insurance.ledger.typed.TypedResult;
import insurance.legacy.codec.Hex;
import insurance.legacy.codec.PolicyRecord;
import insurance.legacy.codec.TransactionRecord;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stateless evaluation of one (master, request) pair. Diagnostic only: it persists nothing and does
 * not satisfy the stateful parity requirement.
 */
@RestController
public class EvaluateController {
  private final PolicyLedgerService service;

  public EvaluateController(PolicyLedgerService service) {
    this.service = service;
  }

  @PostMapping(path = "/v1/raw/evaluate", produces = MediaType.APPLICATION_JSON_VALUE)
  public EvaluateResponse evaluate(@RequestBody EvaluateRequest body) {
    byte[] master = GenerationController.hex(body.masterHex(), "masterHex", PolicyRecord.LENGTH);
    byte[] txn = GenerationController.hex(body.recordHex(), "recordHex", TransactionRecord.LENGTH);
    Evaluation e = service.evaluateStateless(master, txn);
    return new EvaluateResponse(
        e.status().name(),
        e.accepted(),
        Hex.of(e.result().bytes()),
        Hex.of(e.master().bytes()),
        TypedResult.of(e.result()));
  }
}
