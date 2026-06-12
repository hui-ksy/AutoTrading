package main;

import lombok.extern.slf4j.Slf4j;
import main.bitget.BitgetFuturesApiClient;
import main.bitget.PaperTradingClient;
import main.bitget.TradeClient;
import main.job.CoinAdder;
import main.job.DailyOptimizer;
import main.job.TelegramCommandHandler;
import main.job.TelegramNotifier;
import main.job.WindowsPowerManager;
import main.account.LiveAccountBalanceProvider;
import main.model.*;
import main.strategy.StrategyFactory;
import main.strategy.TradingStrategy;
import main.util.PriceFormatter;

import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
public class BitgetTradingBot {

    private static final List<Trade> tradeHistory = new ArrayList<>();
    private static TelegramNotifier telegram;
    private static TradingConfig config;
    private static double initialBalance;

    private static final LiveAccountBalanceProvider balanceProvider = new LiveAccountBalanceProvider();

    private static List<AutoTrader> autoTraders = new ArrayList<>();
    private static final AtomicBoolean botRunning = new AtomicBoolean(true);
    private static final AtomicBoolean gracefulShutdown = new AtomicBoolean(false);
    private static final AtomicBoolean directionFilterEnabled = new AtomicBoolean(true);

    private static PaperTradingClient globalPaperClient;
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

            TelegramCommandHandler cmdHandler = new TelegramCommandHandler(
                    botRunning, gracefulShutdown, directionFilterEnabled,
                    config, balanceProvider, autoTraders,
                    () -> { botRunning.set(false); autoTraders.forEach(AutoTrader::stopBot); },
                    () -> { botRunning.set(true); autoTraders.forEach(AutoTrader::startBot); }
            );
            telegram.setCommandHandler(cmdHandler);
            telegram.sendStartupMessage();
            dailyOptimizer = new DailyOptimizer(config, telegram, autoTraders);
            coinAdder = new CoinAdder(config, telegram, BitgetTradingBot::addTraderInternal);
            cmdHandler.setDailyOptimizer(dailyOptimizer);
            cmdHandler.setCoinAdder(coinAdder);

            if (config.getMode() == TradingMode.PAPER) {
                BitgetFuturesApiClient futuresApiClientForMarketData = new BitgetFuturesApiClient(
                        config.getApiKey(), config.getSecretKey(), config.getPassphrase(),
                        config.getProductType(), config.getMarginMode()
                );
                globalPaperClient = new PaperTradingClient(futuresApiClientForMarketData, config.getInitialBalance(), config.getFeeRatePercent());
                initialBalance = config.getInitialBalance();
            }

            List<String> pairs = (config.getTradingPairs() != null && !config.getTradingPairs().isEmpty())
                    ? config.getTradingPairs()
                    : List.of(config.getTradingPair());

