package insurance.ledger;

import insurance.contract.v001.RateTable;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.stream.Collectors;

/** Identity strings recorded in receipts. */
public final class BuildIdentity {
  private BuildIdentity() {}

  /**
   * SHA-256 of the launched JAR ({@code java -jar}: the single class-path entry, which also covers
   * Spring Boot nested-jar code sources), else of the JAR the class was loaded from, else the
   * classes directory path.
   */
  public static String ofClass(Class<?> type) {
    String classPath = System.getProperty("java.class.path", "");
    if (!classPath.contains(File.pathSeparator) && classPath.endsWith(".jar")) {
      Path launched = Path.of(classPath);
      if (Files.isRegularFile(launched)) {
        try {
          return "jar:sha256:" + Sha256.of(Files.readAllBytes(launched));
        } catch (IOException e) {
          return "unknown";
        }
      }
    }
    CodeSource source = type.getProtectionDomain().getCodeSource();
    if (source == null || source.getLocation() == null) {
      return "unknown";
    }
    try {
      Path path = Path.of(source.getLocation().toURI());
      if (Files.isRegularFile(path)) {
        return "jar:sha256:" + Sha256.of(Files.readAllBytes(path));
      }
      return "classes:" + path;
    } catch (URISyntaxException | IOException | IllegalArgumentException e) {
      return "unknown";
    }
  }

  public static String runtime() {
    return System.getProperty("java.vendor")
        + " "
        + System.getProperty("java.runtime.version")
        + " ("
        + System.getProperty("java.vm.name")
        + ")";
  }

  /** Canonical textual form of the rate table so the receipt binds the exact rows used. */
  public static String rateTableSha256(RateTable table) {
    String canonical =
        table.rows().stream()
            .map(r -> r.effective() + "," + r.rateBps() + "," + r.feeBps())
            .collect(Collectors.joining("\n", "", "\n"));
    return Sha256.of(canonical.getBytes(StandardCharsets.US_ASCII));
  }
}
