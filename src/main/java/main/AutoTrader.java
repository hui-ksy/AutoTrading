package main;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import main.bitget.BitgetFuturesApiClient;
import main.bitget.PaperTradingClient;
import main.bitget.TradeClient;
import main.job.TelegramNotifier;
import main.model.*;
import main.strategy.TradingStrategy;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
public class AutoTrader {

    @FunctionalInterface
    public interface TradeHandler {
        void handle(Position closedPosition, double exitPrice, double fee);
    }

    @Getter
    private final TradeClient apiClient;
    private final PaperTradingClient paperClient;
    private final TradingStrategy strategy;
    private final TradingConfig config;
    @Getter
    private final String pair;
    private final TelegramNotifier telegram;
    private final ScheduledExecutorService scheduler;
    private final TradeHandler tradeHandler;

    @Getter
    private Position currentPosition;
    private volatile boolean running;
    private volatile boolean isEntering;
    private volatile Signal entrySignal;
    private long enteringStartTime = 0;
    
    private final double fixedStopLossPercent;
    private final double fixedTakeProfitPercent;
    
    private static final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    public AutoTrader(TradeClient apiClient, PaperTradingClient paperClient,
                      TradingStrategy strategy, TradingConfig config,
                      String pair, TelegramNotifier telegram, TradeHandler tradeHandler) {
        this.apiClient = apiClient;
        this.paperClient = paperClient;
        this.strategy = strategy;
        this.config = config;
        this.pair = pair;
        this.telegram = telegram;
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
        this.isEntering = false;
        
        this.fixedStopLossPercent = config.getStopLossPercent() / 100.0;
        this.fixedTakeProfitPercent = config.getTakeProfitPercent() / 100.0;
        this.tradeHandler = tradeHandler;
    }

    public void start() {
        log.info("자동 트레이딩 시작 - 페어: {}", pair);
        running = true;
        
        scheduler.scheduleAtFixedRate(this::tickerLoop, 0, config.getTickerIntervalSeconds(), TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::candleLoop, 0, config.getCandleIntervalSeconds(), TimeUnit.SECONDS);
        
        checkAndLoadExistingPosition();
    }
    
    public void stopBot() {
        this.running = false;
        log.info("[{}] 봇 일시 정지", pair);
    }
    
    public void startBot() {
        this.running = true;
        log.info("[{}] 봇 재시작", pair);
    }
    
    public void stop() {
        log.info("자동 트레이딩 중지 [{}]", pair);
        running = false;
        scheduler.shutdown();
    }
    
    public void closePosition(String reason) {
        if (currentPosition != null) {
            double currentPrice = (apiClient != null) ? apiClient.getTickerPrice(pair) : 0.0;
            if (currentPrice > 0) {
                executeExitPosition(reason, currentPrice);
            } else {
                log.warn("[{}] 현재가 조회 실패로 강제 청산 불가", pair);
            }
        }
    }

    private void tickerLoop() {
        if (!running || !hasPosition()) return;
        
        try {
            double currentPrice = (apiClient != null) ? apiClient.getTickerPrice(pair) : 0.0;
            if (currentPrice > 0) {
                managePosition(currentPrice);
            }
        } catch (Exception e) {
            log.error("티커 루프 오류 [{}]", pair, e);
        }
    }

    private void candleLoop() {
        if (!running || isEntering) return;
        
        try {
            if (!hasPosition()) {
                double totalEquity;
                double availableBalance;
                
                if (config.getMode() == TradingMode.PAPER) {
                    totalEquity = paperClient.getTotalEquity();
                    availableBalance = totalEquity;
                } else {
                    totalEquity = BitgetTradingBot.sharedTotalEquity;
                    availableBalance = BitgetTradingBot.sharedAvailableBalance;
                }
                
                if (availableBalance > 0) {
                    double requiredMargin = totalEquity * (config.getOrderPercentOfBalance() / 100.0);
                    if (availableBalance < 5.0 || availableBalance < requiredMargin) {
                        return; 
                    }
                }
            }

            if (isEntering) {
                if (System.currentTimeMillis() - enteringStartTime > 180000) {
                    log.warn("[{}] 진입 대기 시간 초과! 강제로 상태를 초기화합니다.", pair);
                    isEntering = false;
                    entrySignal = null;
                } else {
                    if (config.getMode() == TradingMode.LIVE) confirmPositionEntered();
                    return;
                }
            }
            
            if (currentPosition != null && config.getMode() == TradingMode.LIVE && !apiClient.hasPosition(pair)) {
                handleExternalClose();
                return;
            }

            List<Candle> candles = getCandles(config.getTimeframe(), config.getCandleLimit());
            if (candles == null || candles.isEmpty()) return;
            
            long lastCandleTimestamp = candles.get(candles.size() - 1).getTimestamp();
            long currentTime = System.currentTimeMillis();
            long diffMinutes = (currentTime - lastCandleTimestamp) / (60 * 1000);
            
            if (diffMinutes > 10) {
                log.warn("[{}] 마지막 캔들 데이터가 너무 오래되었습니다 ({}분 전). 전략 분석을 건너뜁니다.", pair, diffMinutes);
                return;
            }

            Signal signal = strategy.generateSignal(candles, pair, currentPosition);
            
            if (signal.getAction() == Signal.Action.EXIT) {
                double currentPrice = (apiClient != null) ? apiClient.getTickerPrice(pair) : candles.get(candles.size() - 1).getClose();
                executeExitPosition(signal.getReason(), currentPrice);
            } else if (signal.getAction() == Signal.Action.BUY || signal.getAction() == Signal.Action.SHORT) {
                if (!hasPosition()) {
                    executeEnterPosition(signal, candles);
                }
            }
            
        } catch (Exception e) {
            log.error("캔들 루프 오류 [{}]", pair, e);
        }
    }

