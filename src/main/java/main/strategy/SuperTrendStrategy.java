package main.strategy;

import lombok.extern.slf4j.Slf4j;
import main.indicator.TechnicalIndicators;
import main.model.Candle;
import main.model.Position;
import main.model.Signal;
import main.model.TradingConfig;

import java.util.List;

@Slf4j
public class SuperTrendStrategy implements TradingStrategy {

    private final TradingConfig config;
    private final int atrPeriod;
    private final double multiplier;
    
    // [수정] EMA 필터 제거, ADX 필터만 유지
    private final int adxPeriod;
    private final double adxThreshold;

    public SuperTrendStrategy(TradingConfig config) {
        this.config = config;
        this.atrPeriod = config.getAtrPeriod() > 0 ? config.getAtrPeriod() : 14;
        this.multiplier = config.getAtrSlMultiplier() > 0 ? config.getAtrSlMultiplier() : 3.0;
        
        this.adxPeriod = config.getAdxPeriod();
        this.adxThreshold = config.getAdxThreshold();
    }

    @Override
    public Signal generateSignal(List<Candle> candles, String pair, Position position) {
        // 데이터 충분 여부 확인 (ADX 계산을 위해 최소 ADX 기간 * 2 필요)
        if (candles.size() < adxPeriod * 2) {
            return Signal.builder().action(Signal.Action.HOLD).build();
        }

        // 1. SuperTrend 계산
        TechnicalIndicators.SuperTrendResult st = TechnicalIndicators.calculateSuperTrend(candles, atrPeriod, multiplier);
        
        // 2. ADX (추세 강도 필터)
        List<Double> adx = TechnicalIndicators.calculateADX(candles, adxPeriod);
        
        int lastIndex = st.getTrend().size() - 1;
        if (lastIndex < 1 || adx.isEmpty()) {
            return Signal.builder().action(Signal.Action.HOLD).build();
        }

        boolean currentTrend = st.getTrend().get(lastIndex);
        boolean prevTrend = st.getTrend().get(lastIndex - 1);
        double currentPrice = candles.get(candles.size() - 1).getClose();
        
        double currentAdx = adx.get(adx.size() - 1);

        // --- 청산 로직 ---
        if (position != null) {
            if (candles.get(candles.size() - 1).getTimestamp() == position.getEntryTimestamp()) {
                return Signal.builder().action(Signal.Action.HOLD).build();
            }

            boolean isLong = "BUY".equals(position.getSide());
            
            if (isLong && !currentTrend) { // Long 포지션인데 하락 추세로 전환되면 청산
                return Signal.builder().action(Signal.Action.EXIT).reason("SuperTrend Bearish Reversal").build();
            } else if (!isLong && currentTrend) { // Short 포지션인데 상승 추세로 전환되면 청산
                return Signal.builder().action(Signal.Action.EXIT).reason("SuperTrend Bullish Reversal").build();
            }
            return Signal.builder().action(Signal.Action.HOLD).build();
        }

        // --- 진입 로직 ---

        // Long 진입: 하락 -> 상승 전환 + ADX 필터
        if (!prevTrend && currentTrend) {
            // 추세 강도 강함 (ADX > 25)
            if (currentAdx > adxThreshold) {
                if (config.isAllowLong()) {
                    return createSignal(currentPrice, Signal.Action.BUY);
                }
            }
        }

        // Short 진입: 상승 -> 하락 전환 + ADX 필터
        if (prevTrend && !currentTrend) {
            // 추세 강도 강함 (ADX > 25)
            if (currentAdx > adxThreshold) {
                if (config.isAllowShort()) {
                    return createSignal(currentPrice, Signal.Action.SHORT);
                }
            }
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
                .reason("SuperTrend Reversal (ADX Filter)")
                .build();
    }
}
