package main.backtest;

import lombok.extern.slf4j.Slf4j;
import main.bitget.BitgetFuturesApiClient;
import main.model.Candle;
import main.model.TradingConfig;
import main.strategy.BollingerBandReversionStrategy;
import main.strategy.DynamicExitScalpingStrategy;
import main.strategy.MomentumScalpingStrategy;
import main.strategy.TradingStrategy;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 백테스트 실행 진입점.
 *
 * main() → 단일 백테스트 (application.conf 파라미터 사용)
 * runSweep() → 파라미터 스윕 (최적 조합 탐색)
 *
 * 기본 설정:
 *   - 심볼: BTCUSDT
 *   - 타임프레임: 1h (더 많은 거래 신호 확보)
 *   - 초기 잔고: $10,000
 *   - 수수료: 0.06% 편도 (Bitget taker)
 */
@Slf4j
public class BacktestRunner {

    // ── 공통 파라미터 ──────────────────────────────────────────────
    private static final String SYMBOL   = "BTCUSDT";
    private static final double INIT_BAL = 10_000.0;
    private static final double FEE_PCT  = 0.06;     // 편도 수수료 %

    public static void main(String[] args) {
        // 멀티코인 BB 전략 검증 — BTC 최적 파라미터가 ETH/SOL/XRP에도 통하는지 확인
        runMultiCoinComparison();
    }

    // ── 단일 백테스트 (application.conf 설정 기반) ───────────────────
    public static void runSingle() {
        final String timeframe = "15m";
        final int candleCnt = 2000;
        final int warmup = 210;

        log.info("===== 단일 백테스트 시작 =====");
        log.info("심볼={} 타임프레임={} 캔들수={}", SYMBOL, timeframe, candleCnt);

        TradingConfig config = TradingConfig.getInstance();
        List<Candle> candles = fetchCandles(config, timeframe, candleCnt);
        if (candles.isEmpty()) return;

        TradingStrategy strategy = new DynamicExitScalpingStrategy(config);
        Backtester backtester = new Backtester(strategy, candles, INIT_BAL, FEE_PCT, warmup, SYMBOL);
        BacktestResult result = backtester.run();

        printResult(result);
        log.info("===== 단일 백테스트 종료 =====");
    }

    // ── 파라미터 스윕 ────────────────────────────────────────────────
    public static void runSweep() {
        final String timeframe = "15m";
        final int candleCnt = 2000;
        final int warmup = 210;

        log.info("===== 파라미터 스윕 시작 =====");
        log.info("심볼={} 타임프레임={} 캔들수={}", SYMBOL, timeframe, candleCnt);

        TradingConfig config = TradingConfig.getInstance();
        List<Candle> candles = fetchCandles(config, timeframe, candleCnt);
        if (candles.isEmpty()) return;

        // 기간 계산 (일)
        double totalDays = (candles.get(candles.size() - 1).getTimestamp()
            - candles.get(warmup).getTimestamp()) / (1000.0 * 60 * 60 * 24);

        log.info("캔들 {}개, 백테스트 기간 {}일", candles.size(), String.format("%.1f", totalDays));
        System.out.printf("%n[파라미터 스윕] 기간: %.1f일  목표: 승률>=70%%, 하루 2~3건 이상%n%n", totalDays);

        // ── 스윕 범위 정의 ──────────────────────────────────────────
        double[] rsiBuys      = {35, 40, 45, 50};
        double[] rsiSells     = {50, 55, 60, 65};
        double[] ctiThresholds = {0.0, -0.1, -0.2, -0.3};  // Long: CTI < threshold
        double[] slMults      = {1.0, 1.5, 2.0};
        double[] tpMults      = {1.5, 2.0, 2.5, 3.0};
        // rsiExitLong = rsiBuy + 15,  rsiExitShort = rsiSell - 15 (자동 계산)

        List<SweepResult> results = new ArrayList<>();
        int total = rsiBuys.length * rsiSells.length * ctiThresholds.length
            * slMults.length * tpMults.length;
        int count = 0;

        for (double rsiBuy : rsiBuys) {
            for (double rsiSell : rsiSells) {
                if (rsiSell <= rsiBuy) continue;       // 롱/숏 범위 중복 방지

                double rsiExitLong  = Math.min(rsiBuy + 15, rsiSell - 1);
                double rsiExitShort = Math.max(rsiSell - 15, rsiBuy + 1);

                for (double cti : ctiThresholds) {
                    for (double sl : slMults) {
                        for (double tp : tpMults) {
                            if (tp <= sl) continue;    // R:R 1 이상 보장

                            TradingStrategy strategy = new DynamicExitScalpingStrategy(
                                rsiBuy, rsiSell, rsiExitLong, rsiExitShort, cti, sl, tp);
                            Backtester bt = new Backtester(
                                strategy, candles, INIT_BAL, FEE_PCT, warmup, SYMBOL);
                            BacktestResult r = bt.run();

                            double tradesPerDay = totalDays > 0
                                ? r.getTotalTrades() / totalDays : 0;

                            results.add(new SweepResult(
                                rsiBuy, rsiSell, rsiExitLong, rsiExitShort,
                                cti, sl, tp, r, tradesPerDay));

                            count++;
                            if (count % 50 == 0) {
                                log.info("스윕 진행: {}/{}", count, total);
                            }
                        }
                    }
                }
            }
        }

        printSweepResults(results, totalDays);
        log.info("===== 파라미터 스윕 종료 ({} 조합) =====", results.size());
    }

    // ── BB 평균회귀 파라미터 스윕 ────────────────────────────────────
    public static void runBBSweep() {
        final String timeframe = "15m";
        final int candleCnt = 2000;
        final int warmup = 210;

        log.info("===== BB 평균회귀 파라미터 스윕 시작 =====");
        log.info("심볼={} 타임프레임={} 캔들수={}", SYMBOL, timeframe, candleCnt);

        TradingConfig config = TradingConfig.getInstance();
        List<Candle> candles = fetchCandles(config, timeframe, candleCnt);
        if (candles.isEmpty()) return;

        double totalDays = (candles.get(candles.size() - 1).getTimestamp()
            - candles.get(warmup).getTimestamp()) / (1000.0 * 60 * 60 * 24);

        log.info("캔들 {}개, 백테스트 기간 {}일", candles.size(), String.format("%.1f", totalDays));
        System.out.printf("%n[BB 스윕] 기간: %.1f일  목표: 승률>=65%%, 하루 2건 이상%n%n", totalDays);

        int[]    bbPeriods   = {15, 20};
        double[] bbStdDevs   = {1.5, 2.0, 2.5, 3.0};
        double[] rsiOversolds   = {25, 30, 35};
        double[] rsiOverboughts = {65, 70, 75};
        double[] slMults     = {1.5, 2.0};
        double[] tpMults     = {2.0, 2.5, 3.0};

        List<SweepResult> results = new ArrayList<>();
        int total = bbPeriods.length * bbStdDevs.length * rsiOversolds.length
            * rsiOverboughts.length * slMults.length * tpMults.length;
        int count = 0;

        for (int bbP : bbPeriods) {
            for (double bbSd : bbStdDevs) {
                for (double rsiBuy : rsiOversolds) {
                    for (double rsiSell : rsiOverboughts) {
                        for (double sl : slMults) {
                            for (double tp : tpMults) {
                                if (tp <= sl) continue;

                                TradingStrategy strategy = new BollingerBandReversionStrategy(
                                    bbP, bbSd, rsiBuy, rsiSell, sl, tp);
                                Backtester bt = new Backtester(
                                    strategy, candles, INIT_BAL, FEE_PCT, warmup, SYMBOL);
                                BacktestResult r = bt.run();

                                double tradesPerDay = totalDays > 0
                                    ? r.getTotalTrades() / totalDays : 0;

                                // rsiBuy→cti 필드를 bbPeriod/bbStdDev 재활용
                                results.add(new SweepResult(
                                    rsiBuy, rsiSell, bbP, bbSd,
                                    0, sl, tp, r, tradesPerDay));

                                count++;
                                if (count % 50 == 0) {
                                    log.info("BB 스윕 진행: {}/{}", count, total);
                                }
                            }
                        }
                    }
                }
            }
        }

        printBBSweepResults(results, totalDays);
        log.info("===== BB 파라미터 스윕 종료 ({} 조합) =====", results.size());
    }

