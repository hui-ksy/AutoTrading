package main.job;

import lombok.extern.slf4j.Slf4j;
import main.backtest.BacktestRunner;
import main.model.OptimizationProposal;
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
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public class DailyOptimizer {

    // 15m 캔들 30일치 = 30*24*4 = 2880 개 + 워밍업
    private static final int    CANDLE_COUNT = 2920;
    private static final String SYMBOL       = "PEPEUSDT";
    private static final String CONFIG_PATH  = "src/main/resources/application.conf";

    private final TradingConfig   config;
    private final TelegramNotifier telegram;
    private final ScheduledExecutorService scheduler;
    private final AtomicReference<List<OptimizationProposal>> pendingProposals = new AtomicReference<>(null);

    public DailyOptimizer(TradingConfig config, TelegramNotifier telegram) {
        this.config   = config;
        this.telegram = telegram;
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

    public void triggerNow() {
        scheduler.submit(this::runAndPropose);
        log.info("DailyOptimizer 즉시 실행 요청");
    }

    /**
     * Telegram reply 처리. 대기 중인 제안이 있을 때만 동작.
     * @return 처리 응답 메시지, 또는 null (기존 명령어 핸들러에 위임)
     */
    public String handleReply(String text) {
        List<OptimizationProposal> proposals = pendingProposals.get();
        if (proposals == null) return null;

        String trimmed = text.trim();
        if (!trimmed.matches("[0-3]")) return null;

        int choice = Integer.parseInt(trimmed);
        pendingProposals.set(null);

        if (choice == 0) {
            return "✅ 현재 파라미터를 유지합니다.";
        }

        int idx = choice - 1;
        if (idx >= proposals.size()) {
            return "⚠️ 잘못된 번호입니다.";
        }

        OptimizationProposal selected = proposals.get(idx);
        applyProposal(selected);

        return String.format(
            "✅ %d번 제안이 적용되었습니다.\n" +
            "rsiOS=%d / rsiOB=%d / SL=%.1f× / TP=%.1f×\n" +
            "봇이 다음 캔들부터 새 파라미터로 동작합니다.",
            choice,
            selected.rsiOS(), selected.rsiOB(),
            selected.slMult(), selected.tpMult()
        );
    }

    private void runAndPropose() {
        log.info("일일 파라미터 최적화 스윕 시작 ({}, {} 캔들)", SYMBOL, CANDLE_COUNT);
        telegram.sendRawMessage("⏳ <b>일일 파라미터 최적화 중...</b>\n잠시 기다려 주세요.");

        List<OptimizationProposal> all;
        try {
            all = BacktestRunner.runSweepForOptimizer(SYMBOL, CANDLE_COUNT);
        } catch (Exception e) {
            log.error("스윕 실행 중 오류", e);
            telegram.sendRawMessage("⚠️ 파라미터 스윕 중 오류가 발생했습니다: " + e.getMessage());
            return;
        }

        List<OptimizationProposal> top3 = all.stream()
            .filter(p -> p.returnPct() > 0)
            .sorted(Comparator.comparingDouble(OptimizationProposal::returnPct).reversed())
            .limit(3)
            .toList();

        if (top3.isEmpty()) {
            telegram.sendRawMessage("📊 <b>일일 최적화 결과</b>\n\n수익 플러스 조합을 찾지 못했습니다. 현재 파라미터를 유지합니다.");
            return;
        }

        pendingProposals.set(top3);
        telegram.sendRawMessage(buildProposalMessage(top3));
    }

    private String buildProposalMessage(List<OptimizationProposal> top3) {
        LocalDate today = LocalDate.now();
        LocalDate from  = today.minusDays(30);
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MM-dd");

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
            "📊 <b>일일 최적화 리포트</b> (%s 15m, 10x)\n" +
            "기간: %s ~ %s\n\n" +
            "<b>현재 설정:</b> rsiOS=%.0f / rsiOB=%.0f / SL=%.1f× / TP=%.1f×\n\n",
            SYMBOL,
            from.format(fmt), today.format(fmt),
            config.getBollingerBandsRsiOversold(), config.getBollingerBandsRsiOverbought(),
            config.getAtrSlMultiplier(), config.getAtrTpMultiplier()
        ));

        String[] numbers = {"1️⃣", "2️⃣", "3️⃣"};
        for (int i = 0; i < top3.size(); i++) {
            OptimizationProposal p = top3.get(i);
            sb.append(String.format(
                "%s rsiOS=%d / rsiOB=%d / SL=%.1f / TP=%.1f → %+.0f%%, WR %.0f%%, MDD %.0f%%\n",
                numbers[i], p.rsiOS(), p.rsiOB(), p.slMult(), p.tpMult(),
                p.returnPct(), p.winRate(), p.mdd()
            ));
        }

        sb.append("0️⃣ 현재 유지\n\n");
        sb.append("번호를 입력해 주세요.");
        return sb.toString();
    }

    private void applyProposal(OptimizationProposal p) {
        config.setBollingerBandsRsiOversold(p.rsiOS());
        config.setBollingerBandsRsiOverbought(p.rsiOB());
        config.setAtrSlMultiplier(p.slMult());
        config.setAtrTpMultiplier(p.tpMult());
        log.info("파라미터 인메모리 적용: rsiOS={}, rsiOB={}, SL={}, TP={}",
            p.rsiOS(), p.rsiOB(), p.slMult(), p.tpMult());
        updateConfigFile(p);
    }

    private void updateConfigFile(OptimizationProposal p) {
        Path path = Paths.get(CONFIG_PATH);
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            content = content.replaceAll("rsiOversold\\s*=\\s*[\\d.]+",
                "rsiOversold = " + p.rsiOS());
            content = content.replaceAll("rsiOverbought\\s*=\\s*[\\d.]+",
                "rsiOverbought = " + p.rsiOB());
            content = content.replaceAll("atrSlMultiplier\\s*=\\s*[\\d.]+",
                "atrSlMultiplier = " + p.slMult());
            content = content.replaceAll("atrTpMultiplier\\s*=\\s*[\\d.]+",
                "atrTpMultiplier = " + p.tpMult());
            Files.writeString(path, content, StandardCharsets.UTF_8);
            log.info("application.conf 업데이트 완료");
        } catch (IOException e) {
            log.error("application.conf 파일 업데이트 실패: {}", e.getMessage());
            telegram.sendRawMessage("⚠️ config 파일 업데이트 실패: " + e.getMessage());
        }
    }

    public void shutdown() {
        scheduler.shutdown();
    }
}