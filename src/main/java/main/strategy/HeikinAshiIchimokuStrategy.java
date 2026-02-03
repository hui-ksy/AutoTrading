package main.strategy;

import lombok.extern.slf4j.Slf4j;
import main.indicator.TechnicalIndicators;
import main.model.Candle;
import main.model.Position;
import main.model.Signal;
import main.model.TradingConfig;

import java.util.List;

@Slf4j
public class HeikinAshiIchimokuStrategy implements TradingStrategy {

    private final TradingConfig config;
    private final int emaShort;
    private final int emaMedium;
    private final int emaLong;
    private final int adxPeriod;
    private final double adxThreshold;
    private final int stochK;
    private final int stochD;
    private final int stochSmooth;
    
    // 볼린저 밴드 설정 (눌림목 필터용)
    private final int bbPeriod;
    private final double bbStdDev;

    public HeikinAshiIchimokuStrategy(TradingConfig config) {
        this.config = config;
        this.emaShort = config.getEmaShortPeriod();
        this.emaMedium = config.getEmaMediumPeriod();
        this.emaLong = config.getEmaLongPeriod();
        this.adxPeriod = config.getAdxPeriod();
        this.adxThreshold = config.getAdxThreshold();
        this.stochK = config.getStochK();
        this.stochD = config.getStochD();
        this.stochSmooth = config.getStochSmooth();
        
        // 볼린저 밴드 설정 로드 (기존 설정 재활용)
        this.bbPeriod = config.getBollingerBandsPeriod();
        this.bbStdDev = config.getBollingerBandsStdDev();
    }

    @Override
    public Signal generateSignal(List<Candle> candles, String pair, Position position) {
        // 데이터 충분 여부 확인
        if (candles.size() < Math.max(emaLong, bbPeriod)) {
            return Signal.builder().action(Signal.Action.HOLD).build();
        }

        // 1. 하이킨 아시 캔들 변환
        List<Candle> haCandles = TechnicalIndicators.calculateHeikinAshi(candles);
        
        // 2. EMA 계산
        List<Double> emaS = TechnicalIndicators.calculateEMA(candles, emaShort);
        List<Double> emaM = TechnicalIndicators.calculateEMA(candles, emaMedium);
        List<Double> emaL = TechnicalIndicators.calculateEMA(candles, emaLong);
        
        // 3. ADX 계산
        List<Double> adx = TechnicalIndicators.calculateADX(candles, adxPeriod);
        
        // 4. 스토캐스틱 계산
        TechnicalIndicators.StochasticResult stochastic = TechnicalIndicators.calculateStochastic(candles, stochK, stochD, stochSmooth);
        
        // 5. 볼린저 밴드 계산 (눌림목 필터)
        TechnicalIndicators.BollingerBandsResult bb = TechnicalIndicators.calculateBollingerBands(candles, bbPeriod, bbStdDev);

        int lastIndex = candles.size() - 1;
        
        // 데이터 유효성 검사
        if (emaS.isEmpty() || emaM.isEmpty() || emaL.isEmpty() || adx.isEmpty() || 
            stochastic.getK().isEmpty() || stochastic.getD().isEmpty() || bb == null) {
            return Signal.builder().action(Signal.Action.HOLD).build();
        }

        double currentEmaS = emaS.get(emaS.size() - 1);
        double currentEmaM = emaM.get(emaM.size() - 1);
        double currentEmaL = emaL.get(emaL.size() - 1);
        double currentAdx = adx.get(adx.size() - 1);
        
        Candle currentHa = haCandles.get(lastIndex);
        
        double currentK = stochastic.getK().get(stochastic.getK().size() - 1);
        double currentD = stochastic.getD().get(stochastic.getD().size() - 1);
        double prevK = stochastic.getK().get(stochastic.getK().size() - 2);
        double prevD = stochastic.getD().get(stochastic.getD().size() - 2);
        
        double currentPrice = candles.get(lastIndex).getClose();

        // --- 청산 로직 (포지션 보유 시) ---
        if (position != null) {
            if (candles.get(lastIndex).getTimestamp() == position.getEntryTimestamp()) {
                return Signal.builder().action(Signal.Action.HOLD).build();
            }

            boolean isLong = "BUY".equals(position.getSide());
            
            if (isLong) {
                if (currentHa.getClose() < currentHa.getOpen()) {
                    return Signal.builder().action(Signal.Action.EXIT).reason("Heikin Ashi Bearish Reversal").build();
                }
            } else {
                if (currentHa.getClose() > currentHa.getOpen()) {
                    return Signal.builder().action(Signal.Action.EXIT).reason("Heikin Ashi Bullish Reversal").build();
                }
            }
            return Signal.builder().action(Signal.Action.HOLD).build();
        }

        // --- 진입 로직 (포지션 없을 시) ---

        // Long 조건
        // 1. EMA 정배열
        // 2. ADX > Threshold
        // 3. 하이킨 아시 양봉
        // 4. 스토캐스틱 골든크로스
        // 5. [추가] 현재가가 볼린저 밴드 중앙선보다 낮아야 함 (눌림목)
        boolean isLongSignal = currentEmaS > currentEmaM && currentEmaM > currentEmaL &&
                               currentAdx > adxThreshold &&
                               currentHa.getClose() > currentHa.getOpen() &&
                               prevK < prevD && currentK > currentD &&
                               currentPrice < bb.getMiddleBand();

        if (config.isAllowLong() && isLongSignal) {
            return createSignal(currentPrice, Signal.Action.BUY);
        }

        // Short 조건
        // 1. EMA 역배열
        // 2. ADX > Threshold
        // 3. 하이킨 아시 음봉
        // 4. 스토캐스틱 데드크로스
        // 5. [추가] 현재가가 볼린저 밴드 중앙선보다 높아야 함 (눌림목)
        boolean isShortSignal = currentEmaS < currentEmaM && currentEmaM < currentEmaL &&
                                currentAdx > adxThreshold &&
                                currentHa.getClose() < currentHa.getOpen() &&
                                prevK > prevD && currentK < currentD &&
                                currentPrice > bb.getMiddleBand();

        if (config.isAllowShort() && isShortSignal) {
            return createSignal(currentPrice, Signal.Action.SHORT);
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
                .reason("EMA Trend + ADX + Stochastic + BB Filter")
                .build();
    }
}