    // ── EMA Cross 모멘텀 스캘핑 파라미터 스윕 (1m, 시간당 1-2건 목표) ─────
    public static void runMomentumSweep() {
        final String timeframe = "1m";
        final int candleCnt = 4320;   // 1m × 4320 = 3일
        final int warmup = 35;        // slowEma max(21) + RSI(14) + 버퍼

        log.info("===== EMA 모멘텀 스캘핑 파라미터 스윕 시작 =====");
        log.info("심볼={} 타임프레임={} 캔들수={}", SYMBOL, timeframe, candleCnt);

        TradingConfig config = TradingConfig.getInstance();
        List<Candle> candles = fetchCandles(config, timeframe, candleCnt);
        if (candles.isEmpty()) return;

        double totalDays  = (candles.get(candles.size() - 1).getTimestamp()
            - candles.get(warmup).getTimestamp()) / (1000.0 * 60 * 60 * 24);
        double totalHours = totalDays * 24;

        log.info("캔들 {}개, 백테스트 기간 {}일 ({}시간)",
            candles.size(),
            String.format("%.1f", totalDays),
            String.format("%.0f", totalHours));
        System.out.printf("%n[EMA 모멘텀 스윕] 기간: %.1f일 (%.0f시간)  목표: 시간당 1-2건%n%n",
            totalDays, totalHours);

        int[]    fastEmas = {3, 5, 8};
        int[]    slowEmas = {13, 21};
        double[] slMults  = {0.8, 1.0, 1.2, 1.5};
        double[] tpMults  = {1.2, 1.5, 2.0, 2.5};

        List<MomentumSweepResult> results = new ArrayList<>();
        int total = fastEmas.length * slowEmas.length * slMults.length * tpMults.length;
        int count = 0;

        for (int fast : fastEmas) {
            for (int slow : slowEmas) {
                for (double sl : slMults) {
                    for (double tp : tpMults) {
                        if (tp <= sl) continue;

                        TradingStrategy strategy = new MomentumScalpingStrategy(fast, slow, sl, tp);
                        Backtester bt = new Backtester(
                            strategy, candles, INIT_BAL, FEE_PCT, warmup, SYMBOL);
                        BacktestResult r = bt.run();

                        double tradesPerDay  = totalDays  > 0 ? r.getTotalTrades() / totalDays  : 0;
                        double tradesPerHour = totalHours > 0 ? r.getTotalTrades() / totalHours : 0;

                        results.add(new MomentumSweepResult(fast, slow, sl, tp, r,
                            tradesPerDay, tradesPerHour));

                        count++;
                        if (count % 20 == 0) {
                            log.info("모멘텀 스윕 진행: {}/{}", count, total);
                        }
                    }
                }
            }
        }

        printMomentumSweepResults(results, totalDays, totalHours);
        log.info("===== EMA 모멘텀 스윕 종료 ({} 조합) =====", results.size());
    }

    // ── 모멘텀 스윕 결과 출력 ──────────────────────────────────────────
    private static void printMomentumSweepResults(List<MomentumSweepResult> results,
                                                   double totalDays, double totalHours) {
        System.out.println("══════════════════════════════════════════════════════════════════════════════");
        System.out.println("         EMA 모멘텀 스캘핑 스윕 결과 (Top 20 — 수익률 기준)");
        System.out.println("══════════════════════════════════════════════════════════════════════════════");
        System.out.printf("%-6s %-6s %-5s %-5s │ %5s %6s %7s %7s %7s %6s %6s%n",
            "fast", "slow", "SL×", "TP×",
            "거래수", "승률%", "건/시간", "건/일", "수익률%", "MDD%", "Sharpe");
        System.out.println("──────────────────────────────────────────────────────────────────────────────");

        results.stream()
            .filter(r -> r.result.getTotalTrades() >= 5)
            .sorted(Comparator.comparingDouble((MomentumSweepResult r) ->
                r.result.getTotalReturnPct()).reversed())
            .limit(20)
            .forEach(r -> {
                BacktestResult br = r.result;
                String flag = r.tradesPerHour >= 1.0 ? " ★" : "";
                System.out.printf("%-6d %-6d %-5.1f %-5.1f │ %5d %6.1f %7.2f %7.2f %7.2f %6.2f %6.2f%s%n",
                    r.fastEma, r.slowEma, r.sl, r.tp,
                    br.getTotalTrades(), br.getWinRate(),
                    r.tradesPerHour, r.tradesPerDay,
                    br.getTotalReturnPct(), br.getMaxDrawdownPct(), br.getSharpeRatio(),
                    flag);
            });

        System.out.println("──────────────────────────────────────────────────────────────────────────────");
        System.out.println("★ = 시간당 1건 이상");

        System.out.println();
        System.out.println("══════════════════════════════════════════════════════════════════════════════");
        System.out.println("         Top 10 — 거래 빈도 기준 (최소 10건)");
        System.out.println("══════════════════════════════════════════════════════════════════════════════");
        System.out.printf("%-6s %-6s %-5s %-5s │ %5s %6s %7s %7s %7s %6s %6s%n",
            "fast", "slow", "SL×", "TP×",
            "거래수", "승률%", "건/시간", "건/일", "수익률%", "MDD%", "Sharpe");
        System.out.println("──────────────────────────────────────────────────────────────────────────────");

        results.stream()
            .filter(r -> r.result.getTotalTrades() >= 10)
            .sorted(Comparator.comparingDouble((MomentumSweepResult r) ->
                r.tradesPerHour).reversed())
            .limit(10)
            .forEach(r -> {
                BacktestResult br = r.result;
                String flag = r.tradesPerHour >= 1.0 ? " ★" : "";
                System.out.printf("%-6d %-6d %-5.1f %-5.1f │ %5d %6.1f %7.2f %7.2f %7.2f %6.2f %6.2f%s%n",
                    r.fastEma, r.slowEma, r.sl, r.tp,
                    br.getTotalTrades(), br.getWinRate(),
                    r.tradesPerHour, r.tradesPerDay,
                    br.getTotalReturnPct(), br.getMaxDrawdownPct(), br.getSharpeRatio(),
                    flag);
            });
        System.out.println("──────────────────────────────────────────────────────────────────────────────");

        long hitTarget = results.stream()
            .filter(r -> r.tradesPerHour >= 1.0)
            .count();
        System.out.printf("%n목표(시간당 1건 이상) 달성 조합: %d개 / 전체 %d개%n", hitTarget, results.size());
        System.out.printf("전체 기간: %.1f일 (%.0f시간)%n", totalDays, totalHours);
    }

    // ── 모멘텀 스윕 결과 컨테이너 ──────────────────────────────────────
    private static class MomentumSweepResult {
        final int fastEma, slowEma;
        final double sl, tp;
        final BacktestResult result;
        final double tradesPerDay, tradesPerHour;

