package main.model.config;

import lombok.Data;
import main.model.domain.StrategyType;

@Data
public class SymbolConfig {
    private String symbol;
    private StrategyType strategyType;
    private String timeframe;
    private int leverage;
    private int candleLimit;
    private int bbPeriod;
    private double bbStdDev;
    private int rsiPeriod;
    private int rsiOversold;
    private int rsiOverbought;
    private double slMult;
    private double tpMult;
    private long candleIntervalSeconds;
    private long tickerIntervalSeconds;
    private int trendFilterEma;
    private double bbWidthMult;

    public static SymbolConfig defaults(String symbol) {
        SymbolConfig result = new SymbolConfig();
        result.symbol        = symbol;
        result.strategyType  = StrategyType.BOLLINGER_BAND_REVERSION;
        result.timeframe     = "15m";
        result.leverage      = 10;
        result.candleLimit   = 100;
        result.bbPeriod      = 17;
        result.bbStdDev      = 2.6;
        result.rsiPeriod     = 14;
        result.rsiOversold   = 30;
        result.rsiOverbought = 70;
        result.slMult                = 3.5;
        result.tpMult                = 5.0;
        result.candleIntervalSeconds = 15;
        result.tickerIntervalSeconds = 1;
        result.trendFilterEma        = 0;
        result.bbWidthMult           = 0.0;
        return result;
    }
}