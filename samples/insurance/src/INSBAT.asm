* SINGLE WRITER: READ IMMUTABLE MASTER; WRITE A NEW GENERATION.
         COPY  INSWORK
INSBAT   INSENT
         LA    11,AREA
         USING WORK,11
         OPEN  (MASTER,INPUT,TRANS,INPUT,NEWMAST,OUTPUT,RESULT,OUTPUT)
         TM    MASTER+48,X'10'
         BNO   IOFAIL
         TM    TRANS+48,X'10'
         BNO   IOFAIL
         TM    NEWMAST+48,X'10'
         BNO   IOFAIL
         TM    RESULT+48,X'10'
         BNO   IOFAIL
         L     8,=A(TABLE)
         SR    9,9
         XC    PREVID,PREVID
* BR-011: AT MOST 512 UNIQUE POLICIES, STRICT MASTER ID ORDER.
READMAST GET   MASTER,SREC
         CH    9,=H'512'
         BNL   BADMAST
         CLC   SID,PREVID
         BNH   BADMAST
         LR    1,11
         L     15,=V(INSVAL)
         BALR  14,15
         LTR   15,15
         BNZ   BADMAST
         MVC   0(128,8),SREC
         MVC   PREVID,SID
         LA    8,128(8)
         LA    9,1(9)
         B     READMAST
MASTEND  ST    9,COUNT
* BR-012: FILE ORDER IS THE BUSINESS ORDER; NEVER IMPLICITLY SORT.
READTX   GET   TRANS,TREC
         L     8,=A(TABLE)
         L     9,COUNT
         LTR   9,9
         BZ    MISSING
SEARCH   CLC   TID,0(8)
         BE    FOUND
         LA    8,128(8)
         BCT   9,SEARCH
MISSING  XC    SREC,SREC
         LR    1,11
         L     15,=V(INSCALC)
         BALR  14,15
         MVC   OSTAT,=C'NPOL'
         B     WRITERES
FOUND    MVC   SREC,0(8)
         LR    1,11
         L     15,=V(INSCALC)
         BALR  14,15
         CLC   OSTAT,=C'OKAY'
         BNE   WRITERES
         MVC   0(128,8),SREC
WRITERES PUT   RESULT,OREC
         B     READTX
TXEND    L     8,=A(TABLE)
         L     9,COUNT
         LTR   9,9
         BZ    SUCCESS
OUTLOOP  PUT   NEWMAST,(8)
         LA    8,128(8)
         BCT   9,OUTLOOP
SUCCESS  XC    RETCODE,RETCODE
         B     FINISH
BADMAST  MVC   RETCODE,=F'12'
         B     FINISH
IOFAIL   MVC   RETCODE,=F'16'
FINISH   CLOSE (MASTER,,TRANS,,NEWMAST,,RESULT)
         L     15,RETCODE
         INSRET
         LTORG
SAVE     DS    18F
COUNT    DC    F'0'
RETCODE  DC    F'16'
PREVID   DS    CL8
MASTER   DCB   DDNAME=POLIN,DSORG=PS,MACRF=GM,RECFM=FB,                X
               LRECL=128,BLKSIZE=1280,EODAD=MASTEND
TRANS    DCB   DDNAME=TXNIN,DSORG=PS,MACRF=GM,RECFM=FB,                X
               LRECL=40,BLKSIZE=4000,EODAD=TXEND
NEWMAST  DCB   DDNAME=POLOUT,DSORG=PS,MACRF=PM,RECFM=FB,               X
               LRECL=128,BLKSIZE=1280
RESULT   DCB   DDNAME=RESOUT,DSORG=PS,MACRF=PM,RECFM=FB,               X
               LRECL=96,BLKSIZE=9600
         DS    0D
AREA     DS    (WORKLEN)C
TABLE    DS    512CL128
         END   INSBAT