        MomentumSweepResult(int fastEma, int slowEma, double sl, double tp,
                             BacktestResult result, double tradesPerDay, double tradesPerHour) {
            this.fastEma = fastEma;
            this.slowEma = slowEma;
            this.sl = sl;
            this.tp = tp;
            this.result = result;
            this.tradesPerDay = tradesPerDay;
            this.tradesPerHour = tradesPerHour;
        }
    }

    // ── BB 스윕 결과 출력 ─────────────────────────────────────────────
    private static void printBBSweepResults(List<SweepResult> results, double totalDays) {
        System.out.println("════════════════════════════════════════════════════════════════════════════════════");
        System.out.println("               BB 평균회귀 파라미터 스윕 결과 (Top 20 — 수익률 기준)");
        System.out.println("════════════════════════════════════════════════════════════════════════════════════");
        System.out.printf("%-5s %-5s %-7s %-7s %-5s %-5s │ %5s %6s %7s %7s %6s %6s%n",
            "bbP", "bbSD", "rsiOS", "rsiOB", "SL×", "TP×",
            "거래수", "승률%", "건/일", "수익률%", "MDD%", "Sharpe");
        System.out.println("────────────────────────────────────────────────────────────────────────────────────");

        results.stream()
            .filter(r -> r.result.getTotalTrades() >= 3)
            .sorted(Comparator.comparingDouble((SweepResult r) -> r.result.getTotalReturnPct()).reversed())
            .limit(20)
            .forEach(r -> {
                BacktestResult br = r.result;
                String flag = br.getWinRate() >= 65.0 && r.tradesPerDay >= 1.0 ? " ★" : "";
                System.out.printf("%-5.0f %-5.1f %-7.0f %-7.0f %-5.1f %-5.1f │ %5d %6.1f %7.2f %7.2f %6.2f %6.2f%s%n",
                    r.rsiExitLong, r.rsiExitShort,  // bbPeriod, bbStdDev
                    r.rsiBuy, r.rsiSell,             // rsiOversold, rsiOverbought
                    r.sl, r.tp,
                    br.getTotalTrades(), br.getWinRate(), r.tradesPerDay,
                    br.getTotalReturnPct(), br.getMaxDrawdownPct(), br.getSharpeRatio(),
                    flag);
            });

        System.out.println("────────────────────────────────────────────────────────────────────────────────────");
        System.out.println("★ = 승률 ≥ 65% AND 1건/일 이상");

        System.out.println();
        System.out.println("════════════════════════════════════════════════════════════════════════════════════");
        System.out.println("               Top 10 — 승률 기준 (최소 5건 이상)");
        System.out.println("════════════════════════════════════════════════════════════════════════════════════");
        System.out.printf("%-5s %-5s %-7s %-7s %-5s %-5s │ %5s %6s %7s %7s %6s %6s%n",
            "bbP", "bbSD", "rsiOS", "rsiOB", "SL×", "TP×",
            "거래수", "승률%", "건/일", "수익률%", "MDD%", "Sharpe");
        System.out.println("────────────────────────────────────────────────────────────────────────────────────");

        results.stream()
            .filter(r -> r.result.getTotalTrades() >= 5)
            .sorted(Comparator.comparingDouble((SweepResult r) -> r.result.getWinRate()).reversed())
            .limit(10)
            .forEach(r -> {
                BacktestResult br = r.result;
                String flag = br.getWinRate() >= 65.0 && r.tradesPerDay >= 1.0 ? " ★" : "";
                System.out.printf("%-5.0f %-5.1f %-7.0f %-7.0f %-5.1f %-5.1f │ %5d %6.1f %7.2f %7.2f %6.2f %6.2f%s%n",
                    r.rsiExitLong, r.rsiExitShort,
                    r.rsiBuy, r.rsiSell,
                    r.sl, r.tp,
                    br.getTotalTrades(), br.getWinRate(), r.tradesPerDay,
                    br.getTotalReturnPct(), br.getMaxDrawdownPct(), br.getSharpeRatio(),
                    flag);
            });
        System.out.println("────────────────────────────────────────────────────────────────────────────────────");

        long hitTarget = results.stream()
            .filter(r -> r.result.getWinRate() >= 65.0 && r.tradesPerDay >= 1.0)
            .count();
        System.out.printf("%n목표(승률≥65%% + 1건/일 이상) 달성 조합: %d개 / 전체 %d개%n", hitTarget, results.size());
    }

    // ── 스윕 결과 출력 ───────────────────────────────────────────────
    private static void printSweepResults(List<SweepResult> results, double totalDays) {
        System.out.println("════════════════════════════════════════════════════════════════════════════════════════════════════════");
        System.out.println("                              파라미터 스윕 결과 (Top 20 — 수익률 기준)");
        System.out.println("════════════════════════════════════════════════════════════════════════════════════════════════════════");
        System.out.printf("%-6s %-6s %-6s %-6s %-6s %-5s %-5s │ %5s %6s %7s %7s %6s %6s%n",
            "rsiBuy", "rsiSel", "rsiEL", "rsiES", "CTI", "SL×", "TP×",
            "거래수", "승률%", "건/일", "수익률%", "MDD%", "Sharpe");
        System.out.println("──────────────────────────────────────────────────────────────────────────────────────────────────────");

        // 수익률 기준 정렬 후 Top 20
        results.stream()
            .filter(r -> r.result.getTotalTrades() >= 3)   // 최소 3건 이상
            .sorted(Comparator.comparingDouble((SweepResult r) -> r.result.getTotalReturnPct()).reversed())
            .limit(20)
            .forEach(r -> {
                BacktestResult br = r.result;
                String flag = br.getWinRate() >= 65.0 && r.tradesPerDay >= 1.0 ? " ★" : "";
                System.out.printf("%-6.0f %-6.0f %-6.0f %-6.0f %-6.1f %-5.1f %-5.1f │ %5d %6.1f %7.2f %7.2f %6.2f %6.2f%s%n",
                    r.rsiBuy, r.rsiSell, r.rsiExitLong, r.rsiExitShort,
                    r.cti, r.sl, r.tp,
                    br.getTotalTrades(), br.getWinRate(), r.tradesPerDay,
                    br.getTotalReturnPct(), br.getMaxDrawdownPct(), br.getSharpeRatio(),
                    flag);
            });

        System.out.println("──────────────────────────────────────────────────────────────────────────────────────────────────────");
        System.out.println("★ = 승률 ≥ 70% AND 1건/일 이상 동시 충족");

        // 승률 기준 Top 10 (추가 테이블)
        System.out.println();
        System.out.println("════════════════════════════════════════════════════════════════════════════════════════════════════════");
        System.out.println("                              Top 10 — 승률 기준 (최소 5건 이상)");
        System.out.println("════════════════════════════════════════════════════════════════════════════════════════════════════════");
        System.out.printf("%-6s %-6s %-6s %-6s %-6s %-5s %-5s │ %5s %6s %7s %7s %6s %6s%n",
            "rsiBuy", "rsiSel", "rsiEL", "rsiES", "CTI", "SL×", "TP×",
            "거래수", "승률%", "건/일", "수익률%", "MDD%", "Sharpe");
        System.out.println("──────────────────────────────────────────────────────────────────────────────────────────────────────");

        results.stream()
            .filter(r -> r.result.getTotalTrades() >= 5)
            .sorted(Comparator.comparingDouble((SweepResult r) -> r.result.getWinRate()).reversed())
            .limit(10)
            .forEach(r -> {
                BacktestResult br = r.result;
                String flag = br.getWinRate() >= 65.0 && r.tradesPerDay >= 1.0 ? " ★" : "";
                System.out.printf("%-6.0f %-6.0f %-6.0f %-6.0f %-6.1f %-5.1f %-5.1f │ %5d %6.1f %7.2f %7.2f %6.2f %6.2f%s%n",
                    r.rsiBuy, r.rsiSell, r.rsiExitLong, r.rsiExitShort,
                    r.cti, r.sl, r.tp,
                    br.getTotalTrades(), br.getWinRate(), r.tradesPerDay,
                    br.getTotalReturnPct(), br.getMaxDrawdownPct(), br.getSharpeRatio(),
                    flag);
            });
        System.out.println("──────────────────────────────────────────────────────────────────────────────────────────────────────");

        // 목표 달성 요약
        long hitTarget = results.stream()
            .filter(r -> r.result.getWinRate() >= 70.0 && r.tradesPerDay >= 1.0)
            .count();
        System.out.printf("%n목표(승률≥70%% + 1건/일 이상) 달성 조합: %d개 / 전체 %d개%n", hitTarget, results.size());
    }

