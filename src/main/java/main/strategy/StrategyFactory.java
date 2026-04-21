package main.strategy;

import lombok.extern.slf4j.Slf4j;
import main.model.StrategyType;
import main.model.TradingConfig;

@Slf4j
public class StrategyFactory {
    public static TradingStrategy createStrategy(TradingConfig config) {
        StrategyType type = config.getStrategyType();
        log.info("전략 생성: {}", type);
        switch (type) {
            case BOLLINGER_BANDS_SCALPING:
                return new BollingerBandsScalpingStrategy(config);
            case DONCHIAN_BREAKOUT:
                return new DonchianBreakoutStrategy(config);
            case DOWNTREND_RSI:
                return new DowntrendRsiStrategy(config);
            case EMA_STOCHASTIC_SCALPING:
                return new EmaStochasticScalpingStrategy(config);
            case TRIPLE_EMA:
                return new TripleEMAStrategy(config);
            case ELLIOTT_WAVE:
                return new ElliottWaveStrategy(config);
            case ORDER_BLOCK:
                return new OrderBlockStrategy(config);
            case MACD_ATR_TREND:
                return new MacdAtrTrendStrategy(config);
            case HEIKIN_ASHI_ICHIMOKU:
                return new HeikinAshiIchimokuStrategy(config);
            case SUPER_TREND:
                return new SuperTrendStrategy(config);
            case DYNAMIC_EXIT_SCALPING:
                return new DynamicExitScalpingStrategy(config);
            case BOLLINGER_BAND_REVERSION:
                return new BollingerBandReversionStrategy(config);
            default:
                throw new IllegalArgumentException("Unknown strategy type: " + type);
        }
    }
}
