package insurance.legacy.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PackedTest {
  @Test
  void encodesPreferredSigns() {
    assertEquals("0000000000000c", Hex.of(Packed.of(0, 7)));
    assertEquals("0000000012345c", Hex.of(Packed.of(12345, 7)));
    assertEquals("0000000012345d", Hex.of(Packed.of(-12345, 7)));
    assertEquals("9999999999999c", Hex.of(Packed.of(9_999_999_999_999L, 7)));
    assertEquals("00325c", Hex.of(Packed.of(325, 3)));
  }

  @Test
  void acceptsCdfSignsOnInput() {
    assertEquals(7, Packed.value(Hex.parse("0000000000007c"), 0, 7));
    assertEquals(-7, Packed.value(Hex.parse("0000000000007d"), 0, 7));
    assertEquals(7, Packed.value(Hex.parse("0000000000007f"), 0, 7));
    assertEquals(Packed.SIGN_UNSIGNED, Packed.signNibble(Hex.parse("0000000000007f"), 0, 7));
  }

  @Test
  void rejectsInvalidNibblesLikeInspack() {
    assertFalse(Packed.isValid(Hex.parse("000000000000a0"), 0, 7));
    assertFalse(Packed.isValid(Hex.parse("0a00000000000c"), 0, 7));
    assertFalse(Packed.isValid(Hex.parse("0000000000000e"), 0, 7));
    assertFalse(Packed.isValid(Hex.parse("0000000000000b"), 0, 7));
    assertTrue(Packed.isValid(Hex.parse("0000000000000f"), 0, 7));
    assertThrows(IllegalArgumentException.class, () -> Packed.value(new byte[7], 0, 7));
  }

  @Test
  void negativeZeroIsZeroButKeepsItsSign() {
    byte[] raw = Hex.parse("0000000000000d");
    assertEquals(0, Packed.value(raw, 0, 7));
    assertTrue(Packed.isNegativeZero(raw, 0, 7));
    assertFalse(Packed.isNegativeZero(Hex.parse("0000000000000c"), 0, 7));
  }

  @Test
  void overflowFailsClosed() {
    assertThrows(Packed.PackedOverflowException.class, () -> Packed.of(10_000_000_000_000L, 7));
    assertThrows(Packed.PackedOverflowException.class, () -> Packed.of(100_000, 3));
    assertEquals(9_999_999_999_999L, Packed.maxMagnitude(7));
    assertEquals(13, Packed.digits(7));
  }

  @Test
  void fullwordRoundTrips() {
    assertArrayEquals(Hex.parse("0134d8e9"), Fullword.of(20240617));
    assertArrayEquals(Hex.parse("0134fdf5"), Fullword.of(20250101));
    assertEquals(-1, Fullword.get(Hex.parse("ffffffff"), 0));
    assertEquals(20250101, Fullword.get(Fullword.of(20250101), 0));
  }

  @Test
  void cp037Digits() {
    assertEquals("f0f0f0f0f0f0f3f1", Hex.of(Cp037.encode("00000031")));
    assertTrue(Cp037.allDigits(Cp037.encode("00000031"), 0, 8));
    assertFalse(Cp037.allDigits(Cp037.encode("0000003A"), 0, 8));
    assertEquals("d7", Hex.of(Cp037.encode("P")));
  }
}