    // ── 단일 결과 출력 ───────────────────────────────────────────────
    private static void printResult(BacktestResult result) {
        System.out.println();
        System.out.println(result);
        System.out.println();

        List<BacktestResult.TradeRecord> trades = result.getTrades();
        if (!trades.isEmpty()) {
            System.out.println("── 거래 내역 (최근 " + Math.min(30, trades.size()) + "건) ──────────────");
            System.out.printf("%-6s %-6s %-22s %-22s %-10s %-10s %-8s%n",
                "No", "사이드", "진입시각", "청산시각", "진입가", "청산가", "수익률");
            int start = Math.max(0, trades.size() - 30);
            for (int i = start; i < trades.size(); i++) {
                BacktestResult.TradeRecord t = trades.get(i);
                System.out.printf("%-6d %-6s %-22s %-22s %-10.2f %-10.2f %+.2f%% [%s]%n",
                    i + 1,
                    t.getSide(),
                    formatTs(t.getEntryTimestamp()),
                    formatTs(t.getExitTimestamp()),
                    t.getEntryPrice(),
                    t.getExitPrice(),
                    t.getPnlPct(),
                    t.getExitReason());
            }
        }
    }

    // ── API 캔들 수집 ────────────────────────────────────────────────
    private static List<Candle> fetchCandles(TradingConfig config, String timeframe, int candleCount) {
        return fetchCandles(config, SYMBOL, timeframe, candleCount);
    }

    private static List<Candle> fetchCandles(TradingConfig config, String symbol, String timeframe, int candleCount) {
        BitgetFuturesApiClient apiClient = new BitgetFuturesApiClient(
            config.getApiKey(),
            config.getSecretKey(),
            config.getPassphrase(),
            config.getProductType(),
            config.getMarginMode()
        );

        log.info("Bitget API에서 {}개 {} {} 캔들 수집 중...", candleCount, symbol, timeframe);
        List<Candle> candles = apiClient.getCandles(symbol, timeframe, candleCount);

        if (candles.isEmpty()) {
            log.error("캔들 데이터를 가져오지 못했습니다. API 설정을 확인하세요.");
            return candles;
        }
        log.info("캔들 수집 완료: {}개  기간: {} ~ {}",
            candles.size(),
            formatTs(candles.get(0).getTimestamp()),
            formatTs(candles.get(candles.size() - 1).getTimestamp()));
        return candles;
    }

    // ── 스윕 결과 컨테이너 ───────────────────────────────────────────
    private static class SweepResult {
        final double rsiBuy, rsiSell, rsiExitLong, rsiExitShort, cti, sl, tp;
        final BacktestResult result;
        final double tradesPerDay;

        SweepResult(double rsiBuy, double rsiSell, double rsiExitLong, double rsiExitShort,
                    double cti, double sl, double tp,
                    BacktestResult result, double tradesPerDay) {
            this.rsiBuy = rsiBuy;
            this.rsiSell = rsiSell;
            this.rsiExitLong = rsiExitLong;
            this.rsiExitShort = rsiExitShort;
            this.cti = cti;
            this.sl = sl;
            this.tp = tp;
            this.result = result;
            this.tradesPerDay = tradesPerDay;
        }
    }

    // ── BB 평균회귀 5m 파라미터 스윕 (일 6-8건 목표) ─────────────────────
    public static void runBB5mSweep() {
        final String timeframe = "5m";
        final int candleCnt = 4320;   // 5m × 4320 = 15일
        final int warmup = 40;

        log.info("===== BB 평균회귀 5m 파라미터 스윕 시작 =====");
        log.info("심볼={} 타임프레임={} 캔들수={}", SYMBOL, timeframe, candleCnt);

        TradingConfig config = TradingConfig.getInstance();
        List<Candle> candles = fetchCandles(config, timeframe, candleCnt);
        if (candles.isEmpty()) return;

        double totalDays  = (candles.get(candles.size() - 1).getTimestamp()
            - candles.get(warmup).getTimestamp()) / (1000.0 * 60 * 60 * 24);
        double totalHours = totalDays * 24;

        log.info("캔들 {}개, 백테스트 기간 {}일",
            candles.size(), String.format("%.1f", totalDays));
        System.out.printf("%n[BB 5m 스윕] 기간: %.1f일  목표: 일 6-8건 + 승률 60%%+ + 플러스 수익%n%n",
            totalDays);

        int[]    bbPeriods      = {10, 15, 20};
        double[] bbStdDevs      = {1.5, 2.0, 2.5};
        double[] rsiOversolds   = {25, 30, 35};
        double[] rsiOverboughts = {65, 70, 75};
        double[] slMults        = {0.8, 1.0, 1.5, 2.0};
        double[] tpMults        = {1.5, 2.0, 2.5, 3.0};

        List<BB1mSweepResult> results = new ArrayList<>();
        int total = bbPeriods.length * bbStdDevs.length * rsiOversolds.length
            * rsiOverboughts.length * slMults.length * tpMults.length;
        int count = 0;

        for (int bbP : bbPeriods) {
            for (double bbSd : bbStdDevs) {
                for (double rsiBuy : rsiOversolds) {
                    for (double rsiSell : rsiOverboughts) {
                        for (double sl : slMults) {
                            for (double tp : tpMults) {
                                if (tp <= sl) continue;

                                TradingStrategy strategy = new BollingerBandReversionStrategy(
                                    bbP, bbSd, rsiBuy, rsiSell, sl, tp);
                                Backtester bt = new Backtester(
                                    strategy, candles, INIT_BAL, FEE_PCT, warmup, SYMBOL);
                                BacktestResult r = bt.run();

                                double tradesPerDay  = totalDays  > 0 ? r.getTotalTrades() / totalDays  : 0;
                                double tradesPerHour = totalHours > 0 ? r.getTotalTrades() / totalHours : 0;

                                results.add(new BB1mSweepResult(
                                    bbP, bbSd, rsiBuy, rsiSell, sl, tp, r,
                                    tradesPerDay, tradesPerHour));

                                count++;
                                if (count % 100 == 0) {
                                    log.info("BB 5m 스윕 진행: {}/{}", count, total);
                                }
                            }
                        }
                    }
                }
            }
        }

        printBB5mSweepResults(results, totalDays);
        log.info("===== BB 5m 스윕 종료 ({} 조합) =====", results.size());
    }

