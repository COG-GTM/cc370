package insurance.app;

import insurance.contract.v001.ContractV001;
import insurance.ledger.ContractBinding;
import insurance.ledger.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Retained runtime evidence of the process-kill and HTTP boundary tests. Written only when {@code
 * -Dledger.evidence.dir=DIR} is set (the acceptance evidence run); otherwise a no-op so unit runs
 * leave nothing behind.
 */
public final class RuntimeEvidence {
  private final Path dir;
  private final String name;
  private final Map<String, Object> doc = new LinkedHashMap<>();

  public RuntimeEvidence(String schema, String name) {
    String d = System.getProperty("ledger.evidence.dir");
    this.dir = d == null || d.isBlank() ? null : Path.of(d);
    this.name = name;
    doc.put("schema", schema);
    doc.put("recorded_at", Instant.now().toString());
    doc.put("java_runtime", System.getProperty("java.runtime.version"));
    doc.put("postgres_image", PostgresSupport.PG.getDockerImageName());
    doc.put("execution", "maven-test-classpath child JVM (not the packaged JAR)");
    doc.put("source_commit", git("rev-parse", "HEAD"));
    doc.put(
        "source_tree_dirty",
        !git("status", "--porcelain", "--", "..", ":(exclude)../evidence").isEmpty());
    doc.put("contract_identity", ContractBinding.of(ContractV001.frozen()).identity());
  }

  private static String git(String... args) {
    String[] cmd = new String[args.length + 1];
    cmd[0] = "git";
    System.arraycopy(args, 0, cmd, 1, args.length);
    try {
      Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
      String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
      if (!p.waitFor(30, TimeUnit.SECONDS) || p.exitValue() != 0) {
        return "unknown";
      }
      return out;
    } catch (IOException e) {
      return "unknown";
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return "unknown";
    }
  }

  public RuntimeEvidence put(String key, Object value) {
    doc.put(key, value);
    return this;
  }

  public Map<String, Object> section(String key) {
    Map<String, Object> m = new LinkedHashMap<>();
    doc.put(key, m);
    return m;
  }

  /** Writes {@code DIR/name.test.json}; one file per test method. */
  public void write(String test) {
    if (dir == null) {
      return;
    }
    doc.put("test", test);
    try {
      Files.createDirectories(dir);
      Files.write(
          dir.resolve(name + "." + test + ".json"),
          Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(doc));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
