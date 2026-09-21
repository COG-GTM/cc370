package insurance.app.persistence;

import insurance.ledger.generation.GenerationInfo;
import insurance.ledger.generation.GenerationStatus;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/** Metadata columns of {@code generation}; the byte columns are read through {@code JdbcClient}. */
@Table("generation")
public record GenerationRow(
    @Id Long id,
    String namespace,
    String name,
    Long parentId,
    String status,
    long fence,
    int policiesCount,
    long lastOrdinal,
    int typedRequests,
    int rawRequests,
    String seedPolinSha256,
    String manifestSha256) {

  public GenerationStatus generationStatus() {
    return GenerationStatus.valueOf(status);
  }

  public GenerationInfo toInfo(String parentName) {
    return new GenerationInfo(
        namespace,
        name,
        parentName,
        generationStatus(),
        fence,
        policiesCount,
        lastOrdinal,
        typedRequests,
        rawRequests,
        seedPolinSha256,
        manifestSha256);
  }
}
