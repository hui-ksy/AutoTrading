package main.strategy;

import lombok.extern.slf4j.Slf4j;
import main.indicator.TechnicalIndicators;
import main.model.Candle;
import main.model.Position;
import main.model.Signal;
import main.model.TradingConfig;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Dynamic Exit Scalping Strategy (E0V1E_53_Sharpe 류)
 * <p>
 * 핵심 아이디어:
 * - RSI 과매도/과매수 + SMA 추세 필터로 진입
 * - CTI(Correlation Trend Index) 로 추세 강도 확인 (Pearson 상관 기반 간이 구현)
 * - ATR 기반 동적 손절/익절 (R:R 1.5)
 * - EWO(Elliott Wave Oscillator = EMA5 - EMA35) 추가 필터
 * - RSI 기반 동적 익절 (RSI가 과매수/과매도 반전 시 조기 청산)
 * <p>
 * 설정값 (application.conf):
 * <pre>
 * dynamicExitScalping {
 *   rsiPeriod       = 14
 *   rsiBuy          = 30      # RSI < rsiBuy → Long 후보
 *   rsiSell         = 70      # RSI > rsiSell → Short 후보
 *   rsiExitLong     = 55      # Long 포지션 RSI 익절 선
 *   rsiExitShort    = 45      # Short 포지션 RSI 익절 선
 *   smaPeriod       = 200     # SMA 추세 필터
 *   ctiPeriod       = 20      # CTI 룩백
 *   ctiThreshold    = -0.5    # CTI < threshold → 롱 필터 통과
 *   ewoPeriod1      = 5
 *   ewoPeriod2      = 35
 *   atrPeriod       = 14
 *   atrSlMult       = 1.5
 *   atrTpMult       = 2.5
 * }
 * </pre>
 */
@Slf4j
public class DynamicExitScalpingStrategy implements TradingStrategy {

    // ── 기본값 (application.conf 미설정 시 사용) ──────────────────────
    private static final int    DEFAULT_RSI_PERIOD     = 14;
    private static final double DEFAULT_RSI_BUY        = 30.0;
    private static final double DEFAULT_RSI_SELL       = 70.0;
    private static final double DEFAULT_RSI_EXIT_LONG  = 55.0;
    private static final double DEFAULT_RSI_EXIT_SHORT = 45.0;
    private static final int    DEFAULT_SMA_PERIOD     = 200;
    private static final int    DEFAULT_CTI_PERIOD     = 20;
    private static final double DEFAULT_CTI_THRESHOLD  = -0.5;   // Long: CTI < threshold
    private static final int    DEFAULT_EWO_PERIOD1    = 5;
    private static final int    DEFAULT_EWO_PERIOD2    = 35;
    private static final int    DEFAULT_ATR_PERIOD     = 14;
    private static final double DEFAULT_ATR_SL_MULT    = 1.5;
    private static final double DEFAULT_ATR_TP_MULT    = 2.5;

    // ── 설정값 (생성자에서 로드) ──────────────────────────────────────
    private final int    rsiPeriod;
    private final double rsiBuy;
    private final double rsiSell;
    private final double rsiExitLong;
    private final double rsiExitShort;
    private final int    smaPeriod;
    private final int    ctiPeriod;
    private final double ctiThreshold;
    private final int    ewoPeriod1;
    private final int    ewoPeriod2;
    private final int    atrPeriod;
    private final double atrSlMult;
    private final double atrTpMult;

    // 최소 필요 캔들 수
    private final int minCandles;

