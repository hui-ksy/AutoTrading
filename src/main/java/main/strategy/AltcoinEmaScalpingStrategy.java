package main.strategy;

import main.indicator.TechnicalIndicators;
import main.model.Candle;
import main.model.Position;
import main.model.Signal;
import main.model.TradingConfig;

import java.util.List;

public class AltcoinEmaScalpingStrategy implements TradingStrategy {

    private final int    fastEmaPeriod;
    private final int    slowEmaPeriod;
    private final int    trendEmaPeriod;  // 추세 필터 EMA (기본 200)
    private final int    rsiPeriod;
    private final double rsiBuyMin;       // BUY: RSI 최솟값 (기본 45)
    private final double rsiBuyMax;       // BUY: RSI 최댓값 (기본 70, 과매수 방지)
    private final double rsiSellMin;      // SHORT: RSI 최솟값 (기본 30, 과매도 방지)
    private final double rsiSellMax;      // SHORT: RSI 최댓값 (기본 55)
    private final double atrSlMult;
    private final double atrTpMult;
    private static final int ATR_PERIOD = 14;

    public AltcoinEmaScalpingStrategy(TradingConfig config) {
        this.fastEmaPeriod  = config.getEmaShortPeriod()  > 0 ? config.getEmaShortPeriod()  : 5;
        this.slowEmaPeriod  = config.getEmaMediumPeriod() > 0 ? config.getEmaMediumPeriod() : 20;
        this.trendEmaPeriod = 200;
        this.rsiPeriod      = 14;
        this.rsiBuyMin      = 45.0;
        this.rsiBuyMax      = 70.0;
        this.rsiSellMin     = 30.0;
        this.rsiSellMax     = 55.0;
        this.atrSlMult      = config.getAtrSlMultiplier() > 0 ? config.getAtrSlMultiplier() : 0.8;
        this.atrTpMult      = config.getAtrTpMultiplier() > 0 ? config.getAtrTpMultiplier() : 2.5;
    }

    /** 파라미터 스윕용 생성자 (추세 필터 포함) */
    public AltcoinEmaScalpingStrategy(int fastEmaPeriod, int slowEmaPeriod,
                                      double atrSlMult, double atrTpMult) {
        this.fastEmaPeriod  = fastEmaPeriod;
        this.slowEmaPeriod  = slowEmaPeriod;
        this.trendEmaPeriod = 200;
        this.rsiPeriod      = 14;
        this.rsiBuyMin      = 45.0;
        this.rsiBuyMax      = 70.0;
        this.rsiSellMin     = 30.0;
        this.rsiSellMax     = 55.0;
        this.atrSlMult      = atrSlMult;
        this.atrTpMult      = atrTpMult;
    }

