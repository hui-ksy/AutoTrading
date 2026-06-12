package main.job;

import lombok.extern.slf4j.Slf4j;
import main.backtest.BacktestRunner;
import main.model.OptimizationProposal;
import main.model.SymbolConfig;
import main.model.TradingConfig;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import main.util.ConfigFileUpdater;

/** /add 명령어 처리: 백테스트 스윕 → 최적 파라미터 선택 → config 업데이트 → 봇 시작. */
@Slf4j
public class CoinAdder {

    private static final int CANDLE_COUNT = 2920;

    private final TradingConfig    config;
    private final TelegramNotifier telegram;
    private final Consumer<String> traderStarter;
    private final ExecutorService  executor;

    public CoinAdder(TradingConfig config, TelegramNotifier telegram, Consumer<String> traderStarter) {
        this.config        = config;
        this.telegram      = telegram;
        this.traderStarter = traderStarter;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "coin-adder");
            t.setDaemon(true);
            return t;
        });
    }

    public void triggerAdd(String symbol) {
        executor.submit(() -> runAdd(symbol));
        log.info("코인 추가 요청: {}", symbol);
    }

    private void runAdd(String symbol) {
        List<String> pairs = config.getTradingPairs();
        if (pairs != null && pairs.contains(symbol)) {
            telegram.sendRawMessage("⚠️ " + symbol + "는 이미 트레이딩 중입니다.");
            return;
        }

        telegram.sendRawMessage(String.format("⏳ <b>%s 추가 백테스트 중...</b>\n잠시 기다려 주세요.", symbol));
        log.info("코인 추가 스윕 시작: {}", symbol);

        try {
            List<OptimizationProposal> results = BacktestRunner.runSweepForOptimizer(symbol, CANDLE_COUNT)
                .stream()
                .filter(p -> p.returnPct() > 0)
                .sorted(Comparator.comparingDouble(OptimizationProposal::returnPct).reversed())
                .toList();

            if (results.isEmpty()) {
                telegram.sendRawMessage(String.format(
                    "⚠️ <b>%s 추가 실패</b>\n수익 플러스 파라미터 조합을 찾지 못했습니다.", symbol));
                return;
            }

            OptimizationProposal best = results.get(0);
            updateInMemoryConfig(symbol, best);
            updateConfigFile(symbol, best);
            traderStarter.accept(symbol);

            telegram.sendRawMessage(String.format(
                "✅ <b>%s 추가 완료</b>\n" +
                "rsiOS=%d / rsiOB=%d / SL=%.1f× / TP=%.1f× / BBW=%s\n" +
                "수익: %+.0f%%, WR: %.0f%%, MDD: %.0f%%\n" +
                "봇이 즉시 시작됩니다.",
                symbol, best.rsiOS(), best.rsiOB(), best.slMult(), best.tpMult(),
                best.bbWidthMult() == 0.0 ? "OFF" : String.format("%.1f×", best.bbWidthMult()),
                best.returnPct(), best.winRate(), best.mdd()));
        } catch (Exception e) {
            log.error("{} 코인 추가 중 오류", symbol, e);
            telegram.sendRawMessage("⚠️ " + symbol + " 추가 오류: " + e.getMessage());
        }
    }

    private void updateInMemoryConfig(String symbol, OptimizationProposal best) {
        SymbolConfig symbolConfig = SymbolConfig.defaults(symbol);
        symbolConfig.setRsiOversold(best.rsiOS());
        symbolConfig.setRsiOverbought(best.rsiOB());
        symbolConfig.setSlMult(best.slMult());
        symbolConfig.setTpMult(best.tpMult());
        symbolConfig.setBbWidthMult(best.bbWidthMult());
        config.getSymbolConfigs().put(symbol, symbolConfig);

        List<String> pairs = config.getTradingPairs();
        if (pairs != null && !pairs.contains(symbol)) {
            pairs.add(symbol);
        }
        log.info("{} 인메모리 설정 추가 완료", symbol);
    }

    private void updateConfigFile(String symbol, OptimizationProposal best) {
        try {
            String symLine = ConfigFileUpdater.formatSymbolLine(
                symbol, best.rsiOS(), best.rsiOB(), best.slMult(), best.tpMult(), best.bbWidthMult());

            if (!ConfigFileUpdater.insertIntoSymbolsBlock(symLine)) {
                log.warn("symbols 블록을 찾지 못했습니다 — {} 라인 수동 추가 필요", symbol);
                telegram.sendRawMessage("⚠️ symbols 블록 수정 실패 — " + symbol + " 수동 추가 필요");
            }

            if (!ConfigFileUpdater.addToPairsArray(symbol)) {
                log.warn("pairs 배열을 찾지 못했습니다 — {} 수동 추가 필요", symbol);
                telegram.sendRawMessage("⚠️ pairs 배열 수정 실패 — " + symbol + " 수동 추가 필요");
            }

            log.info("application.conf {} 추가 완료", symbol);
        } catch (IOException e) {
            log.error("application.conf 업데이트 실패 ({}): {}", symbol, e.getMessage());
            telegram.sendRawMessage("⚠️ " + symbol + " config 업데이트 실패: " + e.getMessage());
        }
    }

    public void shutdown() {
        executor.shutdown();
    }
}