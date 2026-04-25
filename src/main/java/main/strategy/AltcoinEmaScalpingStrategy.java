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
    private final int    rsiPeriod;
    private final double atrSlMult;
    private final double atrTpMult;
    private static final int ATR_PERIOD = 14;

    /** application.conf 기반 실거래/페이퍼 트레이딩용 생성자
     *  emaTrend.emaShortPeriod  → fastEma  (기본 5)
     *  emaTrend.emaMediumPeriod → slowEma  (기본 20)
     *  risk.atrSlMultiplier, risk.atrTpMultiplier
     */
    public AltcoinEmaScalpingStrategy(TradingConfig config) {
        this.fastEmaPeriod = config.getEmaShortPeriod()  > 0 ? config.getEmaShortPeriod()  : 5;
        this.slowEmaPeriod = config.getEmaMediumPeriod() > 0 ? config.getEmaMediumPeriod() : 20;
        this.rsiPeriod     = 14;
        this.atrSlMult     = config.getAtrSlMultiplier() > 0 ? config.getAtrSlMultiplier() : 0.8;
        this.atrTpMult     = config.getAtrTpMultiplier() > 0 ? config.getAtrTpMultiplier() : 2.5;
    }

    /** 파라미터 스윕용 생성자 */
    public AltcoinEmaScalpingStrategy(int fastEmaPeriod, int slowEmaPeriod,
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
        double rsi   = rsiList.get(rsiList.size() - 1);
        double close = candles.get(candles.size() - 1).getClose();
        double atr   = TechnicalIndicators.calculateATR(candles, ATR_PERIOD);

        boolean bullishCross = fastPrev <= slowPrev && fastNow > slowNow;
        boolean bearishCross = fastPrev >= slowPrev && fastNow < slowNow;

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
            return hold(String.format("포지션 유지 | EMA%d=%.6f EMA%d=%.6f | RSI=%.1f",
                fastEmaPeriod, fastNow, slowEmaPeriod, slowNow, rsi));
        }

        // ── 롱 진입: 상향 크로스 + RSI > 50 ──────────────────────────
        if (bullishCross && rsi > 50) {
            double sl = close - atr * atrSlMult;
            double tp = close + atr * atrTpMult;
            return Signal.builder()
                .action(Signal.Action.BUY)
                .entryPrice(close)
                .stopLoss(sl)
                .takeProfit(tp)
                .reason(String.format("EMA Long | EMA%d=%.6f > EMA%d=%.6f | RSI=%.1f | ATR=%.6f",
                    fastEmaPeriod, fastNow, slowEmaPeriod, slowNow, rsi, atr))
                .build();
        }

        // ── 숏 진입: 하향 크로스 + RSI < 50 ──────────────────────────
        if (bearishCross && rsi < 50) {
            double sl = close + atr * atrSlMult;
            double tp = close - atr * atrTpMult;
            return Signal.builder()
                .action(Signal.Action.SHORT)
                .entryPrice(close)
                .stopLoss(sl)
                .takeProfit(tp)
                .reason(String.format("EMA Short | EMA%d=%.6f < EMA%d=%.6f | RSI=%.1f | ATR=%.6f",
                    fastEmaPeriod, fastNow, slowEmaPeriod, slowNow, rsi, atr))
                .build();
        }

        return hold(String.format("조건 미충족 | EMA%d=%.6f EMA%d=%.6f | RSI=%.1f",
            fastEmaPeriod, fastNow, slowEmaPeriod, slowNow, rsi));
    }

    private Signal hold(String reason) {
        return Signal.builder().action(Signal.Action.HOLD).reason(reason).build();
    }
}