    // ── BB 5m 스윕 결과 출력 ──────────────────────────────────────────
    private static void printBB5mSweepResults(List<BB1mSweepResult> results, double totalDays) {
        System.out.println("══════════════════════════════════════════════════════════════════════════════════════");
        System.out.println("               BB 평균회귀 5m 스윕 결과 (Top 20 — 수익률 기준)");
        System.out.println("══════════════════════════════════════════════════════════════════════════════════════");
        System.out.printf("%-4s %-4s %-7s %-7s %-5s %-5s │ %5s %6s %7s %7s %6s %6s%n",
            "bbP", "bbSD", "rsiOS", "rsiOB", "SL×", "TP×",
            "거래수", "승률%", "건/일", "수익률%", "MDD%", "Sharpe");
        System.out.println("──────────────────────────────────────────────────────────────────────────────────────");

        results.stream()
            .filter(r -> r.result.getTotalTrades() >= 5)
            .sorted(Comparator.comparingDouble((BB1mSweepResult r) ->
                r.result.getTotalReturnPct()).reversed())
            .limit(20)
            .forEach(r -> {
                BacktestResult br = r.result;
                String flag = r.tradesPerDay >= 3.0 && br.getTotalReturnPct() > 0 ? " ★" : "";
                System.out.printf("%-4d %-4.1f %-7.0f %-7.0f %-5.1f %-5.1f │ %5d %6.1f %7.2f %7.2f %6.2f %6.2f%s%n",
                    r.bbPeriod, r.bbStdDev, r.rsiOversold, r.rsiOverbought, r.sl, r.tp,
                    br.getTotalTrades(), br.getWinRate(),
                    r.tradesPerDay,
                    br.getTotalReturnPct(), br.getMaxDrawdownPct(), br.getSharpeRatio(),
                    flag);
            });
        System.out.println("──────────────────────────────────────────────────────────────────────────────────────");
        System.out.println("★ = 일 3건 이상 AND 플러스 수익");

        System.out.println();
        System.out.println("══════════════════════════════════════════════════════════════════════════════════════");
        System.out.println("               Top 10 — 승률 기준 (최소 10건)");
        System.out.println("══════════════════════════════════════════════════════════════════════════════════════");
        System.out.printf("%-4s %-4s %-7s %-7s %-5s %-5s │ %5s %6s %7s %7s %6s %6s%n",
            "bbP", "bbSD", "rsiOS", "rsiOB", "SL×", "TP×",
            "거래수", "승률%", "건/일", "수익률%", "MDD%", "Sharpe");
        System.out.println("──────────────────────────────────────────────────────────────────────────────────────");

        results.stream()
            .filter(r -> r.result.getTotalTrades() >= 10)
            .sorted(Comparator.comparingDouble((BB1mSweepResult r) ->
                r.result.getWinRate()).reversed())
            .limit(10)
            .forEach(r -> {
                BacktestResult br = r.result;
                String flag = r.tradesPerDay >= 3.0 && br.getTotalReturnPct() > 0 ? " ★" : "";
                System.out.printf("%-4d %-4.1f %-7.0f %-7.0f %-5.1f %-5.1f │ %5d %6.1f %7.2f %7.2f %6.2f %6.2f%s%n",
                    r.bbPeriod, r.bbStdDev, r.rsiOversold, r.rsiOverbought, r.sl, r.tp,
                    br.getTotalTrades(), br.getWinRate(),
                    r.tradesPerDay,
                    br.getTotalReturnPct(), br.getMaxDrawdownPct(), br.getSharpeRatio(),
                    flag);
            });
        System.out.println("──────────────────────────────────────────────────────────────────────────────────────");

        long hitTarget = results.stream()
            .filter(r -> r.tradesPerDay >= 3.0 && r.result.getTotalReturnPct() > 0)
            .count();
        System.out.printf("%n목표(일 3건+ AND 플러스 수익) 달성 조합: %d개 / 전체 %d개%n", hitTarget, results.size());
        System.out.printf("전체 기간: %.1f일%n", totalDays);
    }

    // ── BB 평균회귀 1m 파라미터 스윕 (시간당 1-2건 목표) ─────────────────
    public static void runBB1mSweep() {
        final String timeframe = "1m";
        final int candleCnt = 4320;   // 1m × 4320 = 3일
        final int warmup = 40;        // bbPeriod max(20) + rsi(14) + 버퍼

        log.info("===== BB 평균회귀 1m 파라미터 스윕 시작 =====");
        log.info("심볼={} 타임프레임={} 캔들수={}", SYMBOL, timeframe, candleCnt);

        TradingConfig config = TradingConfig.getInstance();
        List<Candle> candles = fetchCandles(config, timeframe, candleCnt);
        if (candles.isEmpty()) return;

        double totalDays  = (candles.get(candles.size() - 1).getTimestamp()
            - candles.get(warmup).getTimestamp()) / (1000.0 * 60 * 60 * 24);
        double totalHours = totalDays * 24;

        log.info("캔들 {}개, 백테스트 기간 {}일 ({}시간)",
            candles.size(),
            String.format("%.1f", totalDays),
            String.format("%.0f", totalHours));
        System.out.printf("%n[BB 1m 스윕] 기간: %.1f일 (%.0f시간)  목표: 시간당 1-2건 + 수익%n%n",
            totalDays, totalHours);

        // 1m에서 BB 신호 빈도 계산:
        // 60캔들/시간 × 4.5%(2σ 이탈) ≈ 2.7회/시간 이론적 신호
        // RSI 필터 후 약 1-2건/시간 기대
        int[]    bbPeriods   = {10, 15, 20};
        double[] bbStdDevs   = {1.5, 2.0, 2.5};
        double[] rsiOversolds   = {25, 30, 35};
        double[] rsiOverboughts = {65, 70, 75};
        double[] slMults     = {0.5, 0.8, 1.0, 1.5};
        double[] tpMults     = {1.0, 1.5, 2.0, 2.5};

        List<BB1mSweepResult> results = new ArrayList<>();
        int total = bbPeriods.length * bbStdDevs.length * rsiOversolds.length
            * rsiOverboughts.length * slMults.length * tpMults.length;
        int count = 0;

        for (int bbP : bbPeriods) {
            for (double bbSd : bbStdDevs) {
                for (double rsiBuy : rsiOversolds) {
                    for (double rsiSell : rsiOverboughts) {
                        for (double sl : slMults) {
                            for (double tp : tpMults) {
                                if (tp <= sl) continue;

                                TradingStrategy strategy = new BollingerBandReversionStrategy(
                                    bbP, bbSd, rsiBuy, rsiSell, sl, tp);
                                Backtester bt = new Backtester(
                                    strategy, candles, INIT_BAL, FEE_PCT, warmup, SYMBOL);
                                BacktestResult r = bt.run();

                                double tradesPerDay  = totalDays  > 0 ? r.getTotalTrades() / totalDays  : 0;
                                double tradesPerHour = totalHours > 0 ? r.getTotalTrades() / totalHours : 0;

                                results.add(new BB1mSweepResult(
                                    bbP, bbSd, rsiBuy, rsiSell, sl, tp, r,
                                    tradesPerDay, tradesPerHour));

                                count++;
                                if (count % 100 == 0) {
                                    log.info("BB 1m 스윕 진행: {}/{}", count, total);
                                }
                            }
                        }
                    }
                }
            }
        }

        printBB1mSweepResults(results, totalDays, totalHours);
        log.info("===== BB 1m 스윕 종료 ({} 조합) =====", results.size());
    }