    @Override
    public Signal generateSignal(List<Candle> candles, String pair, Position position) {
        int minNeeded = Math.max(trendEmaPeriod, slowEmaPeriod + rsiPeriod) + 5;
        if (candles.size() < minNeeded) return hold("데이터 부족");

        List<Double> fastEmaList  = TechnicalIndicators.calculateEMA(candles, fastEmaPeriod);
        List<Double> slowEmaList  = TechnicalIndicators.calculateEMA(candles, slowEmaPeriod);
        List<Double> trendEmaList = TechnicalIndicators.calculateEMA(candles, trendEmaPeriod);
        if (fastEmaList.size() < 2 || slowEmaList.size() < 2 || trendEmaList.isEmpty())
            return hold("EMA 계산 불가");

        double fastNow  = fastEmaList.get(fastEmaList.size() - 1);
        double fastPrev = fastEmaList.get(fastEmaList.size() - 2);
        double slowNow  = slowEmaList.get(slowEmaList.size() - 1);
        double slowPrev = slowEmaList.get(slowEmaList.size() - 2);
        double trendEma = trendEmaList.get(trendEmaList.size() - 1);

        List<Double> rsiList = TechnicalIndicators.calculateRSI(candles, rsiPeriod);
        if (rsiList.isEmpty()) return hold("RSI 계산 불가");
        double rsi   = rsiList.get(rsiList.size() - 1);
        double close = candles.get(candles.size() - 1).getClose();
        double atr   = TechnicalIndicators.calculateATR(candles, ATR_PERIOD);

        boolean bullishCross = fastPrev <= slowPrev && fastNow > slowNow;
        boolean bearishCross = fastPrev >= slowPrev && fastNow < slowNow;
        boolean uptrend      = close > trendEma;
        boolean downtrend    = close < trendEma;

        // ── 포지션 보유 중: 역크로스 시 EXIT ─────────────────────────
        if (position != null) {
            boolean isLong = "BUY".equals(position.getSide());
            if (isLong && bearishCross) {
                return Signal.builder()
                    .action(Signal.Action.EXIT)
                    .reason(String.format("EMA 역크로스 → Long 청산 | EMA%d=%.6f < EMA%d=%.6f | RSI=%.1f",
                        fastEmaPeriod, fastNow, slowEmaPeriod, slowNow, rsi))
                    .build();
            }
            if (!isLong && bullishCross) {
                return Signal.builder()
                    .action(Signal.Action.EXIT)
                    .reason(String.format("EMA 역크로스 → Short 청산 | EMA%d=%.6f > EMA%d=%.6f | RSI=%.1f",
                        fastEmaPeriod, fastNow, slowEmaPeriod, slowNow, rsi))
                    .build();
            }
            return hold(String.format("포지션 유지 | EMA%d=%.6f EMA%d=%.6f | RSI=%.1f | EMA%d=%.4f",
                fastEmaPeriod, fastNow, slowEmaPeriod, slowNow, rsi, trendEmaPeriod, trendEma));
        }

        // ── 롱 진입: 상향 크로스 + RSI 범위 + 상승 추세 ─────────────
        if (bullishCross && rsi >= rsiBuyMin && rsi <= rsiBuyMax && uptrend) {
            double sl = close - atr * atrSlMult;
            double tp = close + atr * atrTpMult;
            return Signal.builder()
                .action(Signal.Action.BUY)
                .entryPrice(close)
                .stopLoss(sl)
                .takeProfit(tp)
                .reason(String.format("EMA Long | EMA%d>EMA%d | RSI=%.1f(%.0f~%.0f) | 추세EMA%d=%.4f(↑) | ATR=%.6f",
                    fastEmaPeriod, slowEmaPeriod, rsi, rsiBuyMin, rsiBuyMax, trendEmaPeriod, trendEma, atr))
                .build();
        }

        // ── 숏 진입: 하향 크로스 + RSI 범위 + 하락 추세 ─────────────
        if (bearishCross && rsi >= rsiSellMin && rsi <= rsiSellMax && downtrend) {
            double sl = close + atr * atrSlMult;
            double tp = close - atr * atrTpMult;
            return Signal.builder()
                .action(Signal.Action.SHORT)
                .entryPrice(close)
                .stopLoss(sl)
                .takeProfit(tp)
                .reason(String.format("EMA Short | EMA%d<EMA%d | RSI=%.1f(%.0f~%.0f) | 추세EMA%d=%.4f(↓) | ATR=%.6f",
                    fastEmaPeriod, slowEmaPeriod, rsi, rsiSellMin, rsiSellMax, trendEmaPeriod, trendEma, atr))
                .build();
        }

        return hold(String.format("조건 미충족 | EMA%d=%.6f EMA%d=%.6f | RSI=%.1f | EMA%d=%s추세",
            fastEmaPeriod, fastNow, slowEmaPeriod, slowNow, rsi, trendEmaPeriod, uptrend ? "상승" : "하락"));
    }

    private Signal hold(String reason) {
        return Signal.builder().action(Signal.Action.HOLD).reason(reason).build();
    }
}