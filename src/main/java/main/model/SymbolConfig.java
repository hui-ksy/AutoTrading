package main.model;

import lombok.Data;

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

    public static SymbolConfig defaults(String symbol) {
        SymbolConfig sc = new SymbolConfig();
        sc.symbol        = symbol;
        sc.strategyType  = StrategyType.BOLLINGER_BAND_REVERSION;
        sc.timeframe     = "15m";
        sc.leverage      = 10;
        sc.candleLimit   = 100;
        sc.bbPeriod      = 17;
        sc.bbStdDev      = 2.6;
        sc.rsiPeriod     = 14;
        sc.rsiOversold   = 30;
        sc.rsiOverbought = 70;
        sc.slMult                = 3.5;
        sc.tpMult                = 5.0;
        sc.candleIntervalSeconds = 15;
        sc.tickerIntervalSeconds = 1;
        return sc;
    }
}