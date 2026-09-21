package insurance.legacy.codec;

/**
 * Signed packed decimal ({@code PLn}): {@code 2n-1} digit nibbles followed by a sign nibble.
 *
 * <p>Input accepts sign nibbles {@code C} (positive), {@code D} (negative) and {@code F} (unsigned,
 * positive), exactly like INSPACK. Output always uses the preferred codes {@code C} and {@code D},
 * exactly like the ZAP/AP/SP results of the assembler. Negative zero ({@code ...0D}) is numerically
 * zero; callers that need the original sign use {@link #signNibble}.
 */
public final class Packed {
  public static final int SIGN_POSITIVE = 0xC;
  public static final int SIGN_NEGATIVE = 0xD;
  public static final int SIGN_UNSIGNED = 0xF;

  private static final long[] POW10 = new long[19];

  static {
    POW10[0] = 1L;
    for (int i = 1; i < POW10.length; i++) {
      POW10[i] = POW10[i - 1] * 10L;
    }
  }

  private Packed() {}

  /** Number of decimal digits a {@code PLn} field holds. */
  public static int digits(int length) {
    return 2 * length - 1;
  }

  /** Largest magnitude a {@code PLn} field holds: {@code 10^(2n-1) - 1}. */
  public static long maxMagnitude(int length) {
    return POW10[digits(length)] - 1L;
  }

  /** INSPACK rule: every digit nibble is 0..9 and the sign nibble is C, D or F. */
  public static boolean isValid(byte[] raw, int offset, int length) {
    for (int i = 0; i < length; i++) {
      int b = raw[offset + i] & 0xFF;
      if ((b >>> 4) > 9) {
        return false;
      }
      int low = b & 0x0F;
      if (i < length - 1) {
        if (low > 9) {
          return false;
        }
      } else if (low != SIGN_POSITIVE && low != SIGN_NEGATIVE && low != SIGN_UNSIGNED) {
        return false;
      }
    }
    return true;
  }

  public static int signNibble(byte[] raw, int offset, int length) {
    return raw[offset + length - 1] & 0x0F;
  }

  /** Signed value of a field previously accepted by {@link #isValid}. */
  public static long value(byte[] raw, int offset, int length) {
    if (length > 9) {
      throw new IllegalArgumentException("packed length " + length + " exceeds long range");
    }
    if (!isValid(raw, offset, length)) {
      throw new IllegalArgumentException("invalid packed decimal " + Hex.of(raw, offset, length));
    }
    long magnitude = 0L;
    for (int i = 0; i < length; i++) {
      int b = raw[offset + i] & 0xFF;
      magnitude = magnitude * 10L + (b >>> 4);
      if (i < length - 1) {
        magnitude = magnitude * 10L + (b & 0x0F);
      }
    }
    return signNibble(raw, offset, length) == SIGN_NEGATIVE ? -magnitude : magnitude;
  }

  public static boolean isNegativeZero(byte[] raw, int offset, int length) {
    return isValid(raw, offset, length)
        && signNibble(raw, offset, length) == SIGN_NEGATIVE
        && value(raw, offset, length) == 0L;
  }

  /**
   * Writes {@code value} with the preferred sign ({@code C} for zero and positive, {@code D} for
   * negative). Throws when the magnitude does not fit, which corresponds to a decimal overflow the
   * assembler would raise or truncate; the contract treats it as a domain fault, never as data.
   */
  public static void put(byte[] raw, int offset, int length, long value) {
    long magnitude = Math.abs(value);
    if (value == Long.MIN_VALUE || length > 9 || magnitude > maxMagnitude(length)) {
      throw new PackedOverflowException(value, length);
    }
    int sign = value < 0 ? SIGN_NEGATIVE : SIGN_POSITIVE;
    raw[offset + length - 1] = (byte) (((magnitude % 10L) << 4) | sign);
    magnitude /= 10L;
    for (int i = length - 2; i >= 0; i--) {
      int low = (int) (magnitude % 10L);
      magnitude /= 10L;
      int high = (int) (magnitude % 10L);
      magnitude /= 10L;
      raw[offset + i] = (byte) ((high << 4) | low);
    }
  }

  public static byte[] of(long value, int length) {
    byte[] raw = new byte[length];
    put(raw, 0, length, value);
    return raw;
  }

  /** Raised when a value does not fit the declared packed width. */
  public static final class PackedOverflowException extends ArithmeticException {
    private static final long serialVersionUID = 1L;

    PackedOverflowException(long value, int length) {
      super("value " + value + " does not fit PL" + length);
    }
  }
}
