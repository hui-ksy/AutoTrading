package main.strategy;

import lombok.extern.slf4j.Slf4j;
import main.indicator.TechnicalIndicators;
import main.model.Candle;
import main.model.Position;
import main.model.Signal;
import main.model.TradingConfig;

import java.util.List;

@Slf4j
public class EmaStochasticScalpingStrategy implements TradingStrategy {

    private final TradingConfig config;
    private final int kPeriod;
    private final int dPeriod;
    private final int slowing;
    private final double overbought;
    private final double oversold;

    public EmaStochasticScalpingStrategy(TradingConfig config) {
        this.config = config;
        this.kPeriod = config.getStochasticKPeriod();
        this.dPeriod = config.getStochasticDPeriod();
        this.slowing = config.getStochasticSlowing();
        this.overbought = config.getOverboughtThreshold();
        this.oversold = config.getOversoldThreshold();
    }

    @Override
    public Signal generateSignal(List<Candle> candles, String pair, Position position) {
        // 데이터 충분 여부 확인
        if (candles.size() < kPeriod + dPeriod + slowing) {
            return Signal.builder().action(Signal.Action.HOLD).build();
        }

        // 스토캐스틱 계산
        TechnicalIndicators.StochasticResult stochastic = TechnicalIndicators.calculateStochastic(candles, kPeriod, dPeriod, slowing);
        
        if (stochastic.getK().isEmpty() || stochastic.getD().isEmpty()) {
            return Signal.builder().action(Signal.Action.HOLD).build();
        }

        double currentK = stochastic.getK().get(stochastic.getK().size() - 1);
        double currentD = stochastic.getD().get(stochastic.getD().size() - 1);
        double prevK = stochastic.getK().get(stochastic.getK().size() - 2);
        double prevD = stochastic.getD().get(stochastic.getD().size() - 2);
        
        double currentPrice = candles.get(candles.size() - 1).getClose();

        // --- 청산 로직 ---
        if (position != null) {
            if (candles.get(candles.size() - 1).getTimestamp() == position.getEntryTimestamp()) {
                return Signal.builder().action(Signal.Action.HOLD).build();
            }

            boolean isLong = "BUY".equals(position.getSide());
            
            if (isLong) {
                // Long 청산: 과매수 구간(80) 도달 시 익절
                if (currentK > overbought) {
                    return Signal.builder().action(Signal.Action.EXIT).reason("Stochastic Overbought (Take Profit)").build();
                }
            } else {
                // Short 청산: 과매도 구간(20) 도달 시 익절
                if (currentK < oversold) {
                    return Signal.builder().action(Signal.Action.EXIT).reason("Stochastic Oversold (Take Profit)").build();
                }
            }
            return Signal.builder().action(Signal.Action.HOLD).build();
        }

        // --- 진입 로직 ---

        // Long 진입: 과매도 구간(20)에서 골든크로스
        if (currentK < oversold && currentD < oversold && prevK < prevD && currentK > currentD) {
            if (config.isAllowLong()) {
                return createSignal(currentPrice, Signal.Action.BUY);
            }
        }

        // Short 진입: 과매수 구간(80)에서 데드크로스
        if (currentK > overbought && currentD > overbought && prevK > prevD && currentK < currentD) {
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
                .reason("Stochastic Extreme Scalping")
                .build();
    }
}
