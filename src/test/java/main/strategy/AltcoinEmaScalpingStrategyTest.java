package main.strategy;

import main.model.Candle;
import main.model.Position;
import main.model.Signal;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AltcoinEmaScalpingStrategyTest {

    // fast=5, slow=20, slMult=0.8, tpMult=2.5 (기본 파라미터)
    private final AltcoinEmaScalpingStrategy strategy =
        new AltcoinEmaScalpingStrategy(5, 20, 0.8, 2.5);

    /**
     * flatCount개의 flatPrice 캔들, 이후 extras 순서대로 추가.
     * high = price×1.001, low = price×0.999 (ATR > 0 보장)
     */
    private static List<Candle> flatThen(int flatCount, double flatPrice, double... extras) {
        List<Candle> list = new ArrayList<>(flatCount + extras.length);
        for (int i = 0; i < flatCount; i++) list.add(candle(flatPrice));
        for (double c : extras) list.add(candle(c));
        return list;
    }

    private static Candle candle(double close) {
        Candle c = new Candle();
        c.setClose(close);
        c.setOpen(close);
        c.setHigh(close * 1.001);
        c.setLow(close * 0.999);
        c.setTimestamp(System.currentTimeMillis());
        return c;
    }

    // ── 진입 신호 ─────────────────────────────────────────────────────────────

    @Test
    void bullishCross_rsiAbove50_returnsBuy() {
        // 50개 flat → EMA5=EMA20=1.0, 마지막 봉 1.10 급등 → EMA5 > EMA20 크로스 + RSI=100
        List<Candle> data = flatThen(50, 1.0, 1.10);
        Signal sig = strategy.generateSignal(data, "XRPUSDT", null);

        assertEquals(Signal.Action.BUY, sig.getAction(),
            "상향 EMA 크로스 + RSI>50 → Long 진입");
        assertEquals(1.10, sig.getEntryPrice(), 0.0001);
        assertTrue(sig.getStopLoss() < sig.getEntryPrice(), "SL은 진입가 아래여야 함");
        assertTrue(sig.getTakeProfit() > sig.getEntryPrice(), "TP는 진입가 위여야 함");
    }

    @Test
    void bearishCross_rsiBelow50_returnsShort() {
        // 50개 flat → EMA5=EMA20=1.0, 마지막 봉 0.90 급락 → EMA5 < EMA20 크로스 + RSI=0
        List<Candle> data = flatThen(50, 1.0, 0.90);
        Signal sig = strategy.generateSignal(data, "XRPUSDT", null);

        assertEquals(Signal.Action.SHORT, sig.getAction(),
            "하향 EMA 크로스 + RSI<50 → Short 진입");
        assertEquals(0.90, sig.getEntryPrice(), 0.0001);
        assertTrue(sig.getStopLoss() > sig.getEntryPrice(), "SL은 진입가 위여야 함");
        assertTrue(sig.getTakeProfit() < sig.getEntryPrice(), "TP는 진입가 아래여야 함");
    }

    @Test
    void noEMACross_returnsHold() {
        // 51개 flat → EMA5=EMA20 내내 동일, 크로스 없음 → HOLD
        List<Candle> data = flatThen(51, 1.0);
        Signal sig = strategy.generateSignal(data, "XRPUSDT", null);

        assertEquals(Signal.Action.HOLD, sig.getAction(),
            "EMA 크로스 없으면 HOLD");
    }

    // ── 역크로스 EXIT ─────────────────────────────────────────────────────────

    @Test
    void longPosition_bearishCross_returnsExit() {
        // Long 보유 중 하향 EMA 역크로스 → EXIT
        List<Candle> data = flatThen(50, 1.0, 0.90);
        Position pos = new Position();
        pos.setSide("BUY");
        pos.setEntryPrice(1.0);

        Signal sig = strategy.generateSignal(data, "XRPUSDT", pos);

        assertEquals(Signal.Action.EXIT, sig.getAction(),
            "Long 보유 중 하향 크로스 → EXIT");
        assertTrue(sig.getReason().contains("Long 청산"), "이유에 Long 청산 포함");
    }

    @Test
    void shortPosition_bullishCross_returnsExit() {
        // Short 보유 중 상향 EMA 역크로스 → EXIT
        List<Candle> data = flatThen(50, 1.0, 1.10);
        Position pos = new Position();
        pos.setSide("SELL");
        pos.setEntryPrice(1.0);

        Signal sig = strategy.generateSignal(data, "XRPUSDT", pos);

        assertEquals(Signal.Action.EXIT, sig.getAction(),
            "Short 보유 중 상향 크로스 → EXIT");
        assertTrue(sig.getReason().contains("Short 청산"), "이유에 Short 청산 포함");
    }

    // ── 포지션 유지 (역크로스 없음) ───────────────────────────────────────────

    @Test
    void longPosition_noCross_returnsHold() {
        // Long 보유 중 크로스 없음 → HOLD (SL/TP는 Backtester가 처리)
        List<Candle> data = flatThen(51, 1.0);
        Position pos = new Position();
        pos.setSide("BUY");
        pos.setEntryPrice(1.0);

        Signal sig = strategy.generateSignal(data, "XRPUSDT", pos);

        assertEquals(Signal.Action.HOLD, sig.getAction(),
            "Long 보유 중 역크로스 없으면 HOLD");
    }

    @Test
    void shortPosition_noCross_returnsHold() {
        List<Candle> data = flatThen(51, 1.0);
        Position pos = new Position();
        pos.setSide("SELL");
        pos.setEntryPrice(1.0);

        Signal sig = strategy.generateSignal(data, "XRPUSDT", pos);

        assertEquals(Signal.Action.HOLD, sig.getAction(),
            "Short 보유 중 역크로스 없으면 HOLD");
    }

    // ── 엣지 케이스 ──────────────────────────────────────────────────────────

    @Test
    void insufficientData_returnsHold() {
        // minNeeded = 20 + 14 + 5 = 39, 10개만 제공 → HOLD + 데이터 부족 메시지
        List<Candle> data = flatThen(10, 1.0);
        Signal sig = strategy.generateSignal(data, "XRPUSDT", null);

        assertEquals(Signal.Action.HOLD, sig.getAction());
        assertTrue(sig.getReason().contains("데이터 부족"));
    }

    @Test
    void slAndTp_areDerivedFromAtr() {
        // SL = close - ATR*0.8, TP = close + ATR*2.5 을 검증
        // spread=0.1% → ATR ≈ 0.002 (1.0 기준)
        List<Candle> data = flatThen(50, 1.0, 1.10);
        Signal sig = strategy.generateSignal(data, "XRPUSDT", null);

        assertEquals(Signal.Action.BUY, sig.getAction());
        double entryPrice = sig.getEntryPrice();
        double slGap = entryPrice - sig.getStopLoss();
        double tpGap = sig.getTakeProfit() - entryPrice;
        // TP gap / SL gap ≈ 2.5 / 0.8 = 3.125 (ATR 배수 비율 검증)
        assertEquals(2.5 / 0.8, tpGap / slGap, 0.001,
            "TP gap : SL gap 비율은 atrTpMult:atrSlMult (2.5:0.8) 이어야 함");
    }
}