    // ── BB 1m 스윕 결과 출력 ──────────────────────────────────────────
    private static void printBB1mSweepResults(List<BB1mSweepResult> results,
                                               double totalDays, double totalHours) {
        System.out.println("══════════════════════════════════════════════════════════════════════════════════════");
        System.out.println("               BB 평균회귀 1m 스윕 결과 (Top 20 — 수익률 기준)");
        System.out.println("══════════════════════════════════════════════════════════════════════════════════════");
        System.out.printf("%-4s %-4s %-7s %-7s %-5s %-5s │ %5s %6s %7s %7s %7s %6s %6s%n",
            "bbP", "bbSD", "rsiOS", "rsiOB", "SL×", "TP×",
            "거래수", "승률%", "건/시간", "건/일", "수익률%", "MDD%", "Sharpe");
        System.out.println("──────────────────────────────────────────────────────────────────────────────────────");

        results.stream()
            .filter(r -> r.result.getTotalTrades() >= 5)
            .sorted(Comparator.comparingDouble((BB1mSweepResult r) ->
                r.result.getTotalReturnPct()).reversed())
            .limit(20)
            .forEach(r -> {
                BacktestResult br = r.result;
                String flag = r.tradesPerHour >= 1.0 && br.getTotalReturnPct() > 0 ? " ★" : "";
                System.out.printf("%-4d %-4.1f %-7.0f %-7.0f %-5.1f %-5.1f │ %5d %6.1f %7.2f %7.2f %7.2f %6.2f %6.2f%s%n",
                    r.bbPeriod, r.bbStdDev, r.rsiOversold, r.rsiOverbought, r.sl, r.tp,
                    br.getTotalTrades(), br.getWinRate(),
                    r.tradesPerHour, r.tradesPerDay,
                    br.getTotalReturnPct(), br.getMaxDrawdownPct(), br.getSharpeRatio(),
                    flag);
            });
        System.out.println("──────────────────────────────────────────────────────────────────────────────────────");
        System.out.println("★ = 시간당 1건 이상 AND 플러스 수익");

        System.out.println();
        System.out.println("══════════════════════════════════════════════════════════════════════════════════════");
        System.out.println("               Top 10 — 시간당 거래 빈도 기준 (최소 10건)");
        System.out.println("══════════════════════════════════════════════════════════════════════════════════════");
        System.out.printf("%-4s %-4s %-7s %-7s %-5s %-5s │ %5s %6s %7s %7s %7s %6s %6s%n",
            "bbP", "bbSD", "rsiOS", "rsiOB", "SL×", "TP×",
            "거래수", "승률%", "건/시간", "건/일", "수익률%", "MDD%", "Sharpe");
        System.out.println("──────────────────────────────────────────────────────────────────────────────────────");

        results.stream()
            .filter(r -> r.result.getTotalTrades() >= 10)
            .sorted(Comparator.comparingDouble((BB1mSweepResult r) ->
                r.tradesPerHour).reversed())
            .limit(10)
            .forEach(r -> {
                BacktestResult br = r.result;
                String flag = r.tradesPerHour >= 1.0 && br.getTotalReturnPct() > 0 ? " ★" : "";
                System.out.printf("%-4d %-4.1f %-7.0f %-7.0f %-5.1f %-5.1f │ %5d %6.1f %7.2f %7.2f %7.2f %6.2f %6.2f%s%n",
                    r.bbPeriod, r.bbStdDev, r.rsiOversold, r.rsiOverbought, r.sl, r.tp,
                    br.getTotalTrades(), br.getWinRate(),
                    r.tradesPerHour, r.tradesPerDay,
                    br.getTotalReturnPct(), br.getMaxDrawdownPct(), br.getSharpeRatio(),
                    flag);
            });
        System.out.println("──────────────────────────────────────────────────────────────────────────────────────");

        long hitTarget = results.stream()
            .filter(r -> r.tradesPerHour >= 1.0 && r.result.getTotalReturnPct() > 0)
            .count();
        System.out.printf("%n목표(시간당 1건+ AND 플러스 수익) 달성 조합: %d개 / 전체 %d개%n", hitTarget, results.size());
        System.out.printf("전체 기간: %.1f일 (%.0f시간)%n", totalDays, totalHours);
    }

    // ── BB 1m 스윕 결과 컨테이너 ──────────────────────────────────────
    private static class BB1mSweepResult {
        final int bbPeriod;
        final double bbStdDev, rsiOversold, rsiOverbought, sl, tp;
        final BacktestResult result;
        final double tradesPerDay, tradesPerHour;

        BB1mSweepResult(int bbPeriod, double bbStdDev, double rsiOversold, double rsiOverbought,
                        double sl, double tp, BacktestResult result,
                        double tradesPerDay, double tradesPerHour) {
            this.bbPeriod = bbPeriod;
            this.bbStdDev = bbStdDev;
            this.rsiOversold = rsiOversold;
            this.rsiOverbought = rsiOverbought;
            this.sl = sl;
            this.tp = tp;
            this.result = result;
            this.tradesPerDay = tradesPerDay;
            this.tradesPerHour = tradesPerHour;
        }
    }

    // ── BB 레버리지 비교 (1x ~ 10x) ──────────────────────────────────
    // ── BB 평균회귀 장기 백테스트 (90일, 과적합 검증) ────────────────────
    public static void runBBLongTermTest() {
        final String timeframe = "5m";
        final int candleCnt = 25_920;  // 5m × 25920 = 90일
        final int warmup    = 40;

        // 최선 파라미터 (5m 스윕 1위)
        final int    bbPeriod      = 10;
        final double bbStdDev      = 2.0;
        final double rsiOversold   = 35.0;
        final double rsiOverbought = 75.0;
        final double slMult        = 1.5;
        final double tpMult        = 3.0;

        int[] leverages = {1, 3, 5};

        log.info("===== BB 장기 백테스트 시작 (목표: 90일) =====");
        log.info("심볼={} 타임프레임={} 캔들수={}", SYMBOL, timeframe, candleCnt);

        TradingConfig config = TradingConfig.getInstance();
        List<Candle> candles = fetchCandles(config, timeframe, candleCnt);
        if (candles.isEmpty()) return;

        double totalDays = (candles.get(candles.size() - 1).getTimestamp()
            - candles.get(warmup).getTimestamp()) / (1000.0 * 60 * 60 * 24);

        log.info("캔들 {}개, 실제 백테스트 기간 {}일", candles.size(), String.format("%.1f", totalDays));

        System.out.printf("%n[BB 장기 백테스트] 파라미터: BB(%d,%.1f) rsiOS=%.0f rsiOB=%.0f SL=%.1f× TP=%.1f×  기간: %.1f일%n%n",
            bbPeriod, bbStdDev, rsiOversold, rsiOverbought, slMult, tpMult, totalDays);

        System.out.println("══════════════════════════════════════════════════════════════════════════════");
        System.out.println("                    BB 장기 백테스트 결과 (과적합 검증)");
        System.out.println("══════════════════════════════════════════════════════════════════════════════");
        System.out.printf("%-6s │ %5s %6s %8s %8s %7s %8s %6s%n",
            "레버리지", "거래수", "승률%", "수익률%", "최종잔고", "MDD%", "Sharpe", "건/일");
        System.out.println("──────────────────────────────────────────────────────────────────────────────");

        for (int lev : leverages) {
            TradingStrategy strategy = new BollingerBandReversionStrategy(
                bbPeriod, bbStdDev, rsiOversold, rsiOverbought, slMult, tpMult);
            Backtester bt = new Backtester(
                strategy, candles, INIT_BAL, FEE_PCT, warmup, SYMBOL, lev);
            BacktestResult r = bt.run();

            double tradesPerDay = totalDays > 0 ? r.getTotalTrades() / totalDays : 0;
            String flag = r.getTotalReturnPct() > 0 ? " ★" : "";

            System.out.printf("%3dx    │ %5d %6.1f %8.2f %8.0f %7.2f %8.3f %6.2f%s%n",
                lev,
                r.getTotalTrades(), r.getWinRate(),
                r.getTotalReturnPct(), r.getFinalBalance(),
                r.getMaxDrawdownPct(), r.getSharpeRatio(),
                tradesPerDay, flag);
        }

        System.out.println("──────────────────────────────────────────────────────────────────────────────");
        System.out.println("★ = 플러스 수익  |  단기(15일) 대비 수익률 일관성 확인");
        System.out.printf("초기 잔고: $%.0f  수수료(편도): %.2f%%  기간: %.1f일%n", INIT_BAL, FEE_PCT, totalDays);

        log.info("===== BB 장기 백테스트 종료 =====");
    }

