package insurance.ledger;

import insurance.legacy.codec.Hex;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class Sha256 {
  private Sha256() {}

  public static String of(byte[] raw) {
    try {
      return Hex.of(MessageDigest.getInstance("SHA-256").digest(raw));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
