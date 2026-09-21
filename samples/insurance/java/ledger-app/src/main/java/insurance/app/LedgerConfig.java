package insurance.app;

import insurance.app.persistence.GenerationRepository;
import insurance.app.persistence.JdbcGenerationStore;
import insurance.app.persistence.KillSwitch;
import insurance.contract.v001.ContractV001;
import insurance.ledger.BuildIdentity;
import insurance.ledger.ContractBinding;
import insurance.ledger.PolicyLedgerService;
import insurance.ledger.generation.ReceiptContext;
import java.time.Duration;
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

  /** Content identity of the calculation core and codecs; pinned by every generation. */
  @Bean
  public ContractBinding contractBinding(ContractV001 contract) {
    return ContractBinding.of(contract);
  }

  @Bean
  public ReceiptContext receiptContext(
      @Value("${ledger.source-commit:unknown}") String sourceCommit, ContractBinding binding) {
    return new ReceiptContext(
        "http",
        sourceCommit,
        BuildIdentity.ofClass(LedgerApplication.class),
        binding.rateTableSha256());
  }

  /**
   * The writer lease is how another instance's startup distinguishes a live writer from an orphaned
   * pending generation; it is renewed by every commit and by the store heartbeat. {@code
   * ledger.kill-switch} is the process-kill test hook ({@link KillSwitch}); unset in normal use.
   */
  @Bean(destroyMethod = "close")
  public JdbcGenerationStore generationStore(
      JdbcClient db,
      PlatformTransactionManager txManager,
      GenerationRepository repo,
      @Value("${ledger.writer-lease:PT60S}") Duration writerLease,
      @Value("${ledger.kill-switch:}") String killSwitch,
      ContractBinding binding) {
    return JdbcGenerationStore.open(
        db,
        new TransactionTemplate(txManager),
        repo,
        writerLease,
        KillSwitch.parse(killSwitch),
        binding);
  }

  @Bean
  public PolicyLedgerService ledgerService(
      ContractV001 contract, JdbcGenerationStore store, ReceiptContext context) {
    return new PolicyLedgerService(contract, store, context);
  }
}
