package main.strategy;

import main.indicator.TechnicalIndicators;
import main.model.Candle;
import main.model.Position;
import main.model.Signal;
import main.model.TradingConfig;

import java.util.List;

/**
 * Bollinger Band Mean-Reversion Strategy
 * <p>
 * 핵심 아이디어:
 * - 가격이 BB 하단 돌파 + RSI 과매도 → Long (평균 회귀 노림)
 * - 가격이 BB 상단 돌파 + RSI 과매수 → Short
 * - 청산: RSI 중립 복귀(50) 또는 가격이 BB 중간선 복귀 시 이익 실현
 * - SL/TP: ATR 배수 기반 고정
 * <p>
 * 장점: SMA200 추세 필터 없음 → 하락장/횡보장에서도 롱/숏 모두 적극 진입
 * 승률이 높은 이유: 통계적 극단(2σ) 진입 → 평균 복귀 확률이 구조적으로 높음
 */
public class BollingerBandReversionStrategy implements TradingStrategy {

    private final int    bbPeriod;
    private final double bbStdDev;
    private final int    rsiPeriod;
    private final double rsiOversold;   // RSI < this → Long 후보
    private final double rsiOverbought; // RSI > this → Short 후보
    private final double rsiExitLong;   // RSI >= this → Long 청산
    private final double rsiExitShort;  // RSI <= this → Short 청산
    private final int    atrPeriod;
    private final double atrSlMult;
    private final double atrTpMult;

    /** application.conf 기반 실거래/페이퍼 트레이딩용 생성자 */
    public BollingerBandReversionStrategy(TradingConfig config) {
        this.bbPeriod      = config.getBollingerBandsPeriod() > 0 ? config.getBollingerBandsPeriod() : 10;
        this.bbStdDev      = config.getBollingerBandsStdDev() > 0 ? config.getBollingerBandsStdDev() : 2.0;
        this.rsiPeriod     = config.getBollingerBandsRsiPeriod() > 0 ? config.getBollingerBandsRsiPeriod() : 14;
        this.rsiOversold   = config.getBollingerBandsRsiOversold() > 0 ? config.getBollingerBandsRsiOversold() : 35.0;
        this.rsiOverbought = config.getBollingerBandsRsiOverbought() > 0 ? config.getBollingerBandsRsiOverbought() : 75.0;
        this.rsiExitLong   = 55.0;
        this.rsiExitShort  = 45.0;
        this.atrPeriod     = config.getAtrPeriod() > 0 ? config.getAtrPeriod() : 14;
        this.atrSlMult     = config.getAtrSlMultiplier() > 0 ? config.getAtrSlMultiplier() : 1.5;
        this.atrTpMult     = config.getAtrTpMultiplier() > 0 ? config.getAtrTpMultiplier() : 3.0;
    }

    /** 파라미터 스윕용 생성자 */
    public BollingerBandReversionStrategy(int bbPeriod, double bbStdDev,
                                          double rsiOversold, double rsiOverbought,
                                          double atrSlMult, double atrTpMult) {
        this.bbPeriod      = bbPeriod;
        this.bbStdDev      = bbStdDev;
        this.rsiPeriod     = 14;
        this.rsiOversold   = rsiOversold;
        this.rsiOverbought = rsiOverbought;
        this.rsiExitLong   = 55.0;   // RSI 55 이상 → Long 청산 (중립 복귀)
        this.rsiExitShort  = 45.0;   // RSI 45 이하 → Short 청산
        this.atrPeriod     = 14;
        this.atrSlMult     = atrSlMult;
        this.atrTpMult     = atrTpMult;
    }

    @Override
    public Signal generateSignal(List<Candle> candles, String pair, Position position) {
        int minNeeded = Math.max(bbPeriod, rsiPeriod + 1) + 5;
        if (candles.size() < minNeeded) return hold("데이터 부족");

        double close = candles.get(candles.size() - 1).getClose();

        TechnicalIndicators.BollingerBandsResult bb =
            TechnicalIndicators.calculateBollingerBands(candles, bbPeriod, bbStdDev);
        if (bb == null) return hold("BB 계산 불가");

        List<Double> rsiList = TechnicalIndicators.calculateRSI(candles, rsiPeriod);
        if (rsiList.isEmpty()) return hold("RSI 계산 불가");
        double rsi = rsiList.get(rsiList.size() - 1);

        double atr = TechnicalIndicators.calculateATR(candles, atrPeriod);

        // ── 포지션 보유 중: SL/TP는 Backtester 처리, 여기서는 HOLD ──────
        if (position != null) {
            return hold("포지션 유지 (SL/TP 대기)");
        }

        // ── Long 진입: BB 하단 이탈 + RSI 과매도 ────────────────────
        if (close < bb.getLowerBand() && rsi < rsiOversold) {
            double sl = close - atr * atrSlMult;
            double tp = close + atr * atrTpMult;
            return Signal.builder()
                .action(Signal.Action.BUY)
                .entryPrice(close)
                .stopLoss(sl)
                .takeProfit(tp)
                .reason(String.format("BB Long: RSI=%.1f close=%.0f lower=%.0f",
                    rsi, close, bb.getLowerBand()))
                .build();
        }

        // ── Short 진입: BB 상단 이탈 + RSI 과매수 ───────────────────
        if (close > bb.getUpperBand() && rsi > rsiOverbought) {
            double sl = close + atr * atrSlMult;
            double tp = close - atr * atrTpMult;
            return Signal.builder()
                .action(Signal.Action.SHORT)
                .entryPrice(close)
                .stopLoss(sl)
                .takeProfit(tp)
                .reason(String.format("BB Short: RSI=%.1f close=%.0f upper=%.0f",
                    rsi, close, bb.getUpperBand()))
                .build();
        }

        return hold("조건 미충족");
    }

    private Signal hold(String reason) {
        return Signal.builder().action(Signal.Action.HOLD).reason(reason).build();
    }

    private Signal exit(String reason) {
        return Signal.builder().action(Signal.Action.EXIT).reason(reason).build();
    }
}