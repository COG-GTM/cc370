package insurance.ledger.generation;

import insurance.ledger.batch.BatchCli;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Launches a second JVM running the batch CLI against a store directory. */
final class TwoProcess {
  private TwoProcess() {}

  /** Exit status of {@link Runtime#halt(int)} in the CLI kill switches. */
  static final int HALTED = 137;

  static String classpath() {
    String surefire = System.getProperty("surefire.test.class.path");
    return surefire != null ? surefire : System.getProperty("java.class.path");
  }

  static String javaBinary() {
    return Path.of(System.getProperty("java.home"), "bin", "java").toString();
  }

  /** {@code batch verify --store dir}: exit 5 when the store is locked by a live writer. */
  static Process tryOpen(Path store) throws IOException {
    return start("verify", "--store", store.toString());
  }

  static Process start(String... cliArgs) throws IOException {
    return start(List.of(), cliArgs);
  }

  static Process start(List<String> jvmOptions, String... cliArgs) throws IOException {
    List<String> cmd = new ArrayList<>();
    cmd.add(javaBinary());
    cmd.addAll(jvmOptions);
    cmd.add("-cp");
    cmd.add(classpath());
    cmd.add(BatchCli.class.getName());
    cmd.addAll(List.of(cliArgs));
    return new ProcessBuilder(cmd).redirectErrorStream(true).start();
  }

  /** Runs to completion and returns exit code plus combined stdout/stderr. */
  static Result run(List<String> jvmOptions, String... cliArgs)
      throws IOException, InterruptedException {
    Process p = start(jvmOptions, cliArgs);
    String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    return new Result(p.waitFor(), output);
  }

  static Result run(String... cliArgs) throws IOException, InterruptedException {
    return run(List.of(), cliArgs);
  }

  record Result(int exit, String output) {}
}
