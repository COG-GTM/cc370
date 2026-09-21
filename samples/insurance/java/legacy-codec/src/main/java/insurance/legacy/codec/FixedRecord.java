package insurance.legacy.codec;

import java.util.Arrays;

/** Immutable byte-backed fixed-length record. Typed accessors never normalize the bytes. */
public abstract class FixedRecord {
  private final byte[] raw;

  protected FixedRecord(byte[] raw, int length) {
    if (raw.length != length) {
      throw new IllegalArgumentException(
          getClass().getSimpleName() + ": length " + raw.length + ", expected " + length);
    }
    this.raw = raw.clone();
  }

  public final byte[] bytes() {
    return raw.clone();
  }

  public final int length() {
    return raw.length;
  }

  public final byte[] slice(int offset, int length) {
    return Arrays.copyOfRange(raw, offset, offset + length);
  }

  public final boolean isZero(int offset, int length) {
    for (int i = offset; i < offset + length; i++) {
      if (raw[i] != 0) {
        return false;
      }
    }
    return true;
  }

  protected final int fullword(int offset) {
    return Fullword.get(raw, offset);
  }

  protected final byte[] raw() {
    return raw;
  }

  public final String hex() {
    return Hex.of(raw);
  }

  @Override
  public final boolean equals(Object other) {
    return other != null
        && other.getClass() == getClass()
        && Arrays.equals(raw, ((FixedRecord) other).raw);
  }

  @Override
  public final int hashCode() {
    return Arrays.hashCode(raw);
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + "[" + hex() + "]";
  }
}
