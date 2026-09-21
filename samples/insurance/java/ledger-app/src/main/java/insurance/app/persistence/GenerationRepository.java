package insurance.app.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;

/** Read side of the generation table (Spring Data JDBC). All writes go through the store. */
public interface GenerationRepository extends Repository<GenerationRow, Long> {

  Optional<GenerationRow> findById(Long id);

  Optional<GenerationRow> findByNamespaceAndName(String namespace, String name);

  List<GenerationRow> findByStatus(String status);

  @Query("SELECT DISTINCT namespace FROM generation ORDER BY namespace")
  List<String> namespaces();
}