    // ── 멀티코인 BB 전략 검증 (BTC 최적 파라미터 그대로 적용) ──────────
    public static void runMultiCoinComparison() {
        final String timeframe  = "5m";
        final int    candleCnt  = 17280;  // API 최대 ~30일
        final int    warmup     = 40;
        final int    leverage   = 10;

        final int    bbPeriod      = 10;
        final double bbStdDev      = 2.0;
        final double rsiOversold   = 20.0;
        final double rsiOverbought = 80.0;
        final double slMult        = 1.0;
        final double tpMult        = 2.5;

        List<String> symbols = List.of("BTCUSDT", "ETHUSDT", "SOLUSDT", "XRPUSDT");

        log.info("===== BB 멀티코인 비교 시작 =====");

        TradingConfig config = TradingConfig.getInstance();

        System.out.printf("%n[BB 멀티코인 비교] 파라미터: BB(%d,%.1f) rsiOS=%.0f rsiOB=%.0f SL=%.1f× TP=%.1f×  레버리지: %dx%n%n",
            bbPeriod, bbStdDev, rsiOversold, rsiOverbought, slMult, tpMult, leverage);
        System.out.println("══════════════════════════════════════════════════════════════════════════════");
        System.out.println("                       BB 멀티코인 백테스트 결과");
        System.out.println("══════════════════════════════════════════════════════════════════════════════");
        System.out.printf("%-10s │ %5s %6s %8s %8s %7s %8s %6s %5s%n",
            "심볼", "거래수", "승률%", "수익률%", "최종잔고", "MDD%", "Sharpe", "건/일", "기간");
        System.out.println("──────────────────────────────────────────────────────────────────────────────");

        for (String symbol : symbols) {
            List<Candle> candles = fetchCandles(config, symbol, timeframe, candleCnt);
            if (candles.isEmpty()) {
                System.out.printf("%-10s │ 캔들 수집 실패%n", symbol);
                continue;
            }

            double totalDays = (candles.get(candles.size() - 1).getTimestamp()
                - candles.get(warmup).getTimestamp()) / (1000.0 * 60 * 60 * 24);

            TradingStrategy strategy = new BollingerBandReversionStrategy(
                bbPeriod, bbStdDev, rsiOversold, rsiOverbought, slMult, tpMult);
            Backtester bt = new Backtester(strategy, candles, INIT_BAL, FEE_PCT, warmup, symbol, leverage);
            BacktestResult r = bt.run();

            double tradesPerDay = totalDays > 0 ? r.getTotalTrades() / totalDays : 0;
            String flag = r.getTotalReturnPct() > 0 ? " ★" : " ✗";

            System.out.printf("%-10s │ %5d %6.1f %8.2f %8.0f %7.2f %8.3f %6.2f %4.1f일%s%n",
                symbol,
                r.getTotalTrades(), r.getWinRate(),
                r.getTotalReturnPct(), r.getFinalBalance(),
                r.getMaxDrawdownPct(), r.getSharpeRatio(),
                tradesPerDay, totalDays, flag);
        }

        System.out.println("──────────────────────────────────────────────────────────────────────────────");
        System.out.println("★ = 플러스 수익  ✗ = 마이너스 수익");
        System.out.printf("초기 잔고: $%.0f  수수료(편도): %.2f%%  레버리지: %dx%n", INIT_BAL, FEE_PCT, leverage);

        log.info("===== BB 멀티코인 비교 종료 =====");
    }

    public static void runBBLeverageComparison() {
        final String timeframe = "5m";
         final int candleCnt = 17280;  // 5m × 17280 = 60일
        final int warmup = 40;

        // 최선 파라미터 (30일 스윕 최적값 — 과적합 해소: BB10/2.0, rsiOS=20, rsiOB=80, SL=1.0, TP=2.5)
        final int    bbPeriod      = 10;
        final double bbStdDev      = 2.0;
        final double rsiOversold   = 20.0;
        final double rsiOverbought = 80.0;
        final double slMult        = 1.0;
        final double tpMult        = 2.5;

        int[] leverages = {1, 2, 3, 5, 10};

        log.info("===== BB 레버리지 비교 시작 =====");
        log.info("심볼={} 타임프레임={} 캔들수={} 파라미터=BB({},{}) rsiOS={} rsiOB={} SL={}× TP={}×",
            SYMBOL, timeframe, candleCnt, bbPeriod, bbStdDev, rsiOversold, rsiOverbought, slMult, tpMult);

        TradingConfig config = TradingConfig.getInstance();
        List<Candle> candles = fetchCandles(config, timeframe, candleCnt);
        if (candles.isEmpty()) return;

        double totalDays = (candles.get(candles.size() - 1).getTimestamp()
            - candles.get(warmup).getTimestamp()) / (1000.0 * 60 * 60 * 24);

        log.info("캔들 {}개, 백테스트 기간 {}일", candles.size(), String.format("%.1f", totalDays));

        System.out.printf("%n[BB 레버리지 비교] 파라미터: BB(%d,%.1f) rsiOS=%.0f rsiOB=%.0f SL=%.1f× TP=%.1f×  기간: %.1f일%n%n",
            bbPeriod, bbStdDev, rsiOversold, rsiOverbought, slMult, tpMult, totalDays);

        System.out.println("══════════════════════════════════════════════════════════════════════════════");
        System.out.println("                       BB 레버리지별 백테스트 결과");
        System.out.println("══════════════════════════════════════════════════════════════════════════════");
        System.out.printf("%-6s │ %5s %6s %8s %8s %7s %8s %6s%n",
            "레버리지", "거래수", "승률%", "수익률%", "최종잔고", "MDD%", "Sharpe", "건/일");
        System.out.println("──────────────────────────────────────────────────────────────────────────────");

        for (int lev : leverages) {
            TradingStrategy strategy = new BollingerBandReversionStrategy(
                bbPeriod, bbStdDev, rsiOversold, rsiOverbought, slMult, tpMult);
            Backtester bt = new Backtester(
                strategy, candles, INIT_BAL, FEE_PCT, warmup, SYMBOL, lev);
            BacktestResult r = bt.run();

            double tradesPerDay = totalDays > 0 ? r.getTotalTrades() / totalDays : 0;
            String flag = r.getTotalReturnPct() > 0 ? " ★" : "";

            System.out.printf("%3dx    │ %5d %6.1f %8.2f %8.0f %7.2f %8.3f %6.2f%s%n",
                lev,
                r.getTotalTrades(), r.getWinRate(),
                r.getTotalReturnPct(), r.getFinalBalance(),
                r.getMaxDrawdownPct(), r.getSharpeRatio(),
                tradesPerDay, flag);
        }

        System.out.println("──────────────────────────────────────────────────────────────────────────────");
        System.out.println("★ = 플러스 수익");
        System.out.printf("초기 잔고: $%.0f  수수료(편도): %.2f%%  기간: %.1f일%n", INIT_BAL, FEE_PCT, totalDays);

        log.info("===== BB 레버리지 비교 종료 =====");
    }

