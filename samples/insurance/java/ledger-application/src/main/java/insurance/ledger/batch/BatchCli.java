package insurance.ledger.batch;

import insurance.contract.v001.ContractV001;
import insurance.contract.v001.RateTable;
import insurance.ledger.BuildIdentity;
import insurance.ledger.ExpectedManifest;
import insurance.ledger.Json;
import insurance.ledger.PolicyLedgerService;
import insurance.ledger.Receipt;
import insurance.ledger.Sha256;
import insurance.ledger.generation.FileGenerationStore;
import insurance.ledger.generation.GenerationInfo;
import insurance.ledger.generation.GenerationStore.Lease;
import insurance.ledger.generation.ReceiptContext;
import insurance.legacy.codec.Records;
import insurance.legacy.codec.TransactionRecord;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Batch/file adapter.
 *
 * <pre>
 * batch bootstrap --store DIR --namespace NS --generation GEN --polin F --manifest F
 * batch run       --store DIR --namespace NS --parent GEN --generation GEN
 *                 --polin F --txnin F --manifest F --out DIR [--source-commit SHA]
 * batch inbat     --polin F --txnin F --manifest F --out DIR      (pure INSBAT emulation)
 * batch verify    --store DIR                                     (fail-closed open)
 * </pre>
 *
 * <p>Exit codes: 0 success, 2 manifest/input rejection, 3 lifecycle/publication failure, 4 usage.
 */
public final class BatchCli {
  private BatchCli() {}

  public static void main(String[] args) {
    System.exit(run(args, System.out, System.err));
  }

  public static int run(String[] args, PrintStream out, PrintStream err) {
    if (args.length == 0) {
      err.println("usage: batch <bootstrap|run|inbat|verify> [--key value]...");
      return 4;
    }
    Map<String, String> opts = parse(args, 1);
    try {
      switch (args[0]) {
        case "bootstrap":
          return bootstrap(opts, out);
        case "run":
          return runGeneration(opts, out);
        case "inbat":
          return inbat(opts, out);
        case "verify":
          return verify(opts, out);
        default:
          err.println("unknown command " + args[0]);
          return 4;
      }
    } catch (ExpectedManifest.ManifestException e) {
      err.println(Json.MAPPER.valueToTree(Map.of("rejected", e.problems())).toPrettyString());
      return 2;
    } catch (insurance.ledger.generation.GenerationStore.GenerationException
        | insurance.contract.v001.PolicyTable.BadMasterException
        | Records.PartialRecordException e) {
      err.println(
          Json.MAPPER
              .valueToTree(Map.of("failed", e.getClass().getSimpleName(), "reason", e.getMessage()))
              .toPrettyString());
      return 3;
    } catch (IOException e) {
      err.println("io: " + e.getMessage());
      return 3;
    }
  }

  private static PolicyLedgerService service(Path storeDir, String sourceCommit) {
    ContractV001 contract = new ContractV001(RateTable.FROZEN);
    FileGenerationStore store = FileGenerationStore.open(storeDir);
    ReceiptContext ctx =
        new ReceiptContext(
            "batch",
            sourceCommit,
            BuildIdentity.ofClass(BatchCli.class),
            BuildIdentity.rateTableSha256(RateTable.FROZEN));
    return new PolicyLedgerService(contract, store, ctx);
  }

  private static int bootstrap(Map<String, String> o, PrintStream out) throws IOException {
    PolicyLedgerService svc =
        service(Path.of(req(o, "store")), o.getOrDefault("source-commit", "unknown"));
    byte[] polin = Files.readAllBytes(Path.of(req(o, "polin")));
    ExpectedManifest manifest =
        ExpectedManifest.parse(Files.readAllBytes(Path.of(req(o, "manifest"))));
    GenerationInfo info = svc.bootstrap(req(o, "namespace"), req(o, "generation"), polin, manifest);
    out.write(Json.bytes(info));
    return 0;
  }

