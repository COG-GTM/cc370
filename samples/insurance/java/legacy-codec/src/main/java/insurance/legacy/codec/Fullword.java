package insurance.legacy.codec;

/** Big-endian signed 32-bit fullword ({@code DS F}). */
public final class Fullword {
  public static final int SIZE = 4;

  private Fullword() {}

  public static int get(byte[] raw, int offset) {
    return ((raw[offset] & 0xFF) << 24)
        | ((raw[offset + 1] & 0xFF) << 16)
        | ((raw[offset + 2] & 0xFF) << 8)
        | (raw[offset + 3] & 0xFF);
  }

  public static void put(byte[] raw, int offset, int value) {
    raw[offset] = (byte) (value >>> 24);
    raw[offset + 1] = (byte) (value >>> 16);
    raw[offset + 2] = (byte) (value >>> 8);
    raw[offset + 3] = (byte) value;
  }

  public static byte[] of(int value) {
    byte[] raw = new byte[SIZE];
    put(raw, 0, value);
    return raw;
  }
}