    public synchronized void checkAndLoadExistingPosition() {
        if (config.getMode() == TradingMode.LIVE && apiClient instanceof BitgetFuturesApiClient) {
            BitgetFuturesApiClient futuresClient = (BitgetFuturesApiClient) apiClient;
            Position existingPosition = futuresClient.getSinglePosition(pair);
            
            if (existingPosition != null) {
                log.info("[{}] 기존 {} 포지션 발견! 관리를 시작합니다.", pair, existingPosition.getSide());
                this.currentPosition = existingPosition;

                log.info("[{}] 기존 TP/SL 주문을 초기화하고 새로 설정합니다.", pair);
                futuresClient.cancelAllPlanOrders(pair);
                
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                
                setFixedTpSl(existingPosition); 
                placeExchangeOrders(existingPosition);
                
                telegram.notifyPositionTakeover(existingPosition, config);
            } else {
                log.info("[{}] 현재 보유 중인 포지션이 없습니다.", pair);
            }
        }
    }

    private void executeEnterPosition(Signal signal, List<Candle> candles) {
        double quantity = calculateOrderQuantity(signal.getEntryPrice());
        if (quantity <= 0) return;

        double entryPrice = (apiClient != null) ? apiClient.getTickerPrice(pair) : signal.getEntryPrice();
        if (entryPrice <= 0) {
            log.warn("[{}] 현재가 조회 실패. 진입을 취소합니다.", pair);
            return;
        }

        Candle lastCandle = candles.get(candles.size() - 1);
        String candleTime = timeFormatter.format(Instant.ofEpochMilli(lastCandle.getTimestamp()));
        
        log.info("[시장가 진입 실행] {} {} {} @ ${} (기준 캔들 시간: {})", 
                signal.getAction(), String.format("%.4f", quantity), pair, 
                String.format("%.4f", entryPrice), candleTime);
        
        this.entrySignal = signal;
        this.enteringStartTime = System.currentTimeMillis();

        if (config.getMode() == TradingMode.PAPER) {
            OrderResult result = paperClient.openPosition(pair, signal.getAction().toString(), quantity, entryPrice, config.getLeverage());
            if (result != null) {
                currentPosition = new Position();
                currentPosition.setSymbol(pair);
                currentPosition.setSide(signal.getAction().toString());
                currentPosition.setEntryPrice(result.getAveragePrice());
                currentPosition.setQuantity(result.getFilledQuantity());
                currentPosition.setEntryTimestamp(lastCandle.getTimestamp());
                
                // [수정] 진입 증거금 로그 추가
                double marginUsed = (currentPosition.getEntryPrice() * currentPosition.getQuantity()) / config.getLeverage();
                log.info("[{}] 진입 증거금: ${}", pair, String.format("%.2f", marginUsed));
                
                setFixedTpSl(currentPosition);
                
                telegram.notifyEnterPosition(currentPosition, signal, config);
                this.entrySignal = null;
            }
        } else { // LIVE Mode
            OrderResult result = null;
            double currentQuantity = quantity;
            for (int i = 0; i < 3; i++) {
                if (signal.getAction() == Signal.Action.BUY) {
                    result = apiClient.marketLong(pair, currentQuantity);
                } else {
                    result = apiClient.marketShort(pair, currentQuantity);
                }

                if (result != null && ("filled".equals(result.getStatus()) || "new".equals(result.getStatus()))) {
                    isEntering = true;
                    break;
                } else {
                    log.warn("[{}] 주문 실패 (시도 {}/3). 수량을 절반으로 줄여 재시도합니다.", pair, i + 1);
                    currentQuantity /= 2.0;
                    try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                }
            }

            if (result == null || (!"filled".equals(result.getStatus()) && !"new".equals(result.getStatus()))) {
                log.error("[{}] 주문 최종 실패. 진입을 취소합니다.", pair);
                this.entrySignal = null;
                this.isEntering = false;
            }
        }
    }