  private static int runGeneration(Map<String, String> o, PrintStream out) throws IOException {
    PolicyLedgerService svc =
        service(Path.of(req(o, "store")), o.getOrDefault("source-commit", "unknown"));
    byte[] polin = Files.readAllBytes(Path.of(req(o, "polin")));
    byte[] txnin = Files.readAllBytes(Path.of(req(o, "txnin")));
    ExpectedManifest manifest =
        ExpectedManifest.parse(Files.readAllBytes(Path.of(req(o, "manifest"))));
    manifest.verifyInputs(polin, txnin);
    String ns = req(o, "namespace");
    Lease lease = svc.begin(ns, req(o, "parent"), req(o, "generation"), Optional.of(polin));
    Receipt receipt;
    try {
      for (TransactionRecord t : Records.transactions(txnin)) {
        svc.apply(lease, t, false);
      }
      receipt = svc.publish(lease, manifest, "batch");
    } catch (RuntimeException e) {
      if (svc.store()
          .info(ns, lease.generation())
          .map(i -> i.status().name().equals("PENDING"))
          .orElse(false)) {
        svc.discard(lease);
      }
      throw e;
    }
    Path outDir = Path.of(req(o, "out"));
    Files.createDirectories(outDir);
    byte[] polout = svc.store().polout(ns, lease.generation());
    byte[] resout = svc.store().resout(ns, lease.generation());
    Files.write(outDir.resolve("polout.bin"), polout);
    Files.write(outDir.resolve("resout.bin"), resout);
    Files.write(outDir.resolve("receipt.json"), Json.bytes(receipt));
    Files.write(
        outDir.resolve("generation.json"),
        Json.bytes(svc.store().info(ns, lease.generation()).orElseThrow()));
    out.write(Json.bytes(receipt));
    return 0;
  }

  private static int inbat(Map<String, String> o, PrintStream out) throws IOException {
    byte[] polin = Files.readAllBytes(Path.of(req(o, "polin")));
    byte[] txnin = Files.readAllBytes(Path.of(req(o, "txnin")));
    ExpectedManifest manifest =
        ExpectedManifest.parse(Files.readAllBytes(Path.of(req(o, "manifest"))));
    manifest.verifyInputs(polin, txnin);
    BatchRunner.Output result =
        new BatchRunner(new ContractV001(RateTable.FROZEN)).run(polin, txnin);
    Path outDir = Path.of(req(o, "out"));
    Files.createDirectories(outDir);
    Files.write(outDir.resolve("polout.bin"), result.polout());
    Files.write(outDir.resolve("resout.bin"), result.resout());
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("schema", "insurance-java-inbat-v1");
    summary.put("return_code", result.returnCode());
    summary.put("bad_master_reason", result.badMasterReason());
    summary.put("polin_sha256", Sha256.of(polin));
    summary.put("txnin_sha256", Sha256.of(txnin));
    summary.put("polout_sha256", Sha256.of(result.polout()));
    summary.put("resout_sha256", Sha256.of(result.resout()));
    summary.put("results_count", result.evaluations().size());
    summary.put("build_identity", BuildIdentity.ofClass(BatchCli.class));
    summary.put("runtime_identity", BuildIdentity.runtime());
    summary.put("rate_table_sha256", BuildIdentity.rateTableSha256(RateTable.FROZEN));
    byte[] json = Json.bytes(summary);
    Files.write(outDir.resolve("run.json"), json);
    out.write(json);
    return result.returnCode() == 0 ? 0 : 3;
  }

  private static int verify(Map<String, String> o, PrintStream out) throws IOException {
    FileGenerationStore store = FileGenerationStore.open(Path.of(req(o, "store")));
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("discarded_on_open", store.discardedOnOpen());
    Map<String, String> current = new HashMap<>();
    for (String ns : store.namespaces()) {
      current.put(ns, store.current(ns).orElse(null));
    }
    report.put("current", current);
    out.write(Json.bytes(report));
    return 0;
  }

  private static String req(Map<String, String> o, String key) {
    String v = o.get(key);
    if (v == null) {
      throw new IllegalArgumentException("missing --" + key);
    }
    return v;
  }

  private static Map<String, String> parse(String[] args, int from) {
    Map<String, String> m = new HashMap<>();
    for (int i = from; i < args.length; i++) {
      if (!args[i].startsWith("--") || i + 1 >= args.length) {
        throw new IllegalArgumentException("bad argument " + args[i]);
      }
      m.put(args[i].substring(2), args[++i]);
    }
    return m;
  }
}
