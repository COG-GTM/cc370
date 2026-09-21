package insurance.contract.v001;

import insurance.legacy.codec.Cp037;

/** The fourteen OSTAT values of contract V001, in INSCALC/INSBAT precedence order. */
public enum Status {
  STAT,
  NPOL,
  ORDR,
  CNFL,
  DUPL,
  FORM,
  PACK,
  NEGA,
  OVER,
  DATE,
  TYPE,
  AMNT,
  FUND,
  OKAY;

  private final byte[] cp037 = Cp037.encode(name());

  public byte[] bytes() {
    return cp037.clone();
  }

  public boolean accepted() {
    return this == OKAY;
  }
}
