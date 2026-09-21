package insurance.legacy.codec;

import java.nio.charset.Charset;
import java.util.Arrays;

/** CP037 text. Binary fields are never routed through this class. */
public final class Cp037 {
  public static final Charset CHARSET = Charset.forName("IBM037");
  public static final byte DIGIT_ZERO = (byte) 0xF0;
  public static final byte DIGIT_NINE = (byte) 0xF9;

  private Cp037() {}

  public static byte[] encode(String text) {
    return text.getBytes(CHARSET);
  }

  public static String decode(byte[] raw, int offset, int length) {
    return new String(raw, offset, length, CHARSET);
  }

  public static String decode(byte[] raw) {
    return decode(raw, 0, raw.length);
  }

  /** True when every byte is a CP037 digit {@code F0..F9} (the INSVAL identifier rule). */
  public static boolean allDigits(byte[] raw, int offset, int length) {
    for (int i = offset; i < offset + length; i++) {
      int b = raw[i] & 0xFF;
      if (b < (DIGIT_ZERO & 0xFF) || b > (DIGIT_NINE & 0xFF)) {
        return false;
      }
    }
    return true;
  }

  /**
   * True when the text round-trips exactly through CP037, i.e. re-encoding the decoded text
   * reproduces the same bytes. Used to decide whether a raw field is representable as text.
   */
  public static boolean roundTrips(byte[] raw, int offset, int length) {
    byte[] slice = Arrays.copyOfRange(raw, offset, offset + length);
    String text = decode(slice);
    return Arrays.equals(slice, encode(text));
  }
}
