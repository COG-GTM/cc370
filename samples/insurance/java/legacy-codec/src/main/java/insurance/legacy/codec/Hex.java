package insurance.legacy.codec;

import java.util.HexFormat;

/** Lower-case hexadecimal rendering, the same convention as the Python tools. */
public final class Hex {
  private static final HexFormat FORMAT = HexFormat.of();

  private Hex() {}

  public static String of(byte[] raw) {
    return FORMAT.formatHex(raw);
  }

  public static String of(byte[] raw, int offset, int length) {
    return FORMAT.formatHex(raw, offset, offset + length);
  }

  public static byte[] parse(String hex) {
    return FORMAT.parseHex(hex);
  }
}
