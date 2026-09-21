package insurance.app;

import insurance.ledger.batch.BatchCli;
import insurance.ledger.batch.RoutineCli;
import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Single deployable. {@code java -jar ledger-app.jar batch ...} runs the file-backed batch adapter
 * and {@code routines ...} the direct-call routine oracle, both without starting Spring; any other
 * invocation starts the stateful HTTP service.
 */
@SpringBootApplication
public class LedgerApplication {
  public static void main(String[] args) {
    if (args.length > 0 && args[0].equals("batch")) {
      System.exit(BatchCli.run(Arrays.copyOfRange(args, 1, args.length), System.out, System.err));
    }
    if (args.length > 0 && args[0].equals("routines")) {
      System.exit(RoutineCli.run(Arrays.copyOfRange(args, 1, args.length), System.out, System.err));
    }
    SpringApplication.run(LedgerApplication.class, args);
  }
}
