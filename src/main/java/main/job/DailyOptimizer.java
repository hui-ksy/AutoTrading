package main.job;

import lombok.extern.slf4j.Slf4j;
import main.AutoTrader;
import main.backtest.BacktestRunner;
import main.model.OptimizationProposal;
import main.model.SymbolConfig;
import main.model.TradingConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
public class DailyOptimizer {

    private static final int    CANDLE_COUNT = 2920;
    private static final String CONFIG_PATH  = "src/main/resources/application.conf";

    private final TradingConfig    config;
    private final TelegramNotifier telegram;
    private final List<AutoTrader> traders;
    private final ScheduledExecutorService scheduler;

    public DailyOptimizer(TradingConfig config, TelegramNotifier telegram, List<AutoTrader> traders) {
        this.config   = config;
        this.telegram = telegram;
        this.traders  = traders;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "daily-optimizer");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        LocalDateTime now  = LocalDateTime.now();
        LocalDateTime noon = now.toLocalDate().atTime(12, 0);
        if (!now.isBefore(noon)) noon = noon.plusDays(1);
        long initialDelay = Duration.between(now, noon).getSeconds();

        scheduler.scheduleAtFixedRate(this::runAndApply, initialDelay, 86400, TimeUnit.SECONDS);
        log.info("DailyOptimizer 스케줄 등록 — 다음 실행: {} ({}초 후)", noon, initialDelay);
    }

    public void triggerNow() {
        scheduler.submit(this::runAndApply);
        log.info("DailyOptimizer 즉시 실행 요청 (전체)");
    }

    public void triggerNow(String symbol) {
        scheduler.submit(() -> runAndApplySymbol(symbol));
        log.info("DailyOptimizer 즉시 실행 요청 ({})", symbol);
    }

    /** 더 이상 대기 중인 제안이 없으므로 항상 null 반환 */
    public String handleReply(String text) {
        return null;
    }

    private void runAndApply() {
        runAndApplySymbols(getActiveSymbols());
    }

    private void runAndApplySymbol(String symbol) {
        runAndApplySymbols(List.of(symbol));
    }

    private void runAndApplySymbols(List<String> symbols) {
        log.info("일일 파라미터 최적화 스윕 시작 — 대상: {}", symbols);
        telegram.sendRawMessage(String.format(
            "⏳ <b>일일 파라미터 최적화 중...</b>\n대상: %s\n잠시 기다려 주세요.", symbols));

        Map<String, OptimizationProposal> applied = new LinkedHashMap<>();
        Map<String, String>               skipped = new LinkedHashMap<>();

        for (String sym : symbols) {
            log.info("  스윕: {}", sym);
            try {
                Optional<OptimizationProposal> best = BacktestRunner.runSweepForOptimizer(sym, CANDLE_COUNT)
                    .stream()
                    .filter(p -> p.returnPct() > 0)
                    .max(Comparator.comparingDouble(OptimizationProposal::returnPct));

                if (best.isEmpty()) {
                    skipped.put(sym, "수익 플러스 조합 없음");
                    continue;
                }

                OptimizationProposal top = best.get();
                SymbolConfig sc = config.getSymbolConfigs().computeIfAbsent(sym, SymbolConfig::defaults);
                boolean unchanged = sc.getRsiOversold() == top.rsiOS()
                    && sc.getRsiOverbought() == top.rsiOB()
                    && Math.abs(sc.getSlMult() - top.slMult()) < 0.01
                    && Math.abs(sc.getTpMult() - top.tpMult()) < 0.01;

                if (unchanged) {
                    skipped.put(sym, "현재 값과 동일");
                    continue;
                }

                applyProposal(sym, top);
                applied.put(sym, top);

            } catch (Exception e) {
                log.error("{} 스윕 중 오류", sym, e);
                skipped.put(sym, "오류: " + e.getMessage());
            }
        }

        telegram.sendRawMessage(buildResultMessage(applied, skipped));
    }

    private String buildResultMessage(Map<String, OptimizationProposal> applied,
                                      Map<String, String> skipped) {
        LocalDate today = LocalDate.now();
        LocalDate from  = today.minusDays(30);
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MM-dd");

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
            "📊 <b>일일 최적화 완료</b> (15m, 10x)\n기간: %s ~ %s\n\n",
            from.format(fmt), today.format(fmt)));

        if (!applied.isEmpty()) {
            sb.append("✅ <b>적용된 파라미터</b>\n");
            applied.forEach((sym, p) -> sb.append(String.format(
                "• <b>%s</b>: rsiOS=%d / rsiOB=%d / SL=%.1f× / TP=%.1f× → %+.0f%%, WR %.0f%%, MDD %.0f%%\n",
                sym, p.rsiOS(), p.rsiOB(), p.slMult(), p.tpMult(),
                p.returnPct(), p.winRate(), p.mdd())));
            sb.append("\n");
        }

        if (!skipped.isEmpty()) {
            sb.append("ℹ️ <b>변경 없음</b>\n");
            skipped.forEach((sym, reason) ->
                sb.append(String.format("• <b>%s</b>: %s\n", sym, reason)));
        }

        sb.append("\n봇이 다음 캔들부터 새 파라미터로 동작합니다.");
        return sb.toString();
    }

    private void applyProposal(String symbol, OptimizationProposal p) {
        SymbolConfig sc = config.getSymbolConfigs().computeIfAbsent(symbol, SymbolConfig::defaults);
        sc.setRsiOversold(p.rsiOS());
        sc.setRsiOverbought(p.rsiOB());
        sc.setSlMult(p.slMult());
        sc.setTpMult(p.tpMult());

        log.info("{} 파라미터 적용: rsiOS={}, rsiOB={}, SL={}, TP={}",
            symbol, p.rsiOS(), p.rsiOB(), p.slMult(), p.tpMult());

        traders.forEach(AutoTrader::reloadStrategy);
        updateConfigFile(symbol, p);
    }

    private void updateConfigFile(String symbol, OptimizationProposal p) {
        Path path = Paths.get(CONFIG_PATH);
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            String newLine = String.format(
                "  %s { bbPeriod = 17, bbStdDev = 2.6, rsiOversold = %d, rsiOverbought = %d, slMult = %.1f, tpMult = %.1f }",
                symbol, p.rsiOS(), p.rsiOB(), p.slMult(), p.tpMult());
            String before = content;
            content = content.replaceAll("  " + symbol + "\\s+\\{[^}]+\\}", newLine);
            if (content.equals(before)) {
                if (before.contains(newLine)) {
                    log.info("application.conf {} 설정 유지 (변경 없음)", symbol);
                } else {
                    log.warn("application.conf {} 라인 업데이트 실패: 패턴 매칭 오류", symbol);
                    telegram.sendRawMessage("⚠️ " + symbol + " conf 저장 실패: 패턴 매칭 오류 — 수동 확인 필요");
                }
                return;
            }
            Files.writeString(path, content, StandardCharsets.UTF_8);
            log.info("application.conf {} 라인 업데이트 완료", symbol);
        } catch (IOException e) {
            log.error("application.conf 업데이트 실패 ({}): {}", symbol, e.getMessage());
            telegram.sendRawMessage("⚠️ " + symbol + " config 업데이트 실패: " + e.getMessage());
        }
    }

    private List<String> getActiveSymbols() {
        List<String> pairs = config.getTradingPairs();
        if (pairs != null && !pairs.isEmpty()) return pairs;
        return List.of("PEPEUSDT");
    }

    public void shutdown() {
        scheduler.shutdown();
    }
}
