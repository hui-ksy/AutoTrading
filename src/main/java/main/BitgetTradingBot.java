package main;

import lombok.extern.slf4j.Slf4j;
import main.account.LiveAccountBalanceProvider;
import main.api.bitget.BitgetFuturesApiClient;
import main.api.bitget.PaperTradingClient;
import main.core.trading.AutoTrader;
import main.core.trading.TradeRecorder;
import main.core.trading.TradingBotOrchestrator;
import main.job.*;
import main.model.config.TradingConfig;
import main.model.config.TradingMode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class BitgetTradingBot {

    private static TelegramNotifier telegram;
    private static TradingConfig config;

    private static final LiveAccountBalanceProvider balanceProvider = new LiveAccountBalanceProvider();

    private static TradingBotOrchestrator orchestrator;
    private static final AtomicBoolean botRunning = new AtomicBoolean(true);
    private static final AtomicBoolean gracefulShutdown = new AtomicBoolean(false);
    private static final AtomicBoolean directionFilterEnabled = new AtomicBoolean(true);

    private static DailyOptimizer dailyOptimizer;
    private static CoinAdder coinAdder;

    public static void main(String[] args) {
        log.info("===========================================");
        log.info("Bitget 자동매매 트레이딩봇 시작");
        log.info("===========================================");

        try {
            config = TradingConfig.getInstance();

            telegram = new TelegramNotifier(
                    config.getTelegramBotToken(),
                    config.getTelegramChatId(),
                    config.isTelegramEnabled()
            );

            TradeRecorder recorder = new TradeRecorder(config, balanceProvider, telegram);
            orchestrator = new TradingBotOrchestrator(
                    config, balanceProvider, telegram, directionFilterEnabled, recorder);

            TelegramCommandHandler cmdHandler = new TelegramCommandHandler(
                    botRunning, gracefulShutdown, directionFilterEnabled,
                    config, balanceProvider, orchestrator.getTraders(),
                    () -> {
                        botRunning.set(false);
                        orchestrator.getTraders().forEach(AutoTrader::stopBot);
                    },
                    () -> {
                        botRunning.set(true);
                        orchestrator.getTraders().forEach(AutoTrader::startBot);
                    }
            );
            telegram.setCommandHandler(cmdHandler);
            telegram.sendStartupMessage();

            dailyOptimizer = new DailyOptimizer(config, telegram, orchestrator.getTraders());
            coinAdder = new CoinAdder(config, telegram, orchestrator::addTrader);
            cmdHandler.setDailyOptimizer(dailyOptimizer);
            cmdHandler.setCoinAdder(coinAdder);

            if (config.getMode() == TradingMode.PAPER) {
                BitgetFuturesApiClient futuresApiClientForMarketData = new BitgetFuturesApiClient(
                        config.getApiKey(), config.getSecretKey(), config.getPassphrase(),
                        config.getProductType(), config.getMarginMode()
                );
                PaperTradingClient globalPaperClient = new PaperTradingClient(futuresApiClientForMarketData, config.getInitialBalance(), config.getFeeRatePercent());
                orchestrator.setPaperClient(globalPaperClient);
                recorder.setInitialBalance(config.getInitialBalance());
            }

            List<String> pairs = (config.getTradingPairs() != null && !config.getTradingPairs().isEmpty())
                    ? config.getTradingPairs()
                    : List.of(config.getTradingPair());

            for (String pair : pairs) {
                orchestrator.addTrader(pair);
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                }
            }

            orchestrator.getTraders().forEach(AutoTrader::start);

            if (config.getMode() == TradingMode.LIVE) {
                orchestrator.startPositionReconciliation();
                orchestrator.startBalanceMonitor();
            }

            orchestrator.startSummaryLogging();
            dailyOptimizer.start();
            startHeartbeatThread(config.getHealthcheckPingUrl());

            WindowsPowerManager.preventSleep();

            log.info("===========================================");
            log.info("현재 잔액: ${}", String.format("%.2f", recorder.calculateStats().getCurrentBalance()));
            log.info("트레이딩봇 가동 중... ({}개 페어)", orchestrator.getTraders().size());
            log.info("===========================================");

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("트레이딩봇 종료 신호 수신...");
                if (telegram != null && !gracefulShutdown.get()) {
                    telegram.notifyCrash("예기치 않은 종료 (재시작 대기 중)");
                }
                orchestrator.getTraders().forEach(AutoTrader::stop);
                if (dailyOptimizer != null) dailyOptimizer.shutdown();
                if (coinAdder != null) coinAdder.shutdown();

                WindowsPowerManager.allowSleep();

                if (telegram != null) {
                    telegram.shutdown();
                }
            }));

        } catch (Exception e) {
            log.error("트레이딩봇 실행 중 심각한 오류 발생", e);
            if (telegram != null) {
                telegram.notifyCrash("심각한 오류: " + e.getMessage());
            }
            System.exit(1);
        }
    }


    private static void startHeartbeatThread(String pingUrl) {
        if (pingUrl == null || pingUrl.isBlank()) return;

        HttpClient httpClient = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(pingUrl)).GET().build();

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            try {
                httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            } catch (Exception e) {
                log.warn("Healthcheck ping 실패: {}", e.getMessage());
            }
        }, 0, 5, TimeUnit.MINUTES);

        log.info("Healthcheck 하트비트 시작 (5분 간격)");
    }
}
