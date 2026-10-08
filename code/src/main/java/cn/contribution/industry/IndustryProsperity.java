package cn.contribution.industry;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Relative construction momentum; stable and empty industries are neutral. */
public final class IndustryProsperity {
    private IndustryProsperity() {}

    public static BigDecimal calculate(long daily, BigDecimal longEma, BigDecimal shortEma) {
        return shortEma.subtract(longEma)
                .multiply(new BigDecimal("0.7"))
                .add(BigDecimal.valueOf(daily).subtract(longEma).multiply(new BigDecimal("0.3")))
                .divide(longEma.add(BigDecimal.ONE), 8, RoundingMode.HALF_UP);
    }
}
