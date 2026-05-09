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
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public class DailyOptimizer {

    // 15m 캔들 30일치 = 30*24*4 = 2880 개 + 워밍업
    private static final int    CANDLE_COUNT     = 2920;
    private static final long   REPLY_TIMEOUT_MS = 30 * 60 * 1000L; // 30분
    private static final String CONFIG_PATH      = "src/main/resources/application.conf";

    private final TradingConfig    config;
    private final TelegramNotifier telegram;
    private final List<AutoTrader> traders;
    private final ScheduledExecutorService scheduler;

    // symbol → top3 proposals (순서 보존)
    private final AtomicReference<Map<String, List<OptimizationProposal>>> pendingProposals = new AtomicReference<>(null);
    private ScheduledFuture<?> timeoutFuture;

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

        scheduler.scheduleAtFixedRate(this::runAndPropose, initialDelay, 86400, TimeUnit.SECONDS);
        log.info("DailyOptimizer 스케줄 등록 — 다음 실행: {} ({}초 후)", noon, initialDelay);
    }

    /** 전체 코인 최적화 트리거 */
    public void triggerNow() {
        scheduler.submit(this::runAndPropose);
        log.info("DailyOptimizer 즉시 실행 요청 (전체)");
    }

    /** 특정 코인만 최적화 트리거 */
    public void triggerNow(String symbol) {
        scheduler.submit(() -> runAndProposeSymbol(symbol));
        log.info("DailyOptimizer 즉시 실행 요청 ({})", symbol);
    }

    /**
     * Telegram reply 처리. 대기 중인 제안이 있을 때만 동작.
     * 입력 형식: "1 2 0" (코인 순서대로, 0=유지)
     */
    public String handleReply(String text) {
        Map<String, List<OptimizationProposal>> proposals = pendingProposals.get();
        if (proposals == null) return null;

        String trimmed = text.trim();
        List<String> symbols = new ArrayList<>(proposals.keySet());
        int n = symbols.size();

        String[] parts = trimmed.split("[\\s,]+");
        if (parts.length != n) return null;
        for (String p : parts) {
            if (!p.matches("[0-3]")) return null;
        }

        cancelTimeout();
        pendingProposals.set(null);

        StringBuilder result = new StringBuilder("✅ <b>파라미터 적용 결과</b>\n\n");
        for (int i = 0; i < n; i++) {
            String sym = symbols.get(i);
            int choice = Integer.parseInt(parts[i]);
            if (choice == 0) {
                result.append(String.format("• <b>%s</b>: 현재 유지\n", sym));
                continue;
            }
            List<OptimizationProposal> top3 = proposals.get(sym);
            int idx = choice - 1;
            if (idx >= top3.size()) {
                result.append(String.format("• <b>%s</b>: ⚠️ 잘못된 번호\n", sym));
                continue;
            }
            OptimizationProposal selected = top3.get(idx);
            applyProposal(sym, selected);
            result.append(String.format(
                "• <b>%s</b>: rsiOS=%d / rsiOB=%d / SL=%.1f× / TP=%.1f×\n",
                sym, selected.rsiOS(), selected.rsiOB(), selected.slMult(), selected.tpMult()
            ));
        }
        result.append("\n봇이 다음 캔들부터 새 파라미터로 동작합니다.");
        return result.toString();
    }

    private void runAndPropose() {
        runAndProposeSymbols(getActiveSymbols());
    }

    private void runAndProposeSymbol(String symbol) {
        runAndProposeSymbols(List.of(symbol));
    }

    private void runAndProposeSymbols(List<String> symbols) {
        log.info("일일 파라미터 최적화 스윕 시작 — 대상: {}", symbols);
        telegram.sendRawMessage(String.format(
            "⏳ <b>일일 파라미터 최적화 중...</b>\n대상: %s\n잠시 기다려 주세요.", symbols));

        Map<String, List<OptimizationProposal>> allTop3 = new LinkedHashMap<>();
        for (String sym : symbols) {
            log.info("  스윕: {}", sym);
            try {
                List<OptimizationProposal> top3 = BacktestRunner.runSweepForOptimizer(sym, CANDLE_COUNT)
                    .stream()
                    .filter(p -> p.returnPct() > 0)
                    .sorted(Comparator.comparingDouble(OptimizationProposal::returnPct).reversed())
                    .limit(3)
                    .toList();
                allTop3.put(sym, top3);
            } catch (Exception e) {
                log.error("{} 스윕 중 오류", sym, e);
                telegram.sendRawMessage("⚠️ " + sym + " 스윕 중 오류: " + e.getMessage());
                allTop3.put(sym, List.of());
            }
        }

        boolean anyResult = allTop3.values().stream().anyMatch(l -> !l.isEmpty());
        if (!anyResult) {
            telegram.sendRawMessage("📊 <b>일일 최적화 결과</b>\n\n수익 플러스 조합을 찾지 못했습니다. 현재 파라미터를 유지합니다.");
            return;
        }

        pendingProposals.set(allTop3);
        scheduleTimeout();
        telegram.sendRawMessage(buildProposalMessage(allTop3));
    }

    private void scheduleTimeout() {
        cancelTimeout();
        timeoutFuture = scheduler.schedule(() -> {
            if (pendingProposals.getAndSet(null) != null) {
                log.info("최적화 제안 30분 무응답 — 자동 취소");
                telegram.sendRawMessage("⏰ 30분 무응답으로 최적화 제안이 취소되었습니다. 현재 파라미터를 유지합니다.");
            }
        }, 30, TimeUnit.MINUTES);
    }

    private void cancelTimeout() {
        if (timeoutFuture != null && !timeoutFuture.isDone()) {
            timeoutFuture.cancel(false);
        }
    }

    private String buildProposalMessage(Map<String, List<OptimizationProposal>> allTop3) {
        LocalDate today = LocalDate.now();
        LocalDate from  = today.minusDays(30);
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MM-dd");

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
            "📊 <b>일일 최적화 리포트</b> (15m, 10x)\n기간: %s ~ %s\n\n",
            from.format(fmt), today.format(fmt)
        ));

        List<String> symbols = new ArrayList<>(allTop3.keySet());
        String[] numbers = {"1️⃣", "2️⃣", "3️⃣"};

        for (String sym : symbols) {
            SymbolConfig sc = config.getSymbolConfigs().computeIfAbsent(sym, SymbolConfig::defaults);
            sb.append(String.format(
                "<b>[%s]</b> 현재: rsiOS=%d / rsiOB=%d / SL=%.1f× / TP=%.1f×\n",
                sym, sc.getRsiOversold(), sc.getRsiOverbought(), sc.getSlMult(), sc.getTpMult()
            ));

            List<OptimizationProposal> top3 = allTop3.get(sym);
            if (top3.isEmpty()) {
                sb.append("  ⚠️ 수익 조합 없음\n\n");
                continue;
            }
            for (int i = 0; i < top3.size(); i++) {
                OptimizationProposal p = top3.get(i);
                sb.append(String.format(
                    "  %s rsiOS=%d/rsiOB=%d SL=%.1f TP=%.1f → %+.0f%%, WR %.0f%%, MDD %.0f%%\n",
                    numbers[i], p.rsiOS(), p.rsiOB(), p.slMult(), p.tpMult(),
                    p.returnPct(), p.winRate(), p.mdd()
                ));
            }
            sb.append("\n");
        }

        sb.append("0️⃣ = 유지\n");

        List<String> shortNames = symbols.stream().map(s -> s.replace("USDT", "")).toList();
        if (symbols.size() == 1) {
            sb.append("\n번호를 입력해 주세요.");
        } else {
            sb.append(String.format(
                "\n%s 순서로 번호 입력\n예) \"1 2 0\"",
                String.join(" / ", shortNames)
            ));
        }
        sb.append("\n⏰ 30분 내 응답이 없으면 자동 취소됩니다.");
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
            content = content.replaceAll("  " + symbol + " \\{[^}]+\\}", newLine);
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