    public DynamicExitScalpingStrategy(TradingConfig config) {
        com.typesafe.config.Config raw = com.typesafe.config.ConfigFactory.load();
        String ns = "dynamicExitScalping";

        this.rsiPeriod    = getInt(raw,    ns + ".rsiPeriod",    DEFAULT_RSI_PERIOD);
        this.rsiBuy       = getDbl(raw,    ns + ".rsiBuy",       DEFAULT_RSI_BUY);
        this.rsiSell      = getDbl(raw,    ns + ".rsiSell",      DEFAULT_RSI_SELL);
        this.rsiExitLong  = getDbl(raw,    ns + ".rsiExitLong",  DEFAULT_RSI_EXIT_LONG);
        this.rsiExitShort = getDbl(raw,    ns + ".rsiExitShort", DEFAULT_RSI_EXIT_SHORT);
        this.smaPeriod    = getInt(raw,    ns + ".smaPeriod",    DEFAULT_SMA_PERIOD);
        this.ctiPeriod    = getInt(raw,    ns + ".ctiPeriod",    DEFAULT_CTI_PERIOD);
        this.ctiThreshold = getDbl(raw,    ns + ".ctiThreshold", DEFAULT_CTI_THRESHOLD);
        this.ewoPeriod1   = getInt(raw,    ns + ".ewoPeriod1",   DEFAULT_EWO_PERIOD1);
        this.ewoPeriod2   = getInt(raw,    ns + ".ewoPeriod2",   DEFAULT_EWO_PERIOD2);
        this.atrPeriod    = getInt(raw,    ns + ".atrPeriod",    DEFAULT_ATR_PERIOD);
        this.atrSlMult    = getDbl(raw,    ns + ".atrSlMult",    DEFAULT_ATR_SL_MULT);
        this.atrTpMult    = getDbl(raw,    ns + ".atrTpMult",    DEFAULT_ATR_TP_MULT);

        this.minCandles = Math.max(smaPeriod, Math.max(ewoPeriod2 + 1, rsiPeriod + 1)) + 5;
        log.info("DynamicExitScalpingStrategy 초기화: RSI({}/{}/{}) SMA({}) CTI({}) ATR({}×{}/{}×{})",
            rsiPeriod, rsiBuy, rsiSell, smaPeriod, ctiPeriod,
            atrPeriod, atrSlMult, atrPeriod, atrTpMult);
    }

    /** 파라미터 스윕용 직접 생성자 */
    public DynamicExitScalpingStrategy(double rsiBuy, double rsiSell,
                                        double rsiExitLong, double rsiExitShort,
                                        double ctiThreshold,
                                        double atrSlMult, double atrTpMult) {
        this.rsiPeriod    = DEFAULT_RSI_PERIOD;
        this.rsiBuy       = rsiBuy;
        this.rsiSell      = rsiSell;
        this.rsiExitLong  = rsiExitLong;
        this.rsiExitShort = rsiExitShort;
        this.smaPeriod    = DEFAULT_SMA_PERIOD;
        this.ctiPeriod    = DEFAULT_CTI_PERIOD;
        this.ctiThreshold = ctiThreshold;
        this.ewoPeriod1   = DEFAULT_EWO_PERIOD1;
        this.ewoPeriod2   = DEFAULT_EWO_PERIOD2;
        this.atrPeriod    = DEFAULT_ATR_PERIOD;
        this.atrSlMult    = atrSlMult;
        this.atrTpMult    = atrTpMult;
        this.minCandles   = Math.max(DEFAULT_SMA_PERIOD, DEFAULT_EWO_PERIOD2 + 1) + 5;
    }

    @Override
    public Signal generateSignal(List<Candle> candles, String pair, Position position) {
        if (candles.size() < minCandles) return hold("데이터 부족");

        double close = last(candles).getClose();
        List<Double> closes = candles.stream().map(Candle::getClose).collect(Collectors.toList());

        // ── 지표 계산 ─────────────────────────────────────────────────
        double rsi = lastOf(TechnicalIndicators.calculateRSI(candles, rsiPeriod));
        double sma = sma(closes, smaPeriod);
        double cti = calculateCTI(closes, ctiPeriod);
        double ewo = ewo(closes, ewoPeriod1, ewoPeriod2);
        double atr = TechnicalIndicators.calculateATR(candles, atrPeriod);

        // ── 포지션 보유 중: 동적 익절(RSI 반전) ──────────────────────
        if (position != null) {
            String side = position.getSide();
            if ("BUY".equals(side) && rsi >= rsiExitLong) {
                return exit("Long RSI 익절 RSI=" + fmt(rsi));
            }
            if ("SHORT".equals(side) && rsi <= rsiExitShort) {
                return exit("Short RSI 익절 RSI=" + fmt(rsi));
            }
            return hold("포지션 유지");
        }

        // ── 롱 진입 조건 ─────────────────────────────────────────────
        //   1) RSI < rsiBuy (과매도 — 딥 구간)
        //   2) 가격 > SMA (상승 추세 확인)
        //   3) CTI < ctiThreshold (하락 모멘텀 소진 조짐)
        //   Note: EWO 조건 제거 — 과매도 구간에서 EWO는 항상 음수라 역방향 필터가 됨
        if (rsi < rsiBuy
                && close > sma
                && cti < ctiThreshold) {

            double sl = close - atr * atrSlMult;
            double tp = close + atr * atrTpMult;
            log.info("[{}] Long 진입 RSI={} SMA={} CTI={} EWO={} ATR={}  SL={} TP={}",
                pair, fmt(rsi), fmt(sma), fmt(cti), fmt(ewo), fmt(atr), fmt(sl), fmt(tp));
            return Signal.builder()
                .action(Signal.Action.BUY)
                .entryPrice(close)
                .stopLoss(sl)
                .takeProfit(tp)
                .reason(String.format("Long: RSI=%.1f CTI=%.2f EWO=%.4f", rsi, cti, ewo))
                .build();
        }

        // ── 숏 진입 조건 ─────────────────────────────────────────────
        //   1) RSI > rsiSell (과매수 — 반등 꼭지)
        //   2) 가격 < SMA (하락 추세 확인)
        //   3) CTI > -ctiThreshold (반등 모멘텀 존재 → 꼭지에서 숏)
        //   Note: EWO 조건 제거 — 반등 중 EWO는 항상 양수라 역방향 필터가 됨
        if (rsi > rsiSell
                && close < sma
                && cti > -ctiThreshold) {

            double sl = close + atr * atrSlMult;
            double tp = close - atr * atrTpMult;
            log.info("[{}] Short 진입 RSI={} SMA={} CTI={} EWO={} ATR={}  SL={} TP={}",
                pair, fmt(rsi), fmt(sma), fmt(cti), fmt(ewo), fmt(atr), fmt(sl), fmt(tp));
            return Signal.builder()
                .action(Signal.Action.SHORT)
                .entryPrice(close)
                .stopLoss(sl)
                .takeProfit(tp)
                .reason(String.format("Short: RSI=%.1f CTI=%.2f EWO=%.4f", rsi, cti, ewo))
                .build();
        }

        return hold("조건 미충족 RSI=" + fmt(rsi));
    }

