package insurance.app;

import insurance.app.persistence.GenerationRepository;
import insurance.app.persistence.JdbcGenerationStore;
import insurance.contract.v001.ContractV001;
import insurance.ledger.BuildIdentity;
import insurance.ledger.PolicyLedgerService;
import insurance.ledger.generation.ReceiptContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class LedgerConfig {

  @Bean
  public ContractV001 contract() {
    return ContractV001.frozen();
  }

  @Bean
  public ReceiptContext receiptContext(
      @Value("${ledger.source-commit:unknown}") String sourceCommit, ContractV001 contract) {
    return new ReceiptContext(
        "http",
        sourceCommit,
        BuildIdentity.ofClass(LedgerApplication.class),
        BuildIdentity.rateTableSha256(contract.rates()));
  }

  @Bean
  public JdbcGenerationStore generationStore(
      JdbcClient db, PlatformTransactionManager txManager, GenerationRepository repo) {
    return JdbcGenerationStore.open(db, new TransactionTemplate(txManager), repo);
  }

  @Bean
  public PolicyLedgerService ledgerService(
      ContractV001 contract, JdbcGenerationStore store, ReceiptContext context) {
    return new PolicyLedgerService(contract, store, context);
  }
}
