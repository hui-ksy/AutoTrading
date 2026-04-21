package main.strategy;

import main.indicator.TechnicalIndicators;
import main.model.Candle;
import main.model.Position;
import main.model.Signal;

import java.util.List;

/**
 * EMA Cross Momentum Scalping Strategy
 *
 * 핵심 아이디어:
 * - 빠른 EMA가 느린 EMA 위로 교차 + RSI > 50 → Long (상승 모멘텀 확인)
 * - 빠른 EMA가 느린 EMA 아래로 교차 + RSI < 50 → Short (하락 모멘텀 확인)
 * - 5m 타임프레임 기준 시간당 1-2건 목표
 * - 타이트한 ATR 기반 SL/TP
 *
 * 왜 BB 전략보다 빈번한가:
 * - BB 2σ 이탈은 통계적 희귀 이벤트 (~4.5%) → 드문 신호
 * - EMA 교차는 추세 전환마다 발생 → 훨씬 높은 신호 빈도
 */
public class MomentumScalpingStrategy implements TradingStrategy {

    private final int    fastEmaPeriod;
    private final int    slowEmaPeriod;
    private final int    rsiPeriod;
    private final double atrSlMult;
    private final double atrTpMult;
    private static final int ATR_PERIOD = 14;

    /** 파라미터 스윕용 생성자 */
    public MomentumScalpingStrategy(int fastEmaPeriod, int slowEmaPeriod,
                                     double atrSlMult, double atrTpMult) {
        this.fastEmaPeriod = fastEmaPeriod;
        this.slowEmaPeriod = slowEmaPeriod;
        this.rsiPeriod     = 14;
        this.atrSlMult     = atrSlMult;
        this.atrTpMult     = atrTpMult;
    }

    @Override
    public Signal generateSignal(List<Candle> candles, String pair, Position position) {
        int minNeeded = slowEmaPeriod + rsiPeriod + 5;
        if (candles.size() < minNeeded) return hold("데이터 부족");

        List<Double> fastEmaList = TechnicalIndicators.calculateEMA(candles, fastEmaPeriod);
        List<Double> slowEmaList = TechnicalIndicators.calculateEMA(candles, slowEmaPeriod);

        if (fastEmaList.size() < 2 || slowEmaList.size() < 2) return hold("EMA 계산 불가");

        double fastNow  = fastEmaList.get(fastEmaList.size() - 1);
        double fastPrev = fastEmaList.get(fastEmaList.size() - 2);
        double slowNow  = slowEmaList.get(slowEmaList.size() - 1);
        double slowPrev = slowEmaList.get(slowEmaList.size() - 2);

        List<Double> rsiList = TechnicalIndicators.calculateRSI(candles, rsiPeriod);
        if (rsiList.isEmpty()) return hold("RSI 계산 불가");
        double rsi = rsiList.get(rsiList.size() - 1);

        double close = candles.get(candles.size() - 1).getClose();
        double atr   = TechnicalIndicators.calculateATR(candles, ATR_PERIOD);

        // 포지션 보유 중: SL/TP는 Backtester 처리
        if (position != null) {
            return hold("포지션 유지 (SL/TP 대기)");
        }

        // Long: 빠른 EMA가 느린 EMA 위로 교차 + RSI > 50 (모멘텀 확인)
        boolean bullishCross = fastPrev <= slowPrev && fastNow > slowNow;
        if (bullishCross && rsi > 50) {
            double sl = close - atr * atrSlMult;
            double tp = close + atr * atrTpMult;
            return Signal.builder()
                .action(Signal.Action.BUY)
                .entryPrice(close)
                .stopLoss(sl)
                .takeProfit(tp)
                .reason(String.format("EMA Cross Long: EMA%d=%.1f EMA%d=%.1f RSI=%.1f",
                    fastEmaPeriod, fastNow, slowEmaPeriod, slowNow, rsi))
                .build();
        }

        // Short: 빠른 EMA가 느린 EMA 아래로 교차 + RSI < 50 (모멘텀 확인)
        boolean bearishCross = fastPrev >= slowPrev && fastNow < slowNow;
        if (bearishCross && rsi < 50) {
            double sl = close + atr * atrSlMult;
            double tp = close - atr * atrTpMult;
            return Signal.builder()
                .action(Signal.Action.SHORT)
                .entryPrice(close)
                .stopLoss(sl)
                .takeProfit(tp)
                .reason(String.format("EMA Cross Short: EMA%d=%.1f EMA%d=%.1f RSI=%.1f",
                    fastEmaPeriod, fastNow, slowEmaPeriod, slowNow, rsi))
                .build();
        }

        return hold("조건 미충족");
    }

    private Signal hold(String reason) {
        return Signal.builder().action(Signal.Action.HOLD).reason(reason).build();
    }
}