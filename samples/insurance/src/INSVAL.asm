* BR-004: MASTER STATE IS VALIDATED BEFORE IT CAN BECOME A CANDIDATE.
INSVAL   INSENT
         LR    11,1
         USING WORK,11
         MVC   WVALID,=F'8'
         LA    6,SID
         LA    7,8
IDLOOP   CLI   0(6),C'0'
         BL    VLBAD
         CLI   0(6),C'9'
         BH    VLBAD
         LA    6,1(6)
         BCT   7,IDLOOP
         CLC   SPAD,=XL7'00'
         BNE   VLBAD
         CLC   STAIL,=XL40'00'
         BNE   VLBAD
         L     2,SSEQ
         LTR   2,2
         BM    VLBAD
         LA    6,SFACE
         LA    7,3
VLNUM    LR    1,6
         L     15,=V(INSPACK)
         BALR  14,15
         LTR   15,15
         BNZ   VLBAD
         CP    0(7,6),=P'0'
         BL    VLBAD
         CP    0(7,6),MAXAMT
         BH    VLBAD
         LA    6,7(6)
         BCT   7,VLNUM
         CP    SLOAN,SCASH
         BH    VLBAD
         MVC   WDATE,SISSUE
         LR    1,11
         L     15,=V(INSDATE)
         BALR  14,15
         CLC   WVALID,=F'0'
         BNE   VLBAD
         MVC   WDATE,SDATE
         LR    1,11
         L     15,=V(INSDATE)
         BALR  14,15
         CLC   WVALID,=F'0'
         BNE   VLBAD
         L     2,SDATE
         C     2,SISSUE
         BL    VLBAD
         L     2,SSEQ
         LTR   2,2
         BNZ   VLHIST
         CLC   SLAST,=XL40'00'
         BNE   VLBAD
         CLC   SDATE,SISSUE
         BNE   VLBAD
         B     VLGOOD
VLHIST   CLC   SLAST(8),SID
         BNE   VLBAD
         CLC   SLAST+8(4),SSEQ
         BNE   VLBAD
         CLC   SLAST+12(4),SDATE
         BNE   VLBAD
VLGOOD   XC    WVALID,WVALID
         SR    15,15
         B     VLEXIT
VLBAD    MVC   WVALID,=F'8'
         LA    15,8
VLEXIT   INSRET
MAXAMT   DC    PL7'99999999999'
         LTORG
SAVE     DS    18F
         COPY  INSWORK
         END   INSVAL
