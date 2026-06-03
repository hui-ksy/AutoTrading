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
    private final int    trendFilterEma;

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
        this.trendFilterEma = 0;
    }

    /** 파라미터 스윕용 생성자 (trendFilterEma=0, 필터 비활성) */
    public BollingerBandReversionStrategy(int bbPeriod, double bbStdDev,
                                          double rsiOversold, double rsiOverbought,
                                          double atrSlMult, double atrTpMult) {
        this(bbPeriod, bbStdDev, rsiOversold, rsiOverbought, atrSlMult, atrTpMult, 0);
    }

    /** 파라미터 스윕용 생성자 (trendFilterEma 지정) */
    public BollingerBandReversionStrategy(int bbPeriod, double bbStdDev,
                                          double rsiOversold, double rsiOverbought,
                                          double atrSlMult, double atrTpMult,
                                          int trendFilterEma) {
        this.bbPeriod       = bbPeriod;
        this.bbStdDev       = bbStdDev;
        this.rsiPeriod      = 14;
        this.rsiOversold    = rsiOversold;
        this.rsiOverbought  = rsiOverbought;
        this.rsiExitLong    = 55.0;
        this.rsiExitShort   = 45.0;
        this.atrPeriod      = 14;
        this.atrSlMult      = atrSlMult;
        this.atrTpMult      = atrTpMult;
        this.trendFilterEma = trendFilterEma;
    }

    private static String fmt(double v) {
        double abs = Math.abs(v);
        if (abs == 0)     return "0";
        if (abs < 0.0001) return String.format("%.8f", v);
        if (abs < 0.01)   return String.format("%.6f", v);
        if (abs < 1.0)    return String.format("%.4f", v);
        return String.format("%.4f", v);
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
        if (atr <= 0) return hold("ATR 계산 불가 — 진입 차단");

        // ── 포지션 보유 중: SL/TP는 Backtester 처리, 여기서는 HOLD ──────
        if (position != null) {
            return hold(String.format("포지션 유지 | RSI=%.1f | Close=%s | BB[%s~%s]",
                rsi, fmt(close), fmt(bb.getLowerBand()), fmt(bb.getUpperBand())));
        }

        // ── EMA 추세 필터 (trendFilterEma > 0 일 때만 활성) ─────────
        double emaValue = 0;
        if (trendFilterEma > 0) {
            List<Double> emaList = TechnicalIndicators.calculateEMA(candles, trendFilterEma);
            if (emaList.isEmpty()) return hold("EMA 계산 불가");
            emaValue = emaList.get(emaList.size() - 1);
        }

        // ── Long 진입: BB 하단 이탈 + RSI 과매도 ────────────────────
        if (close < bb.getLowerBand() && rsi < rsiOversold) {
            if (trendFilterEma > 0 && close < emaValue)
                return hold("EMA필터: Long 차단 (하락추세) | EMA" + trendFilterEma + "=" + fmt(emaValue));
            double sl = close - atr * atrSlMult;
            double tp = close + atr * atrTpMult;
            return Signal.builder()
                .action(Signal.Action.BUY)
                .entryPrice(close)
                .stopLoss(sl)
                .takeProfit(tp)
                .reason(String.format("BB Long | RSI=%.1f (< %.0f) | Close=%s < BB하단=%s | ATR=%s",
                    rsi, rsiOversold, fmt(close), fmt(bb.getLowerBand()), fmt(atr)))
                .build();
        }

        // ── Short 진입: BB 상단 이탈 + RSI 과매수 ───────────────────
        if (close > bb.getUpperBand() && rsi > rsiOverbought) {
            if (trendFilterEma > 0 && close > emaValue)
                return hold("EMA필터: Short 차단 (상승추세) | EMA" + trendFilterEma + "=" + fmt(emaValue));
            double sl = close + atr * atrSlMult;
            double tp = close - atr * atrTpMult;
            return Signal.builder()
                .action(Signal.Action.SHORT)
                .entryPrice(close)
                .stopLoss(sl)
                .takeProfit(tp)
                .reason(String.format("BB Short | RSI=%.1f (> %.0f) | Close=%s > BB상단=%s | ATR=%s",
                    rsi, rsiOverbought, fmt(close), fmt(bb.getUpperBand()), fmt(atr)))
                .build();
        }

        return hold(String.format("조건 미충족 | RSI=%.1f (기준: <%.0f/>%.0f) | Close=%s | BB[%s~%s] | ATR=%s",
            rsi, rsiOversold, rsiOverbought, fmt(close), fmt(bb.getLowerBand()), fmt(bb.getUpperBand()), fmt(atr)));
    }

    private Signal hold(String reason) {
        return Signal.builder().action(Signal.Action.HOLD).reason(reason).build();
    }

    private Signal exit(String reason) {
        return Signal.builder().action(Signal.Action.EXIT).reason(reason).build();
    }
}