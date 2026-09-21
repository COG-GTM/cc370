package insurance.app;

import insurance.ledger.Json;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

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