    private synchronized void confirmPositionEntered() {
        log.info("[{}] 체결 상태 확인 중...", pair);
        Position position = ((BitgetFuturesApiClient) apiClient).getSinglePosition(pair);

        if (position != null) {
            log.info("[진입 완료] {} {} @ ${}",
                    position.getSide(), String.format("%.4f", position.getQuantity()), pair,
                    String.format("%.4f", position.getEntryPrice()));
            
            this.currentPosition = position;
            this.currentPosition.setEntryTimestamp(System.currentTimeMillis());
            
            // [수정] 진입 증거금 로그 추가
            double marginUsed = (this.currentPosition.getEntryPrice() * this.currentPosition.getQuantity()) / config.getLeverage();
            log.info("[{}] 진입 증거금: ${}", pair, String.format("%.2f", marginUsed));
            
            if (apiClient instanceof BitgetFuturesApiClient) {
                ((BitgetFuturesApiClient) apiClient).cancelAllPlanOrders(pair);
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            }
            
            setFixedTpSl(this.currentPosition);
            placeExchangeOrders(this.currentPosition);
            
            telegram.notifyEnterPosition(this.currentPosition, this.entrySignal, config);
            this.entrySignal = null;
            this.isEntering = false;
        }
    }
    
    private void placeExchangeOrders(Position position) {
        if (config.getMode() != TradingMode.LIVE || !(apiClient instanceof BitgetFuturesApiClient)) return;
        
        BitgetFuturesApiClient futuresClient = (BitgetFuturesApiClient) apiClient;
        
        boolean slSuccess = false;
        for (int i = 0; i < 3; i++) {
            slSuccess = futuresClient.placeTpSlOrder(
                    position.getSymbol(), position.getSide(), position.getQuantity(),
                    position.getStopLoss(), "loss_plan"
            );
            if (slSuccess) {
                break;
            }
            log.warn("[{}] 거래소 손절 주문(SL) 설정 실패 (시도: {}). 1초 후 재시도...", pair, i + 1);
            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
        }
        if (!slSuccess) {
            log.error("[{}] 거래소 손절 주문(SL) 설정 최종 실패!", pair);
            telegram.notifyError(String.format("🚨 [%s] SL 주문 설정 최종 실패! 수동 확인 및 대응 필요!", pair));
        }
        
        boolean tpSuccess = false;
        for (int i = 0; i < 3; i++) {
            tpSuccess = futuresClient.placeTpSlOrder(
                    position.getSymbol(), position.getSide(), position.getQuantity(),
                    position.getTakeProfit(), "profit_plan"
            );
            if (tpSuccess) {
                break;
            }
            log.warn("[{}] 거래소 익절 주문(TP) 설정 실패 (시도: {}). 1초 후 재시도...", pair, i + 1);
            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
        }
        if (!tpSuccess) {
            log.error("[{}] 거래소 익절 주문(TP) 설정 최종 실패!", pair);
            telegram.notifyError(String.format("🚨 [%s] TP 주문 설정 최종 실패! 수동 확인 및 대응 필요!", pair));
        }
    }

    private void executeExitPosition(String reason, double exitPrice) {
        if (currentPosition == null) return;

        log.info("[시장가 청산 실행] 사유: {}", reason);
        
        if (config.getMode() == TradingMode.PAPER) {
            OrderResult result = paperClient.closePosition(pair, exitPrice);
            if (result == null) {
                log.warn("[PAPER] 페이퍼 클라이언트에서 포지션을 찾을 수 없으나, 봇 내부 포지션을 강제 청산 처리합니다.");
            }
            tradeHandler.handle(currentPosition, exitPrice, 0);
            currentPosition = null;
        } else {
            if (apiClient instanceof BitgetFuturesApiClient) {
                BitgetFuturesApiClient futuresClient = (BitgetFuturesApiClient) apiClient;
                
                OrderResult closeResult = futuresClient.closeEntirePosition(pair);
                if (closeResult != null && "filled".equals(closeResult.getStatus())) {
                    log.info("[{}] 포지션 전체 청산 완료", pair);
                } else {
                    log.error("[{}] 포지션 전체 청산 실패!", pair);
                }
                
                futuresClient.cancelAllPlanOrders(pair);
            }
            
            tradeHandler.handle(currentPosition, exitPrice, 0);
            currentPosition = null;
        }
    }
    