            autoTraders.clear();
            for (String pair : pairs) {
                addTraderInternal(pair);
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                }
            }

            for (AutoTrader trader : autoTraders) {
                trader.start();
            }

            if (config.getMode() == TradingMode.LIVE) {
                startPositionReconciliationThread(autoTraders, telegram);
                startBalanceMonitorThread(autoTraders);
            }

            startSummaryLoggingThread(autoTraders, globalPaperClient);
            dailyOptimizer.start();
            startHeartbeatThread(config.getHealthcheckPingUrl());

            WindowsPowerManager.preventSleep();

            log.info("===========================================");
            log.info("현재 잔액: ${}", String.format("%.2f", initialBalance));
            log.info("트레이딩봇 가동 중... ({}개 페어)", autoTraders.size());
            log.info("===========================================");

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("트레이딩봇 종료 신호 수신...");
                if (telegram != null && !gracefulShutdown.get()) {
                    telegram.notifyCrash("예기치 않은 종료 (재시작 대기 중)");
                }
                autoTraders.forEach(AutoTrader::stop);
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

    private static boolean isDirectionBlocked(String thisPair, Signal.Action action) {
        if (!directionFilterEnabled.get()) return false;
        for (AutoTrader trader : autoTraders) {
            if (trader.getPair().equalsIgnoreCase(thisPair)) continue;
            Position pos = trader.getCurrentPosition();
            if (pos == null) continue;
            String side = pos.getSide();
            if ("BUY".equals(side) && action == Signal.Action.SHORT) return true;
            if ("SHORT".equals(side) && action == Signal.Action.BUY) return true;
        }
        return false;
    }

    private static void addTraderInternal(String pair) {
        TradeClient apiClient = (config.getMode() == TradingMode.LIVE) ? createApiClient(config, pair) : null;
        if (apiClient != null && initialBalance == 0) {
            initialBalance = ((BitgetFuturesApiClient) apiClient).getAccountEquity("USDT");
        }
        main.model.SymbolConfig sc = config.getSymbolConfigs().computeIfAbsent(pair, main.model.SymbolConfig::defaults);
        TradingStrategy strategy = StrategyFactory.createStrategy(config, sc);
        AutoTrader trader = new AutoTrader(apiClient, globalPaperClient, strategy, config, pair, telegram,
                BitgetTradingBot::recordAndReportTrade, BitgetTradingBot::isDirectionBlocked, balanceProvider);
        autoTraders.add(trader);
    }

    public static synchronized void recordAndReportTrade(Position closedPosition, double exitPrice, double pnlFromApi, double feeFromApi) {
        Trade trade = new Trade();
        trade.setSymbol(closedPosition.getSymbol());
        trade.setSide(closedPosition.getSide());
        trade.setEntryPrice(closedPosition.getEntryPrice());
        trade.setExitPrice(exitPrice);
        trade.setQuantity(closedPosition.getQuantity());

        double netPnl;

        if (pnlFromApi != 0) {
            netPnl = pnlFromApi;
            trade.setFee(feeFromApi);
        } else {
            double entryValue = trade.getEntryPrice() * trade.getQuantity();
            double exitValue = trade.getExitPrice() * trade.getQuantity();
            double fee = (entryValue + exitValue) * (config.getFeeRatePercent() / 100.0);
            trade.setFee(fee);

            double grossPnl = ("BUY".equals(trade.getSide())) ? (exitValue - entryValue) : (entryValue - exitValue);
            netPnl = grossPnl - fee;
        }

        trade.setProfit(netPnl);

        double marginUsed = (trade.getEntryPrice() * trade.getQuantity()) / config.getLeverage();
        double pnlPercent = (marginUsed > 0) ? (netPnl / marginUsed) * 100 : 0;
        trade.setProfitPercent(pnlPercent);

        tradeHistory.add(trade);

        CumulativeStats stats = calculateCumulativeStats();
        logAndNotify(trade, stats);
    }

    private static CumulativeStats calculateCumulativeStats() {
        int wins = 0;
        double totalProfit = 0;
        for (Trade trade : tradeHistory) {
            if (trade.getProfit() > 0) wins++;
            totalProfit += trade.getProfit();
        }

        int totalTrades = tradeHistory.size();
        int losses = totalTrades - wins;
        double winRate = (totalTrades > 0) ? (double) wins / totalTrades * 100 : 0;

        // LIVE 모드에서는 펀딩비 등 비거래 비용 반영을 위해 거래소 실제 잔고 사용
        double equity = balanceProvider.getTotalEquity();
        double currentBalance = (config.getMode() == TradingMode.LIVE && equity > 0)
                ? equity
                : initialBalance + totalProfit;
        double totalReturnPercent = (initialBalance > 0) ? (currentBalance / initialBalance - 1) * 100 : 0;

        return new CumulativeStats(totalTrades, wins, losses, winRate, totalProfit, totalReturnPercent, currentBalance);
    }

    private static void logAndNotify(Trade trade, CumulativeStats stats) {
        log.info("[거래 종료] {}, 손익: {}${} ({}%), 수수료: ${}",
                trade.getSymbol(), (trade.getProfit() >= 0 ? "+" : ""), String.format("%.2f", trade.getProfit()),
                String.format("%.2f", trade.getProfitPercent()), String.format("%.2f", trade.getFee()));
        log.info("[누적 통계] 총 거래 {}, 승률 {}%, 총 손익 {}${}",
                stats.getTotalTrades(), String.format("%.2f", stats.getWinRate()),
                (stats.getTotalProfit() >= 0 ? "+" : ""), String.format("%.2f", stats.getTotalProfit()));

        telegram.notifyExitSummary(trade, stats);
    }

    private static TradeClient createApiClient(TradingConfig config, String pair) {
        if (config.getTradingType() == TradingType.FUTURES) {
            BitgetFuturesApiClient client = new BitgetFuturesApiClient(
                    config.getApiKey(), config.getSecretKey(), config.getPassphrase(),
                    config.getProductType(), config.getMarginMode()
            );
            client.setMarginMode(pair, config.getMarginMode());

            setLeverageWithRetry(client, pair, config.getLeverage(), "long");
            setLeverageWithRetry(client, pair, config.getLeverage(), "short");

            return client;
        }
        return null;
    }

    private static void setLeverageWithRetry(BitgetFuturesApiClient client, String pair, int targetLeverage, String side) {
        int currentLeverage = targetLeverage;
        while (currentLeverage >= 1) {
            boolean success = client.setLeverage(pair, currentLeverage, side);
            if (success) {
                if (currentLeverage != targetLeverage) {
                    log.warn("[{}] {} 레버리지 조정됨: {}x -> {}x", pair, side, targetLeverage, currentLeverage);
                }
                return;
            }

            int nextLeverage = (int) (currentLeverage * 0.75);
            if (nextLeverage == currentLeverage) nextLeverage--;

            currentLeverage = nextLeverage;
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
            }
        }
        log.error("[{}] {} 레버리지 설정 최종 실패", pair, side);
    }

    private static void startBalanceMonitorThread(List<AutoTrader> traders) {
        if (traders.isEmpty()) return;

        ScheduledExecutorService balanceScheduler = Executors.newSingleThreadScheduledExecutor();
        balanceScheduler.scheduleAtFixedRate(() -> {
            try {
                TradeClient client = traders.get(0).getApiClient();
                if (client instanceof BitgetFuturesApiClient) {
                    BitgetFuturesApiClient futuresClient = (BitgetFuturesApiClient) client;
                    balanceProvider.update(futuresClient.getAccountEquity("USDT"), futuresClient.getAvailableBalance("USDT"));
                }
            } catch (Exception e) {
                log.error("잔고 조회 중 오류 발생", e);
            }
        }, 0, 5, TimeUnit.SECONDS);
    }

    private static void startPositionReconciliationThread(List<AutoTrader> traders, TelegramNotifier telegram) {
        ScheduledExecutorService reconciliationScheduler = Executors.newSingleThreadScheduledExecutor();
        reconciliationScheduler.scheduleAtFixedRate(() -> {
            try {
                Optional<AutoTrader> liveTraderOpt = traders.stream().filter(t -> t.getApiClient() != null).findFirst();
                if (liveTraderOpt.isEmpty()) return;

                BitgetFuturesApiClient sampleClient = (BitgetFuturesApiClient) liveTraderOpt.get().getApiClient();
                List<Position> exchangePositions = sampleClient.getAllPositions();
                Map<String, Position> exchangePositionMap = exchangePositions.stream()
                        .collect(Collectors.toMap(Position::getSymbol, p -> p));

                Map<String, AutoTrader> traderMap = traders.stream()
                        .collect(Collectors.toMap(AutoTrader::getPair, t -> t));

                for (AutoTrader trader : traders) {
                    if (trader.getCurrentPosition() != null && !exchangePositionMap.containsKey(trader.getPair())) {
                        trader.handleExternalClose();
                    }
                }

                for (Map.Entry<String, Position> entry : exchangePositionMap.entrySet()) {
                    String symbol = entry.getKey();
                    AutoTrader trader = traderMap.get(symbol);
                    if (trader != null && trader.getCurrentPosition() == null) {
                        log.warn("[동기화] {}에 미인수 포지션을 발견했습니다. 관리를 시작합니다.", symbol);
                        telegram.notifyReconciliationInfo(String.format("🕵️ %s의 미인수 포지션을 발견하여 관리를 시작합니다.", symbol));
                        trader.checkAndLoadExistingPosition();
                    }
                }
            } catch (Exception e) {
                log.error("[동기화] 포지션 동기화 중 오류 발생", e);
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    private static void startSummaryLoggingThread(List<AutoTrader> traders, PaperTradingClient paperClient) {
        ScheduledExecutorService summaryScheduler = Executors.newSingleThreadScheduledExecutor();
        summaryScheduler.scheduleAtFixedRate(() -> {
            List<AutoTrader> tradersWithPositions = traders.stream()
                    .filter(t -> t.getCurrentPosition() != null)
                    .collect(Collectors.toList());

            if (tradersWithPositions.isEmpty()) {
                return;
            }

            log.info("---------- 현재 거래 상황 요약 ----------");
            List<String> telegramSummaries = new ArrayList<>();

            for (AutoTrader trader : tradersWithPositions) {
                Position position = trader.getCurrentPosition();

                double currentPrice = (config.getMode() == TradingMode.LIVE)
                        ? trader.getApiClient().getTickerPrice(trader.getPair())
                        : paperClient.getTickerPrice(trader.getPair());

                String currentPriceStr;
                String pnlStr;

                if (currentPrice > 0) {
                    double entryPrice = position.getEntryPrice();
                    double pnlPercent = (currentPrice / entryPrice - 1) * 100;
                    if ("SHORT".equals(position.getSide())) {
                        pnlPercent *= -1;
                    }
                    pnlPercent *= config.getLeverage();

                    currentPriceStr = PriceFormatter.format(currentPrice);
                    pnlStr = String.format("%.2f%%", pnlPercent);
                } else {
                    currentPriceStr = "조회 실패";
                    pnlStr = "N/A";
                }

                String logMsg = String.format("[%s] %s | 진입가: %s | 현재가: %s | 미실현 손익: %s",
                        position.getSymbol(),
                        position.getSide(),
                        PriceFormatter.format(position.getEntryPrice()),
                        currentPriceStr,
                        pnlStr);

                log.info(logMsg);

                if (currentPrice > 0) {
                    String sideEmoji = "BUY".equals(position.getSide()) ? "🟢" : "🔴";
                    String pnlEmoji = Double.parseDouble(pnlStr.replace("%", "")) >= 0 ? "📈" : "📉";
                    String telegramMsg = String.format("%s <b>%s</b> %s\n" +
                                    "진입: $%s ➡️ 현재: $%s\n" +
                                    "%s <b>%s</b>",
                            sideEmoji, position.getSymbol(), position.getSide(),
                            PriceFormatter.format(position.getEntryPrice()), currentPriceStr,
                            pnlEmoji, pnlStr);
                    telegramSummaries.add(telegramMsg);
                }
            }

            CumulativeStats stats = calculateCumulativeStats();
            log.info("---------- 누적 통계 ----------");
            log.info("총 거래: {}, 승률: {}%, 총 손익: {}${}",
                    stats.getTotalTrades(),
                    String.format("%.2f", stats.getWinRate()),
                    (stats.getTotalProfit() >= 0 ? "+" : ""),
                    String.format("%.2f", stats.getTotalProfit())
            );
            log.info("------------------------------------");

            if (!telegramSummaries.isEmpty()) {
                telegram.notifyStatusSummary(telegramSummaries, stats);
            }

        }, 5, 5, TimeUnit.MINUTES);
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
