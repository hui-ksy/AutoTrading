package main.model;

public class FibonacciCalculator {

    private final TradingConfig config;

    // 피보나치 비율
    private static final double[] FIB_LEVELS = {0.236, 0.382, 0.5, 0.618, 0.786, 1.0, 1.618, 2.618};

    public FibonacciCalculator(TradingConfig config) {
        this.config = config;
    }

    // 손절 가격 계산
    public double calculateStopLoss(WavePattern pattern) {
        if (pattern.getType() == WavePattern.Type.IMPULSE) {
            // 파동 2 저점을 손절선으로
            Double wave2Price = pattern.getWavePrice(2);
            if (wave2Price != null) {
                return wave2Price * 0.98; // 2% 여유
            }
        }

        // 기본 손절 (현재가 대비)
        return pattern.getCurrentPrice() * (1 - config.getStopLossPercent() / 100);
    }

    // 익절 가격 계산
    public double calculateTakeProfit(WavePattern pattern) {
        if (pattern.getType() == WavePattern.Type.IMPULSE) {
            // 파동 1의 161.8% 피보나치 확장
            Double wave0 = pattern.getWavePrice(0);
            Double wave1 = pattern.getWavePrice(1);
            Double wave2 = pattern.getWavePrice(2);

            if (wave0 != null && wave1 != null && wave2 != null) {
                double wave1Length = wave1 - wave0;
                return wave2 + (wave1Length * 1.618);
            }
        }

        // 기본 익절
        return pattern.getCurrentPrice() * (1 + config.getTakeProfitPercent() / 100);
    }

    // 피보나치 되돌림 레벨 계산
    public double[] calculateRetracementLevels(double high, double low) {
        double range = high - low;
        double[] levels = new double[FIB_LEVELS.length];

        for (int i = 0; i < FIB_LEVELS.length; i++) {
            levels[i] = high - (range * FIB_LEVELS[i]);
        }

        return levels;
    }

    // 피보나치 확장 레벨 계산
    public double[] calculateExtensionLevels(double start, double end, double retracement) {
        double wave1Length = end - start;
        double[] levels = new double[FIB_LEVELS.length];

        for (int i = 0; i < FIB_LEVELS.length; i++) {
            levels[i] = retracement + (wave1Length * FIB_LEVELS[i]);
        }

        return levels;
    }
}