    // ── BB 5m 장기 파라미터 재스윕 (30일 데이터 — 과적합 해소) ──────────
    public static void runBB5mLongSweep() {
        final String timeframe = "5m";
        final int candleCnt = 9_000;  // 5m × 9000 ≈ 31일 (API 최대)
        final int warmup = 40;

        log.info("===== BB 5m 장기 파라미터 재스윕 시작 (과적합 해소) =====");
        log.info("심볼={} 타임프레임={} 캔들수={}", SYMBOL, timeframe, candleCnt);

        TradingConfig config = TradingConfig.getInstance();
        List<Candle> candles = fetchCandles(config, timeframe, candleCnt);
        if (candles.isEmpty()) return;

        double totalDays = (candles.get(candles.size() - 1).getTimestamp()
            - candles.get(warmup).getTimestamp()) / (1000.0 * 60 * 60 * 24);

        log.info("캔들 {}개, 백테스트 기간 {}일", candles.size(), String.format("%.1f", totalDays));
        System.out.printf("%n[BB 5m 장기 재스윕] 기간: %.1f일  목표: 플러스 수익 + 승률 50%%+ + 하루 2건 이상%n%n",
            totalDays);

        // 짧은 구간에서 잘 맞은 파라미터 대신 핵심 구간 재탐색 (~800조합)
        // 과적합 회피: 더 넓은 BB 밴드, 더 극단적 RSI 필터
        int[]    bbPeriods      = {10, 15, 20};
        double[] bbStdDevs      = {2.0, 2.5, 3.0};
        double[] rsiOversolds   = {20, 25, 30};
        double[] rsiOverboughts = {70, 75, 80};
        double[] slMults        = {1.0, 1.5, 2.0};
        double[] tpMults        = {2.0, 2.5, 3.0, 4.0};

        List<BB1mSweepResult> results = new ArrayList<>();
        int total = bbPeriods.length * bbStdDevs.length * rsiOversolds.length
            * rsiOverboughts.length * slMults.length * tpMults.length;
        int count = 0;

        for (int bbP : bbPeriods) {
            for (double bbSd : bbStdDevs) {
                for (double rsiBuy : rsiOversolds) {
                    for (double rsiSell : rsiOverboughts) {
                        for (double sl : slMults) {
                            for (double tp : tpMults) {
                                if (tp <= sl) continue;

                                TradingStrategy strategy = new BollingerBandReversionStrategy(
                                    bbP, bbSd, rsiBuy, rsiSell, sl, tp);
                                Backtester bt = new Backtester(
                                    strategy, candles, INIT_BAL, FEE_PCT, warmup, SYMBOL);
                                BacktestResult r = bt.run();

                                double tradesPerDay = totalDays > 0
                                    ? r.getTotalTrades() / totalDays : 0;

                                results.add(new BB1mSweepResult(
                                    bbP, bbSd, rsiBuy, rsiSell, sl, tp, r,
                                    tradesPerDay, 0.0));

                                count++;
                                if (count % 200 == 0) {
                                    log.info("재스윕 진행: {}/{}", count, total);
                                }
                            }
                        }
                    }
                }
            }
        }

        printBB5mLongSweepResults(results, totalDays);
        log.info("===== BB 5m 장기 재스윕 종료 ({} 조합) =====", results.size());
    }

    // ── BB 5m 장기 스윕 결과 출력 ──────────────────────────────────────
    private static void printBB5mLongSweepResults(List<BB1mSweepResult> results, double totalDays) {
        System.out.println("══════════════════════════════════════════════════════════════════════════════════════");
        System.out.printf("           BB 5m 장기 재스윕 결과 (기간: %.1f일) — Top 20 수익률 기준%n", totalDays);
        System.out.println("══════════════════════════════════════════════════════════════════════════════════════");
        System.out.printf("%-4s %-4s %-7s %-7s %-5s %-5s │ %5s %6s %7s %7s %6s %6s%n",
            "bbP", "bbSD", "rsiOS", "rsiOB", "SL×", "TP×",
            "거래수", "승률%", "건/일", "수익률%", "MDD%", "Sharpe");
        System.out.println("──────────────────────────────────────────────────────────────────────────────────────");

        results.stream()
            .filter(r -> r.result.getTotalTrades() >= (int)(totalDays * 1.5))  // 하루 1.5건 이상
            .filter(r -> r.result.getTotalReturnPct() > 0)                       // 플러스 수익
            .sorted(Comparator.comparingDouble((BB1mSweepResult r) ->
                r.result.getTotalReturnPct()).reversed())
            .limit(20)
            .forEach(r -> {
                BacktestResult br = r.result;
                String flag = r.tradesPerDay >= 2.0 && br.getWinRate() >= 50.0 ? " ★" : "";
                System.out.printf("%-4d %-4.1f %-7.0f %-7.0f %-5.1f %-5.1f │ %5d %6.1f %7.2f %7.2f %6.2f %6.2f%s%n",
                    r.bbPeriod, r.bbStdDev, r.rsiOversold, r.rsiOverbought, r.sl, r.tp,
                    br.getTotalTrades(), br.getWinRate(),
                    r.tradesPerDay,
                    br.getTotalReturnPct(), br.getMaxDrawdownPct(), br.getSharpeRatio(),
                    flag);
            });
        System.out.println("──────────────────────────────────────────────────────────────────────────────────────");
        System.out.println("★ = 하루 2건 이상 AND 승률 50%+  (필터: 플러스 수익 + 하루 1.5건 이상)");

        System.out.println();
        System.out.println("══════════════════════════════════════════════════════════════════════════════════════");
        System.out.println("           Top 10 — Sharpe 기준 (최소 거래 10건 + 플러스 수익)");
        System.out.println("══════════════════════════════════════════════════════════════════════════════════════");
        System.out.printf("%-4s %-4s %-7s %-7s %-5s %-5s │ %5s %6s %7s %7s %6s %6s%n",
            "bbP", "bbSD", "rsiOS", "rsiOB", "SL×", "TP×",
            "거래수", "승률%", "건/일", "수익률%", "MDD%", "Sharpe");
        System.out.println("──────────────────────────────────────────────────────────────────────────────────────");

        results.stream()
            .filter(r -> r.result.getTotalTrades() >= 10)
            .filter(r -> r.result.getTotalReturnPct() > 0)
            .sorted(Comparator.comparingDouble((BB1mSweepResult r) ->
                r.result.getSharpeRatio()).reversed())
            .limit(10)
            .forEach(r -> {
                BacktestResult br = r.result;
                String flag = r.tradesPerDay >= 2.0 && br.getWinRate() >= 50.0 ? " ★" : "";
                System.out.printf("%-4d %-4.1f %-7.0f %-7.0f %-5.1f %-5.1f │ %5d %6.1f %7.2f %7.2f %6.2f %6.2f%s%n",
                    r.bbPeriod, r.bbStdDev, r.rsiOversold, r.rsiOverbought, r.sl, r.tp,
                    br.getTotalTrades(), br.getWinRate(),
                    r.tradesPerDay,
                    br.getTotalReturnPct(), br.getMaxDrawdownPct(), br.getSharpeRatio(),
                    flag);
            });
        System.out.println("──────────────────────────────────────────────────────────────────────────────────────");

        long profitable = results.stream()
            .filter(r -> r.result.getTotalReturnPct() > 0)
            .count();
        long hitTarget = results.stream()
            .filter(r -> r.tradesPerDay >= 2.0
                && r.result.getTotalReturnPct() > 0
                && r.result.getWinRate() >= 50.0)
            .count();
        System.out.printf("%n플러스 수익 조합: %d개 / 전체 %d개 (%.1f%%)%n",
            profitable, results.size(), 100.0 * profitable / results.size());
        System.out.printf("목표(일 2건+ AND 플러스 수익 AND 승률50%%+) 달성: %d개%n", hitTarget);
        System.out.printf("전체 기간: %.1f일%n", totalDays);
    }

    private static String formatTs(long ts) {
        return DateTimeFormatter.ofPattern("MM-dd HH:mm")
            .withZone(ZoneId.of("Asia/Seoul"))
            .format(Instant.ofEpochMilli(ts));
    }
}