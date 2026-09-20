* SYNTHETIC CONTRACT V001. ONE CALL, ONE POLICY, NO OS DEPENDENCIES.
INSCALC  INSENT
         LR    11,1
         USING WORK,11
         MVC   WBEFORE,SREC
         XC    OREC,OREC
         MVC   OID,TID
         MVC   OSEQ,TSEQ
         MVC   ODATE,TDATE
         MVC   OOP,TOP
         MVC   OVERS,=C'V001'
         ZAP   ORATE,=P'0'
         ZAP   OFEE,=P'0'
         ZAP   OCASH,=P'0'
         ZAP   OSURR,=P'0'
         ZAP   ODEATH,=P'0'
         ZAP   OLOAN,=P'0'
         ZAP   OINT,=P'0'
         ZAP   OCHG,=P'0'
         MVC   OSTAT,=C'STAT'
         L     15,=V(INSVAL)
         BALR  14,15
         LTR   15,15
         BNZ   EXIT
         ZAP   OCASH,SCASH
         ZAP   OLOAN,SLOAN
         MVC   OSTAT,=C'NPOL'
         CLC   SID,TID
         BNE   EXIT
* BR-005: LAST ACCEPTED REQUEST IS AN EXACT REPLAY TOKEN.
         MVC   OSTAT,=C'ORDR'
         L     2,TSEQ
         LTR   2,2
         BNP   EXIT
         C     2,SSEQ
         BL    EXIT
         BH    NEWTX
         MVC   OSTAT,=C'CNFL'
         CLC   TREC,SLAST
         BNE   EXIT
         MVC   OSTAT,=C'DUPL'
         B     EXIT
NEWTX    MVC   OSTAT,=C'FORM'
         CLC   TPAD,=XL3'00'
         BNE   EXIT
         CLC   TTAIL,=XL13'00'
         BNE   EXIT
         MVC   OSTAT,=C'PACK'
         LA    1,TAMT
         L     15,=V(INSPACK)
         BALR  14,15
         LTR   15,15
         BNZ   EXIT
         MVC   OSTAT,=C'NEGA'
         CP    TAMT,=P'0'
         BL    EXIT
         MVC   OSTAT,=C'OVER'
         CP    TAMT,MAXAMT
         BH    EXIT
* BR-006: MONOTONE EFFECTIVE DATES, MAR 1 FEB-29 ANNIVERSARIES.
         MVC   OSTAT,=C'DATE'
         MVC   WDATE,TDATE
         LR    1,11
         L     15,=V(INSDATE)
         BALR  14,15
         CLC   WVALID,=F'0'
         BNE   EXIT
         MVC   WOLDORD,WORD
         MVC   WISSYR,WYEAR
         MVC   WISSMD,WMD
         L     2,TDATE
         C     2,SDATE
         BL    EXIT
         MVC   WDATE,SISSUE
         LR    1,11
         L     15,=V(INSDATE)
         BALR  14,15
         L     2,WISSYR
         S     2,WYEAR
         L     3,WISSMD
         C     3,WMD
         BNL   AGEOK
         BCTR  2,0
AGEOK    ST    2,WAGE
         MVC   WDATE,SDATE
         LR    1,11
         L     15,=V(INSDATE)
         BALR  14,15
         L     2,WOLDORD
         S     2,WORD
         ST    2,WDAYS
         LR    1,11
         L     15,=V(INSRATE)
         BALR  14,15
* BR-007: INTEREST HALF-UP, ACTUAL/365, PERIOD-END RATE.
         MVC   OSTAT,=C'OVER'
         CVD   2,WNUM
         ZAP   WPROD,SCASH
         MP    WPROD,WRATE
         MP    WPROD,WNUM+5(3)
         AP    WPROD,=PL5'1825000'
         DP    WPROD,=PL5'3650000'
         CP    WPROD(7),MAXAMT
         BH    FAIL
         ZAP   OINT,WPROD(7)
         AP    SCASH,OINT
         CP    SCASH,MAXAMT
         BH    FAIL
* BR-008: INDIRECT OPERATION DISPATCH THROUGH RELOCATABLE POINTERS.
         MVC   OSTAT,=C'TYPE'
         LA    3,OPTABLE
         LA    4,6
OPLOOP   CLC   TOP,0(3)
         BE    OPFOUND
         LA    3,8(3)
         BCT   4,OPLOOP
         B     FAIL
OPFOUND  L     15,4(3)
         BR    15
PREMIUM  AP    SCASH,TAMT
         B     LIMITS
WITHDRAW SP    SCASH,TAMT
         B     LIMITS
LOAN     AP    SLOAN,TAMT
         B     LIMITS
REPAY    SP    SLOAN,TAMT
         B     LIMITS
QUOTE    MVC   OSTAT,=C'AMNT'
         CP    TAMT,=P'0'
         BNE   FAIL
LIMITS   MVC   OSTAT,=C'FUND'
         CP    SCASH,=P'0'
         BL    FAIL
         CP    SLOAN,=P'0'
         BL    FAIL
         CP    SLOAN,SCASH
         BH    FAIL
         MVC   OSTAT,=C'OVER'
         CP    SCASH,MAXAMT
         BH    FAIL
         CP    SLOAN,MAXAMT
         BH    FAIL
* BR-009: SURRENDER CHARGE IS ROUNDED BEFORE LOAN DEDUCTION.
         ZAP   WPROD,SCASH
         MP    WPROD,WFEE
         AP    WPROD,=PL3'5000'
         DP    WPROD,=PL5'10000'
         ZAP   OCHG,WPROD(7)
         ZAP   OSURR,SCASH
         SP    OSURR,OCHG
         SP    OSURR,SLOAN
         CP    OSURR,=P'0'
         BNL   DEATH
         ZAP   OSURR,=P'0'
* BR-010: DEATH QUOTE = MAX(FACE,CASH) LESS LOAN.
DEATH    ZAP   ODEATH,SFACE
         CP    SFACE,SCASH
         BNL   DLOAN
         ZAP   ODEATH,SCASH
DLOAN    SP    ODEATH,SLOAN
         CP    ODEATH,=P'0'
         BNL   COMMIT
         ZAP   ODEATH,=P'0'
COMMIT   MVC   SSEQ,TSEQ
         MVC   SDATE,TDATE
         MVC   SLAST,TREC
         MVC   OSTAT,=C'OKAY'
         MVC   OAGE,WAGE
         ZAP   ORATE,WRATE
         ZAP   OFEE,WFEE
         ZAP   OCASH,SCASH
         ZAP   OLOAN,SLOAN
         B     EXIT
FAIL     MVC   SREC,WBEFORE
         ZAP   OINT,=P'0'
EXIT     SR    15,15
         INSRET
         DS    0F
OPTABLE  DC    CL1'P',XL3'00',A(PREMIUM)
         DC    CL1'W',XL3'00',A(WITHDRAW)
         DC    CL1'L',XL3'00',A(LOAN)
         DC    CL1'R',XL3'00',A(REPAY)
         DC    CL1'Q',XL3'00',A(QUOTE)
         DC    CL1'D',XL3'00',A(QUOTE)
MAXAMT   DC    PL7'99999999999'
         LTORG
SAVE     DS    18F
         COPY  INSWORK
         END   INSCALC
