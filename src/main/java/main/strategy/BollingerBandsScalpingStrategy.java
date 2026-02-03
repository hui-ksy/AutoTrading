package main.strategy;

import lombok.extern.slf4j.Slf4j;
import main.indicator.TechnicalIndicators;
import main.model.Candle;
import main.model.Position;
import main.model.Signal;
import main.model.TradingConfig;

import java.util.List;

@Slf4j
public class BollingerBandsScalpingStrategy implements TradingStrategy {

    private final TradingConfig config;
    private final int period;
    private final double stdDev;

    public BollingerBandsScalpingStrategy(TradingConfig config) {
        this.config = config;
        this.period = config.getBollingerBandsPeriod();
        this.stdDev = config.getBollingerBandsStdDev();
    }

    @Override
    public Signal generateSignal(List<Candle> candles, String pair, Position position) {
        if (candles.size() < period + 1) {
            return Signal.builder().action(Signal.Action.HOLD).build();
        }

        TechnicalIndicators.BollingerBandsResult bb = TechnicalIndicators.calculateBollingerBands(candles, period, stdDev);
        if (bb == null) return Signal.builder().action(Signal.Action.HOLD).build();

        Candle currentCandle = candles.get(candles.size() - 1);
        double high = currentCandle.getHigh();
        double low = currentCandle.getLow();
        double close = currentCandle.getClose();

        // --- 청산 로직 ---
        if (position != null) {
            if (candles.get(candles.size() - 1).getTimestamp() == position.getEntryTimestamp()) {
                return Signal.builder().action(Signal.Action.HOLD).build();
            }

            boolean isLong = "BUY".equals(position.getSide());
            if (isLong) {
                if (high >= bb.getUpperBand()) {
                    return Signal.builder().action(Signal.Action.EXIT).reason("Bollinger Band Upper Touch").build();
                }
            } else {
                if (low <= bb.getLowerBand()) {
                    return Signal.builder().action(Signal.Action.EXIT).reason("Bollinger Band Lower Touch").build();
                }
            }
            return Signal.builder().action(Signal.Action.HOLD).build();
        }

        // --- 진입 로직 ---
        
        // Long 진입: 하단 밴드 터치
        boolean isLongSignal = low <= bb.getLowerBand();

        if (config.isAllowLong() && isLongSignal) {
            return createSignal(close, Signal.Action.BUY);
        }

        // Short 진입: 상단 밴드 터치
        boolean isShortSignal = high >= bb.getUpperBand();

        if (config.isAllowShort() && isShortSignal) {
            return createSignal(close, Signal.Action.SHORT);
        }

        return Signal.builder().action(Signal.Action.HOLD).build();
    }

    private Signal createSignal(double price, Signal.Action action) {
        double stopLossPercent = config.getStopLossPercent() / 100.0;
        double stopLossPrice = (action == Signal.Action.BUY) 
                ? price * (1 - stopLossPercent) 
                : price * (1 + stopLossPercent);

        return Signal.builder()
                .action(action)
                .entryPrice(price)
                .stopLoss(stopLossPrice)
                .reason("Bollinger Bands Touch Scalping")
                .build();
    }
}