    public synchronized void handleExternalClose() {
        if (currentPosition == null) return;

        log.info("[상태 감지] {} 포지션이 외부에서 청산되었습니다. 거래 내역을 확인합니다.", pair);
        
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        Trade lastApiTrade = ((BitgetFuturesApiClient) apiClient).getLatestTrade(pair);
        if (lastApiTrade != null) {
            log.info("[{}] 외부 청산 거래 내역: ExitPrice={}, Fee={}", pair, lastApiTrade.getExitPrice(), lastApiTrade.getFee());
            tradeHandler.handle(currentPosition, lastApiTrade.getExitPrice(), lastApiTrade.getFee());
        } else {
            log.warn("[{}] 최근 거래 내역 조회에 실패하여, 현재가로 손익을 추정합니다.", pair);
            double currentPrice = (apiClient != null) ? apiClient.getTickerPrice(pair) : 0.0;
            tradeHandler.handle(currentPosition, currentPrice > 0 ? currentPrice : 0.0, 0);
        }
        
        if (apiClient instanceof BitgetFuturesApiClient) {
            ((BitgetFuturesApiClient) apiClient).cancelAllPlanOrders(pair);
        }
        
        currentPosition = null;
        isEntering = false;
    }

    private void setFixedTpSl(Position position) {
        boolean isLong = "BUY".equals(position.getSide());
        double entryPrice = position.getEntryPrice();
        
        double currentPrice = (apiClient != null) ? apiClient.getTickerPrice(pair) : entryPrice;
        if (currentPrice <= 0) currentPrice = entryPrice;
        
        double stopLossPrice;
        double takeProfitPrice;
        
        if (isLong) {
            double calculatedSl = entryPrice * (1 - fixedStopLossPercent);
            double calculatedTp = entryPrice * (1 + fixedTakeProfitPercent);
            
            stopLossPrice = Math.min(calculatedSl, currentPrice * 0.999);
            takeProfitPrice = Math.max(calculatedTp, currentPrice * 1.001);
            
        } else {
            double calculatedSl = entryPrice * (1 + fixedStopLossPercent);
            double calculatedTp = entryPrice * (1 - fixedTakeProfitPercent);
            
            stopLossPrice = Math.max(calculatedSl, currentPrice * 1.001);
            takeProfitPrice = Math.min(calculatedTp, currentPrice * 0.999);
        }
        
        position.setStopLoss(stopLossPrice);
        position.setTakeProfit(takeProfitPrice);
        
        log.info("[{}] 고정 손익절 설정 - SL: {} (-{}%), TP: {} (+{}%)", 
                pair, String.format("%.4f", stopLossPrice), fixedStopLossPercent * 100,
                String.format("%.4f", takeProfitPrice), fixedTakeProfitPercent * 100);
    }

    private void managePosition(double currentPrice) {
        if (!hasPosition()) return;

        boolean isLong = "BUY".equals(currentPosition.getSide());
        
        if (currentPosition.getStopLoss() <= 0 || currentPosition.getTakeProfit() <= 0) {
            if (config.getMode() == TradingMode.PAPER) {
                log.warn("[{}] 손익절가가 비정상입니다. 강제 설정합니다.", pair);
                setFixedTpSl(currentPosition);
            } else {
                // LIVE 모드에서는 거래소 주문이 우선이므로 내부 변수 갱신만 시도하거나 무시
            }
        }

        if (config.getMode() == TradingMode.PAPER) {
            if (isLong) {
                if (currentPrice <= currentPosition.getStopLoss()) {
                    executeExitPosition("손절(SL) 도달", currentPrice);
                    return;
                }
                if (currentPrice >= currentPosition.getTakeProfit()) {
                    executeExitPosition("익절(TP) 도달", currentPrice);
                    return;
                }
            } else { // SHORT
                if (currentPrice >= currentPosition.getStopLoss()) {
                    executeExitPosition("손절(SL) 도달", currentPrice);
                    return;
                }
                if (currentPrice <= currentPosition.getTakeProfit()) {
                    executeExitPosition("익절(TP) 도달", currentPrice);
                    return;
                }
            }
        }
    }

    private double calculateOrderQuantity(double price) {
        double totalEquity = (config.getMode() == TradingMode.PAPER) ? paperClient.getTotalEquity() : BitgetTradingBot.sharedTotalEquity;
        double availableBalance = (config.getMode() == TradingMode.PAPER) ? totalEquity : BitgetTradingBot.sharedAvailableBalance;
        
        if (totalEquity <= 0) return 0;

        double requiredMargin = totalEquity * (config.getOrderPercentOfBalance() / 100.0);
        if (availableBalance < requiredMargin) {
            return 0;
        }

        double marginToUse = requiredMargin;
        double totalOrderValue = marginToUse * config.getLeverage();
        
        return (totalOrderValue / price) * 0.95;
    }

    private boolean hasPosition() {
        return this.currentPosition != null;
    }

    public List<Candle> getCandles(String timeframe, int limit) {
        return (paperClient != null) ? paperClient.getCandles(pair, timeframe, limit) : (apiClient != null ? apiClient.getCandles(pair, timeframe, limit) : new ArrayList<>());
    }
}
