package main;

import lombok.extern.slf4j.Slf4j;
import main.bitget.BitgetApiClient;
import main.bitget.BitgetFuturesApiClient;
import main.bitget.PaperTradingClient;
import main.bitget.TradeClient;
import main.job.TelegramNotifier;
import main.model.*;
import main.strategy.StrategyFactory;
import main.strategy.TradingStrategy;

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
    
    public static volatile double sharedTotalEquity = 0.0;
    public static volatile double sharedAvailableBalance = 0.0;
    
    private static List<AutoTrader> autoTraders;
    private static volatile boolean botRunning = true;
    
    private static PaperTradingClient globalPaperClient;

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
            
            telegram.setCommandHandler(command -> {
                if (!botRunning && !command.equals("/start") && !command.equals("/help")) {
                    return "봇이 현재 정지 상태입니다. /start 로 재시작하거나 /help 를 입력하세요.";
                }
                
                if (command.equals("/status")) return getStatusSummary();
                if (command.equals("/balance")) return getBalanceSummary();
                if (command.equals("/stop")) {
                    stopAllTraders();
                    return "🚨 모든 봇이 정지되었습니다. /start 로 재시작하세요.";
                }
                if (command.equals("/start")) {
                    startAllTraders();
                    return "✅ 모든 봇이 재시작되었습니다.";
                }
                if (command.equals("/close all")) {
                    closeAllPositions();
                    return "⚠️ 모든 포지션 시장가 청산 명령을 실행했습니다.";
                }
                if (command.equals("/help")) return getHelpMessage();
                
                if (command.equals("/long on")) {
                    config.setAllowLong(true);
                    return "✅ Long 진입이 활성화되었습니다.";
                }
                if (command.equals("/long off")) {
                    config.setAllowLong(false);
                    return "🛑 Long 진입이 비활성화되었습니다.";
                }
                if (command.equals("/short on")) {
                    config.setAllowShort(true);
                    return "✅ Short 진입이 활성화되었습니다.";
                }
                if (command.equals("/short off")) {
                    config.setAllowShort(false);
                    return "🛑 Short 진입이 비활성화되었습니다.";
                }
                
                // [추가] TP/SL 변경 명령어
                if (command.startsWith("/tp ")) {
                    try {
                        double tp = Double.parseDouble(command.substring("/tp ".length()).trim());
                        config.setTakeProfitPercent(tp);
                        return String.format("✅ 익절(TP) 비율이 %.2f%%로 변경되었습니다.", tp);
                    } catch (NumberFormatException e) {
                        return "❌ 잘못된 숫자 형식입니다. 예: /tp 0.5";
                    }
                }
                if (command.startsWith("/sl ")) {
                    try {
                        double sl = Double.parseDouble(command.substring("/sl ".length()).trim());
                        config.setStopLossPercent(sl);
                        return String.format("✅ 손절(SL) 비율이 %.2f%%로 변경되었습니다.", sl);
                    } catch (NumberFormatException e) {
                        return "❌ 잘못된 숫자 형식입니다. 예: /sl 0.3";
                    }
                }
                
                if (command.startsWith("/close ")) {
                    String symbol = command.substring("/close ".length()).trim().toUpperCase();
                    return closeSpecificPosition(symbol);
                }
                
                if (command.startsWith("/stop ")) {
                    String symbol = command.substring("/stop ".length()).trim().toUpperCase();
                    return stopSpecificTrader(symbol);
                }
                
                if (command.startsWith("/start ")) {
                    String symbol = command.substring("/start ".length()).trim().toUpperCase();
                    return startSpecificTrader(symbol);
                }
                
                if (command.startsWith("/add ")) {
                    String symbol = command.substring("/add ".length()).trim().toUpperCase();
                    return addTrader(symbol);
                }
                
                return "알 수 없는 명령어입니다. /help 를 입력하세요.";
            });
            telegram.sendStartupMessage();

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

            autoTraders = new ArrayList<>();
            for (String pair : pairs) {
                addTraderInternal(pair);
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            }
            
            for (AutoTrader trader : autoTraders) {
                trader.start();
            }

            if (config.getMode() == TradingMode.LIVE) {
                startPositionReconciliationThread(autoTraders, telegram);
                startBalanceMonitorThread(autoTraders);
            }
            
            startSummaryLoggingThread(autoTraders, globalPaperClient);

            log.info("===========================================");
            log.info("현재 잔액: ${}", String.format("%.2f", initialBalance));
            log.info("트레이딩봇 가동 중... ({}개 페어)", autoTraders.size());
            log.info("===========================================");

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                log.info("트레이딩봇 종료 신호 수신...");
                if (autoTraders != null) {
                    autoTraders.forEach(trader -> trader.stop());
                }
                if (telegram != null) {
                    telegram.shutdown();
                }
            }));

        } catch (Exception e) {
            log.error("트레이딩봇 실행 중 심각한 오류 발생", e);
            System.exit(1);
        }
    }
    
    private static void addTraderInternal(String pair) {
        TradeClient apiClient = (config.getMode() == TradingMode.LIVE) ? createApiClient(config, pair) : null;
        if (apiClient != null && initialBalance == 0) {
            initialBalance = ((BitgetFuturesApiClient) apiClient).getAccountEquity("USDT");
        }
        TradingStrategy strategy = StrategyFactory.createStrategy(config);
        AutoTrader trader = new AutoTrader(apiClient, globalPaperClient, strategy, config, pair, telegram, BitgetTradingBot::recordAndReportTrade);
        autoTraders.add(trader);
    }

    private static String addTrader(String symbol) {
        boolean exists = autoTraders.stream().anyMatch(t -> t.getPair().equalsIgnoreCase(symbol));
        if (exists) {
            return "⚠️ 이미 실행 중인 코인입니다: " + symbol;
        }
        
        try {
            addTraderInternal(symbol);
            AutoTrader newTrader = autoTraders.get(autoTraders.size() - 1);
            newTrader.start();
            return "✅ 새로운 봇이 추가되고 시작되었습니다: " + symbol;
        } catch (Exception e) {
            log.error("봇 추가 실패", e);
            return "❌ 봇 추가 실패: " + e.getMessage();
        }
    }
    
    private static String stopSpecificTrader(String symbol) {
        Optional<AutoTrader> target = autoTraders.stream()
                .filter(t -> t.getPair().equalsIgnoreCase(symbol))
                .findFirst();
        
        if (target.isPresent()) {
            target.get().stopBot();
            return "🛑 " + symbol + " 봇이 정지되었습니다.";
        } else {
            return "⚠️ 해당 코인의 봇을 찾을 수 없습니다: " + symbol;
        }
    }
    
    private static String startSpecificTrader(String symbol) {
        Optional<AutoTrader> target = autoTraders.stream()
                .filter(t -> t.getPair().equalsIgnoreCase(symbol))
                .findFirst();
        
        if (target.isPresent()) {
            target.get().startBot();
            return "✅ " + symbol + " 봇이 재시작되었습니다.";
        } else {
            return "⚠️ 해당 코인의 봇을 찾을 수 없습니다: " + symbol;
        }
    }

    public static synchronized void recordAndReportTrade(Position closedPosition, double exitPrice, double feeFromApi) {
        Trade trade = new Trade();
        trade.setSymbol(closedPosition.getSymbol());
        trade.setSide(closedPosition.getSide());
        trade.setEntryPrice(closedPosition.getEntryPrice());
        trade.setExitPrice(exitPrice);
        trade.setQuantity(closedPosition.getQuantity());

        double entryValue = trade.getEntryPrice() * trade.getQuantity();
        double exitValue = trade.getExitPrice() * trade.getQuantity();
        
        double fee = (feeFromApi > 0) ? feeFromApi : (entryValue + exitValue) * (config.getFeeRatePercent() / 100.0);
        trade.setFee(fee);

        double grossPnl = ("BUY".equals(trade.getSide())) ? (exitValue - entryValue) : (entryValue - exitValue);
        double netPnl = grossPnl - fee;
        
        int leverage = config.getLeverage();
        double marginUsed = entryValue / leverage;
        double pnlPercent = (marginUsed > 0) ? (netPnl / marginUsed) * 100 : 0;

        trade.setProfit(netPnl);
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
        
        double currentBalance = initialBalance + totalProfit;
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
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
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
                    sharedTotalEquity = futuresClient.getAccountEquity("USDT");
                    sharedAvailableBalance = futuresClient.getAvailableBalance("USDT");
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
                    
                    currentPriceStr = String.format("%.4f", currentPrice);
                    pnlStr = String.format("%.2f%%", pnlPercent);
                } else {
                    currentPriceStr = "조회 실패";
                    pnlStr = "N/A";
                }
                
                String logMsg = String.format("[%s] %s | 진입가: %s | 현재가: %s | 미실현 손익: %s",
                        position.getSymbol(),
                        position.getSide(),
                        String.format("%.4f", position.getEntryPrice()),
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
                            String.format("%.4f", position.getEntryPrice()), currentPriceStr,
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
            
        }, 2, 2, TimeUnit.MINUTES);
    }
    
    private static String getStatusSummary() {
        List<String> summaries = new ArrayList<>();
        for (AutoTrader trader : autoTraders) {
            if (trader.getCurrentPosition() != null) {
                Position position = trader.getCurrentPosition();
                double currentPrice = (config.getMode() == TradingMode.LIVE) 
                        ? trader.getApiClient().getTickerPrice(trader.getPair()) 
                        : globalPaperClient.getTickerPrice(trader.getPair());
                
                if (currentPrice > 0) {
                    double entryPrice = position.getEntryPrice();
                    double pnlPercent = (currentPrice / entryPrice - 1) * 100;
                    if ("SHORT".equals(position.getSide())) {
                        pnlPercent *= -1;
                    }
                    pnlPercent *= config.getLeverage();
                    
                    String sideEmoji = "BUY".equals(position.getSide()) ? "🟢" : "🔴";
                    String pnlEmoji = pnlPercent >= 0 ? "📈" : "📉";
                    summaries.add(String.format("%s <b>%s</b> %s\n" +
                                    "진입: $%s ➡️ 현재: $%s\n" +
                                    "%s <b>%.2f%%</b>",
                            sideEmoji, position.getSymbol(), position.getSide(),
                            String.format("%.4f", entryPrice), String.format("%.4f", currentPrice),
                            pnlEmoji, pnlPercent));
                } else {
                    summaries.add(String.format("⚪️ <b>%s</b> %s\n진입: $%s ➡️ 현재: 조회 실패",
                            position.getSymbol(), position.getSide(), String.format("%.4f", position.getEntryPrice())));
                }
            }
        }
        
        if (summaries.isEmpty()) {
            return "현재 보유 중인 포지션이 없습니다.";
        }
        
        StringBuilder sb = new StringBuilder("📋 <b>현재 포지션 요약</b>\n\n");
        for (String s : summaries) {
            sb.append(s).append("\n\n");
        }
        return sb.toString();
    }
    
    private static String getBalanceSummary() {
        StringBuilder sb = new StringBuilder("💰 <b>계좌 잔고 현황</b>\n\n");
        sb.append(String.format("<b>총 자산:</b> $%.2f\n", sharedTotalEquity));
        sb.append(String.format("<b>가용 잔고:</b> $%.2f\n", sharedAvailableBalance));
        return sb.toString();
    }
    
    private static void stopAllTraders() {
        botRunning = false;
        for (AutoTrader trader : autoTraders) {
            trader.stopBot();
        }
    }
    
    private static void startAllTraders() {
        botRunning = true;
        for (AutoTrader trader : autoTraders) {
            trader.startBot();
        }
    }
    
    private static String closeAllPositions() {
        for (AutoTrader trader : autoTraders) {
            if (trader.getCurrentPosition() != null) {
                trader.closePosition("강제 청산");
            }
        }
        return "모든 포지션에 대한 시장가 청산 명령을 보냈습니다.";
    }
    
    private static String closeSpecificPosition(String symbol) {
        Optional<AutoTrader> targetTrader = autoTraders.stream()
                .filter(t -> t.getPair().equalsIgnoreCase(symbol))
                .findFirst();
        
        if (targetTrader.isPresent() && targetTrader.get().getCurrentPosition() != null) {
            targetTrader.get().closePosition("강제 청산 (텔레그램)");
            return String.format("<b>%s</b> 포지션 시장가 청산 명령을 보냈습니다.", symbol);
        } else if (targetTrader.isPresent() && targetTrader.get().getCurrentPosition() == null) {
            return String.format("<b>%s</b> 포지션이 현재 없습니다.", symbol);
        } else {
            return String.format("<b>%s</b> 페어를 찾을 수 없습니다.", symbol);
        }
    }
    
    private static String getHelpMessage() {
        return "<b>사용 가능한 명령어:</b>\n" +
               "/status - 현재 포지션 요약\n" +
               "/balance - 계좌 잔고 현황\n" +
               "/stop - 모든 봇 정지\n" +
               "/stop [SYMBOL] - 특정 봇 정지\n" +
               "/start - 모든 봇 재시작\n" +
               "/start [SYMBOL] - 특정 봇 재시작\n" +
               "/add [SYMBOL] - 새로운 코인 봇 추가\n" +
               "/close all - 모든 포지션 청산\n" +
               "/close [SYMBOL] - 특정 포지션 청산\n" +
               "/long on/off - Long 진입 허용/금지\n" +
               "/short on/off - Short 진입 허용/금지\n" +
               "/tp [PERCENT] - 익절 비율 변경 (예: /tp 0.5)\n" +
               "/sl [PERCENT] - 손절 비율 변경 (예: /sl 0.3)\n" +
               "/help - 명령어 목록 표시";
    }
}
