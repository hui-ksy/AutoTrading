package main.strategy;

import lombok.extern.slf4j.Slf4j;
import main.indicator.TechnicalIndicators;
import main.model.Candle;
import main.model.Position;
import main.model.Signal;
import main.model.TradingConfig;

import java.util.List;

@Slf4j
public class DowntrendRsiStrategy implements TradingStrategy {

    private final TradingConfig config;
    private final int rsiPeriod;
    private final double rsiThreshold;
    private final int trendEmaPeriod; // [추가] 추세 필터용 EMA 기간

    public DowntrendRsiStrategy(TradingConfig config) {
        this.config = config;
        this.rsiPeriod = config.getDowntrendRsiRsiPeriod() > 0 ? config.getDowntrendRsiRsiPeriod() : 14;
        this.rsiThreshold = config.getDowntrendRsiThreshold() > 0 ? config.getDowntrendRsiThreshold() : 30.0;
        this.trendEmaPeriod = config.getDowntrendRsiTrendEmaPeriod() > 0 ? config.getDowntrendRsiTrendEmaPeriod() : 200;
    }

    @Override
    public Signal generateSignal(List<Candle> candles, String pair, Position position) {
        // 데이터 충분 여부 확인 (EMA 계산을 위해 trendEmaPeriod 필요)
        if (candles.size() < trendEmaPeriod) {
            return Signal.builder().action(Signal.Action.HOLD).build();
        }

        // RSI 계산
        List<Double> rsiList = TechnicalIndicators.calculateRSI(candles, rsiPeriod);
        
        // EMA 계산 (추세 필터)
        List<Double> emaList = TechnicalIndicators.calculateEMA(candles, trendEmaPeriod);
        
        if (rsiList.isEmpty() || emaList.isEmpty()) return Signal.builder().action(Signal.Action.HOLD).build();

        double currentRsi = rsiList.get(rsiList.size() - 1);
        double currentEma = emaList.get(emaList.size() - 1);
        double currentPrice = candles.get(candles.size() - 1).getClose();

        // --- 청산 로직 ---
        if (position != null) {
            if (candles.get(candles.size() - 1).getTimestamp() == position.getEntryTimestamp()) {
                return Signal.builder().action(Signal.Action.HOLD).build();
            }

            boolean isLong = "BUY".equals(position.getSide());
            
            if (isLong) {
                // Long 청산: RSI가 50 이상으로 회귀하면 익절
                if (currentRsi >= 50) {
                    return Signal.builder().action(Signal.Action.EXIT).reason("RSI Mean Reversion (>= 50)").build();
                }
            } else {
                // Short 청산: RSI가 50 이하로 회귀하면 익절
                if (currentRsi <= 50) {
                    return Signal.builder().action(Signal.Action.EXIT).reason("RSI Mean Reversion (<= 50)").build();
                }
            }
            return Signal.builder().action(Signal.Action.HOLD).build();
        }

        // --- 진입 로직 ---

        // Long 진입: 과매도 (RSI < 30) AND 상승 추세 (Price > 200 EMA)
        if (currentRsi < rsiThreshold && currentPrice > currentEma) {
            if (config.isAllowLong()) {
                return createSignal(currentPrice, Signal.Action.BUY);
            }
        }

        // Short 진입: 과매수 (RSI > 70) AND 하락 추세 (Price < 200 EMA)
        if (currentRsi > (100 - rsiThreshold) && currentPrice < currentEma) {
            if (config.isAllowShort()) {
                return createSignal(currentPrice, Signal.Action.SHORT);
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
                .reason("RSI Mean Reversion + EMA Filter")
                .build();
    }
}
