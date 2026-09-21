package insurance.contract.v001;

import java.util.OptionalInt;

/**
 * INSDATE: proleptic Gregorian calendar restricted to 1900-01-01..2099-12-31 on {@code yyyymmdd}
 * fullwords. 1900 is not a leap year; every other year divisible by four in range is.
 */
public final class LegacyCalendar {
  public static final int MIN_YEAR = 1900;
  public static final int MAX_YEAR = 2099;

  private static final int[] MONTH_DAYS = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};

  private LegacyCalendar() {}

  /** Decoded date parts for a valid legacy date. */
  public record Parts(int year, int month, int day) {
    /** {@code month*100 + day}, the WMD used by the age comparison. */
    public int monthDay() {
      return month * 100 + day;
    }
  }

  public static boolean isLeap(int year) {
    return year % 4 == 0 && year != 1900;
  }

  public static boolean isValid(int yyyymmdd) {
    return parts(yyyymmdd).isPresent();
  }

  public static java.util.Optional<Parts> parts(int yyyymmdd) {
    if (yyyymmdd <= 0) {
      return java.util.Optional.empty();
    }
    int year = yyyymmdd / 10000;
    int month = (yyyymmdd / 100) % 100;
    int day = yyyymmdd % 100;
    if (year < MIN_YEAR || year > MAX_YEAR || month < 1 || month > 12 || day < 1) {
      return java.util.Optional.empty();
    }
    int limit = MONTH_DAYS[month - 1];
    if (month == 2 && isLeap(year)) {
      limit = 29;
    }
    if (day > limit) {
      return java.util.Optional.empty();
    }
    return java.util.Optional.of(new Parts(year, month, day));
  }

  /** Day ordinal with 1900-01-01 = 0, for a valid legacy date. */
  public static OptionalInt ordinal(int yyyymmdd) {
    var p = parts(yyyymmdd);
    if (p.isEmpty()) {
      return OptionalInt.empty();
    }
    Parts d = p.get();
    int days = 0;
    for (int y = MIN_YEAR; y < d.year(); y++) {
      days += isLeap(y) ? 366 : 365;
    }
    for (int m = 1; m < d.month(); m++) {
      days += MONTH_DAYS[m - 1];
      if (m == 2 && isLeap(d.year())) {
        days += 1;
      }
    }
    return OptionalInt.of(days + d.day() - 1);
  }

  /** Whole completed years from {@code issue} to {@code at}; both must be valid. */
  public static int age(int issue, int at) {
    Parts i = parts(issue).orElseThrow();
    Parts a = parts(at).orElseThrow();
    int age = a.year() - i.year();
    if (a.monthDay() < i.monthDay()) {
      age -= 1;
    }
    return age;
  }

  /** Largest span in range: 1900-01-01..2099-12-31. */
  public static int maxSpanDays() {
    return ordinal(20991231).orElseThrow() - ordinal(19000101).orElseThrow();
  }
}
