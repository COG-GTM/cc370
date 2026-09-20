* BR-001: CHECK ALL DIGITS BEFORE EXECUTING ANY DECIMAL INSTRUCTION.
INSPACK  INSENT
         LR    3,1
         LA    4,7
PKLOOP   SR    2,2
         IC    2,0(3)
         LR    5,2
         SRL   5,4
         CH    5,=H'9'
         BH    BADPACK
         N     2,=X'0000000F'
         BCT   4,PKDIGIT
         CH    2,=H'12'
         BE    PKGOOD
         CH    2,=H'13'
         BE    PKGOOD
         CH    2,=H'15'
         BE    PKGOOD
BADPACK  LA    15,8
         B     PKEXIT
PKDIGIT  CH    2,=H'9'
         BH    BADPACK
         LA    3,1(3)
         B     PKLOOP
PKGOOD   SR    15,15
PKEXIT   INSRET
         LTORG
SAVE     DS    18F
         END   INSPACK
