* BR-002: GREGORIAN 1900..2099, ORDINAL ZERO = 1900-01-01.
INSDATE  INSENT
         LR    11,1
         USING WORK,11
         MVC   WVALID,=F'8'
         L     3,WDATE
         LTR   3,3
         BNP   DTEXIT
         SR    2,2
         L     4,=F'100'
         DR    2,4
         LR    5,2
         SR    2,2
         DR    2,4
         LR    6,2
         ST    3,WYEAR
         CH    3,=H'1900'
         BL    DTEXIT
         CH    3,=H'2099'
         BH    DTEXIT
         CH    6,=H'1'
         BL    DTEXIT
         CH    6,=H'12'
         BH    DTEXIT
         CH    5,=H'1'
         BL    DTEXIT
         LR    7,6
         BCTR  7,0
         SLL   7,1
         LH    8,MONTHS(7)
         CH    6,=H'2'
         BNE   DTMAX
         CH    3,=H'1900'
         BE    DTMAX
         LR    9,3
         N     9,=F'3'
         BNZ   DTMAX
         LA    8,1(8)
DTMAX    CR    5,8
         BH    DTEXIT
         LR    8,6
         MH    8,=H'100'
         AR    8,5
         ST    8,WMD
         SR    10,10
         L     7,=F'1900'
DTYEAR   CR    7,3
         BNL   DTMONTH
         AH    10,=H'365'
         CH    7,=H'1900'
         BE    DTNEXT
         LR    8,7
         N     8,=F'3'
         BNZ   DTNEXT
         LA    10,1(10)
DTNEXT   LA    7,1(7)
         B     DTYEAR
DTMONTH  LA    7,1
         SR    9,9
DTMLOOP  CR    7,6
         BNL   DTDAY
         AH    10,MONTHS(9)
         CH    7,=H'2'
         BNE   DTMNEXT
         CH    3,=H'1900'
         BE    DTMNEXT
         LR    8,3
         N     8,=F'3'
         BNZ   DTMNEXT
         LA    10,1(10)
DTMNEXT  LA    7,1(7)
         LA    9,2(9)
         B     DTMLOOP
DTDAY    AR    10,5
         BCTR  10,0
         ST    10,WORD
         XC    WVALID,WVALID
DTEXIT   SR    15,15
         INSRET
MONTHS   DC    H'31,28,31,30,31,30,31,31,30,31,30,31'
         LTORG
SAVE     DS    18F
         COPY  INSWORK
         END   INSDATE