    // ── 지표 헬퍼 ─────────────────────────────────────────────────────

    /**
     * CTI (Correlation Trend Index) 간이 구현.
     * 최근 N개 종가와 선형 시퀀스 [0, 1, ..., N-1] 간의 Pearson 상관계수.
     * +1 = 완벽한 상승 추세, -1 = 완벽한 하락 추세.
     */
    private double calculateCTI(List<Double> closes, int period) {
        int size = closes.size();
        if (size < period) return 0;

        List<Double> window = closes.subList(size - period, size);
        double n = period;

        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0, sumY2 = 0;
        for (int i = 0; i < period; i++) {
            double x = i;
            double y = window.get(i);
            sumX  += x;
            sumY  += y;
            sumXY += x * y;
            sumX2 += x * x;
            sumY2 += y * y;
        }

        double num = n * sumXY - sumX * sumY;
        double den = Math.sqrt((n * sumX2 - sumX * sumX) * (n * sumY2 - sumY * sumY));
        return den == 0 ? 0 : num / den;
    }

    /**
     * EWO (Elliott Wave Oscillator) = EMA(fast) - EMA(slow).
     * 양수이면 단기 모멘텀 상승, 음수이면 하락.
     */
    private double ewo(List<Double> closes, int fast, int slow) {
        double emaFast = ema(closes, fast);
        double emaSlow = ema(closes, slow);
        return emaFast - emaSlow;
    }

    /** 단순이동평균 */
    private double sma(List<Double> closes, int period) {
        int size = closes.size();
        if (size < period) return closes.isEmpty() ? 0 : closes.get(size - 1);
        return closes.subList(size - period, size).stream()
            .mapToDouble(Double::doubleValue).average().orElse(0);
    }

    /** 지수이동평균 (마지막 값) */
    private double ema(List<Double> closes, int period) {
        int size = closes.size();
        if (size < period) return closes.isEmpty() ? 0 : closes.get(size - 1);
        double k = 2.0 / (period + 1);
        double emaVal = closes.subList(0, period).stream()
            .mapToDouble(Double::doubleValue).average().orElse(0);
        for (int i = period; i < size; i++) {
            emaVal = closes.get(i) * k + emaVal * (1 - k);
        }
        return emaVal;
    }

    private Candle last(List<Candle> candles) {
        return candles.get(candles.size() - 1);
    }

    private double lastOf(List<Double> list) {
        if (list == null || list.isEmpty()) return 0;
        return list.get(list.size() - 1);
    }

    private Signal hold(String reason) {
        return Signal.builder().action(Signal.Action.HOLD).reason(reason).build();
    }

    private Signal exit(String reason) {
        return Signal.builder().action(Signal.Action.EXIT).reason(reason).build();
    }

    private String fmt(double v) {
        return String.format("%.4f", v);
    }

    // ── Config 헬퍼 ───────────────────────────────────────────────────

    private int getInt(com.typesafe.config.Config c, String path, int def) {
        return c.hasPath(path) ? c.getInt(path) : def;
    }

    private double getDbl(com.typesafe.config.Config c, String path, double def) {
        return c.hasPath(path) ? c.getDouble(path) : def;
    }
}