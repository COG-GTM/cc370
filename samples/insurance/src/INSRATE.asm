* BR-003: PERIOD-END CREDIT BPS, DURATION-BASED SURRENDER CHARGE BPS.
INSRATE  INSENT
         LR    11,1
         USING WORK,11
         L     2,TDATE
         LA    3,RATES
         LA    4,5
RTLOOP   C     2,0(3)
         BL    RTAGE
         MVC   WRATE,4(3)
         MVC   WFEE,7(3)
         LA    3,12(3)
         BCT   4,RTLOOP
RTAGE    L     2,WAGE
         CH    2,=H'10'
         BL    RTEXIT
         ZAP   WFEE,=P'0'
RTEXIT   SR    15,15
         INSRET
RATES    DC    F'19000101',PL3'125',PL3'700',XL2'00'
         DC    F'20000101',PL3'175',PL3'600',XL2'00'
         DC    F'20200101',PL3'225',PL3'500',XL2'00'
         DC    F'20240101',PL3'300',PL3'400',XL2'00'
         DC    F'20250101',PL3'325',PL3'350',XL2'00'
         LTORG
SAVE     DS    18F
         COPY  INSWORK
         END   INSRATE
