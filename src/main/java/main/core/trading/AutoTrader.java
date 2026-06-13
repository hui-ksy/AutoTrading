package main.core.trading;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import main.api.bitget.BitgetFuturesApiClient;
import main.api.bitget.PaperTradingClient;
import main.api.bitget.TradeClient;
import main.account.AccountBalanceProvider;
import main.job.TelegramNotifier;
import main.model.domain.Candle;
import main.model.domain.Position;
import main.model.domain.Signal;
import main.model.config.TradingConfig;
import main.model.config.TradingMode;
import main.model.config.SymbolConfig;
import main.strategy.core.StrategyFactory;
import main.strategy.core.TradingStrategy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
public class AutoTrader {

    @FunctionalInterface
    public interface TradeHandler {
        void handle(Position closedPosition, double exitPrice, double pnl, double fee);
    }

    @FunctionalInterface
    public interface DirectionChecker {
        boolean isBlocked(String pair, Signal.Action action);
    }

    @Getter
    private final TradeClient apiClient;
    private final PaperTradingClient paperClient;
    private volatile TradingStrategy strategy;
    private final TradingConfig config;
    @Getter
    private final String pair;
    private final TelegramNotifier telegram;
    private final ScheduledExecutorService scheduler;

    private volatile boolean running;
    private int analysisCount = 0;

    private final DirectionChecker directionChecker;
    private final SymbolConfig symbolConfig;
    private final AccountBalanceProvider balanceProvider;

    private final PositionExecutor positionExecutor;
    private final TpSlCalculator tpSlCalculator;

    public AutoTrader(TradeClient apiClient, PaperTradingClient paperClient,
                      TradingStrategy strategy, TradingConfig config,
                      String pair, TelegramNotifier telegram, TradeHandler tradeHandler,
                      DirectionChecker directionChecker, AccountBalanceProvider balanceProvider) {
        this.apiClient = apiClient;
        this.paperClient = paperClient;
        this.strategy = strategy;
        this.config = config;
        this.pair = pair;
        this.telegram = telegram;
        this.scheduler = Executors.newSingleThreadScheduledExecutor();

        this.directionChecker = directionChecker;
        this.symbolConfig = config.getSymbolConfigs().computeIfAbsent(pair, SymbolConfig::defaults);
        this.balanceProvider = balanceProvider;

        double fixedStopLossPercent = config.getStopLossPercent() / 100.0;
        double fixedTakeProfitPercent = config.getTakeProfitPercent() / 100.0;

        this.tpSlCalculator = new TpSlCalculator(apiClient, config, symbolConfig, pair, telegram,
                fixedStopLossPercent, fixedTakeProfitPercent);
        this.positionExecutor = new PositionExecutor(apiClient, paperClient, config, pair, telegram,
                tradeHandler, balanceProvider, tpSlCalculator);
    }

    public void start() {
        log.info("자동 트레이딩 시작 - 페어: {}", pair);
        running = true;
        
        scheduler.scheduleAtFixedRate(this::tickerLoop, 0, symbolConfig.getTickerIntervalSeconds(), TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::candleLoop, 0, symbolConfig.getCandleIntervalSeconds(), TimeUnit.SECONDS);
        
        checkAndLoadExistingPosition();
    }
    
    public void reloadStrategy() {
        SymbolConfig symbolConfig = config.getSymbolConfigs().computeIfAbsent(pair, SymbolConfig::defaults);
        this.strategy = StrategyFactory.createStrategy(config, symbolConfig);
        log.info("[{}] 전략 인스턴스 재생성 완료 (새 파라미터 적용)", pair);
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
        if (positionExecutor.hasPosition()) {
            double currentPrice = positionExecutor.getCurrentPrice();
            if (currentPrice > 0) {
                positionExecutor.executeExitPosition(reason, currentPrice);
            } else {
                log.warn("[{}] 현재가 조회 실패로 강제 청산 불가", pair);
            }
        }
    }

    public Position getCurrentPosition() {
        return positionExecutor.getCurrentPosition();
    }

    public void handleExternalClose() {
        positionExecutor.handleExternalClose();
    }

    private void tickerLoop() {
        if (!running || !positionExecutor.hasPosition()) return;

        try {
            double currentPrice = positionExecutor.getCurrentPrice();
            if (currentPrice > 0) {
                Position currentPosition = positionExecutor.getCurrentPosition();
                int maxHoldHours = config.getMaxHoldHours();
                if (config.isMaxHoldEnabled() && maxHoldHours > 0 && currentPosition.getEntryTimestamp() > 0) {
                    long holdMs = System.currentTimeMillis() - currentPosition.getEntryTimestamp();
                    if (holdMs > (long) maxHoldHours * 3_600_000L) {
                        log.warn("[{}] 최대 보유 시간({}h) 초과 — 포지션 강제 청산", pair, maxHoldHours);
                        telegram.notifyError(String.format("⏰ [%s] 최대 보유 %dh 초과 — 자동 청산", pair, maxHoldHours));
                        positionExecutor.executeExitPosition("최대 보유 시간 초과", currentPrice);
                        return;
                    }
                }
                managePosition(currentPrice);
            }
        } catch (Exception e) {
            log.error("티커 루프 오류 [{}]", pair, e);
        }
    }

    private void candleLoop() {
        if (!running || positionExecutor.isEntering()) return;

        try {
            if (!positionExecutor.hasPosition() && !positionExecutor.hasSufficientBalance()) {
                return;
            }

            if (positionExecutor.isEntering()) {
                if (System.currentTimeMillis() - positionExecutor.getEnteringStartTime() > 180000) {
                    log.warn("[{}] 진입 대기 시간 초과! 강제로 상태를 초기화합니다.", pair);
                    positionExecutor.resetEntering();
                } else {
                    if (config.getMode() == TradingMode.LIVE) positionExecutor.confirmPositionEntered();
                    return;
                }
            }

            if (positionExecutor.hasPosition() && config.getMode() == TradingMode.LIVE && !apiClient.hasPosition(pair)) {
                positionExecutor.handleExternalClose();
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

            Signal signal = strategy.generateSignal(candles, pair, positionExecutor.getCurrentPosition());
            analysisCount++;

            if (signal.getAction() == Signal.Action.HOLD) {
                log.info("[{}] #{} HOLD | {}", pair, analysisCount, signal.getReason());
                if (analysisCount % 120 == 1) {
                    telegram.notifyPeriodicStatus(pair, analysisCount, signal.getReason());
                }
            } else if (signal.getAction() == Signal.Action.EXIT) {
                double currentPrice = (apiClient != null) ? apiClient.getTickerPrice(pair) : candles.get(candles.size() - 1).getClose();
                log.info("[{}] #{} EXIT 신호 | {}", pair, analysisCount, signal.getReason());
                positionExecutor.executeExitPosition(signal.getReason(), currentPrice);
            } else if (signal.getAction() == Signal.Action.BUY || signal.getAction() == Signal.Action.SHORT) {
                log.info("[{}] #{} ★ {} 신호 | {}", pair, analysisCount, signal.getAction(), signal.getReason());
                if (directionChecker != null && directionChecker.isBlocked(pair, signal.getAction())) {
                    log.info("[{}] 방향성 필터: 다른 코인 반대 포지션으로 진입 차단 ({})", pair, signal.getAction());
                } else {
                    telegram.notifySignalDetected(pair, signal.getAction().toString(), signal.getReason());
                    if (!positionExecutor.hasPosition()) {
                        positionExecutor.executeEnterPosition(signal, candles);
                    }
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
                positionExecutor.setCurrentPosition(existingPosition);
                if (existingPosition.getEntryTimestamp() == 0) {
                    existingPosition.setEntryTimestamp(System.currentTimeMillis());
                    log.info("[{}] 포지션 진입 시간 미확인 — 현재 시간으로 초기화 (maxHoldHours 카운트 시작)", pair);
                }

                double marginUsed = (existingPosition.getEntryPrice() * existingPosition.getQuantity()) / config.getLeverage();
                log.info("[{}] 인수 증거금: ${}", pair, String.format("%.2f", marginUsed));

                log.info("[{}] 기존 TP/SL 주문을 초기화하고 새로 설정합니다.", pair);
                futuresClient.cancelAllPlanOrders(pair);

                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}

                List<Candle> candles = getCandles(config.getTimeframe(), config.getCandleLimit());
                if (candles != null && !candles.isEmpty()) {
                    tpSlCalculator.setAtrTpSl(existingPosition, candles);
                }
                tpSlCalculator.placeExchangeOrders(existingPosition);

                telegram.notifyPositionTakeover(existingPosition, config);
            } else {
                log.info("[{}] 현재 보유 중인 포지션이 없습니다.", pair);
            }
        }
    }


    private void managePosition(double currentPrice) {
        if (!positionExecutor.hasPosition()) return;

        Position currentPosition = positionExecutor.getCurrentPosition();
        boolean isLong = "BUY".equals(currentPosition.getSide());

        if (currentPosition.getStopLoss() <= 0 || currentPosition.getTakeProfit() <= 0) {
            if (config.getMode() == TradingMode.PAPER) {
                log.warn("[{}] 손익절가가 비정상입니다. 강제 설정합니다.", pair);
                tpSlCalculator.setFixedTpSl(currentPosition);
            }
        }

        if (config.getMode() == TradingMode.PAPER) {
            if (isLong) {
                if (currentPrice <= currentPosition.getStopLoss()) {
                    positionExecutor.executeExitPosition("손절(SL) 도달", currentPrice);
                    return;
                }
                if (currentPrice >= currentPosition.getTakeProfit()) {
                    positionExecutor.executeExitPosition("익절(TP) 도달", currentPrice);
                    return;
                }
            } else {
                if (currentPrice >= currentPosition.getStopLoss()) {
                    positionExecutor.executeExitPosition("손절(SL) 도달", currentPrice);
                    return;
                }
                if (currentPrice <= currentPosition.getTakeProfit()) {
                    positionExecutor.executeExitPosition("익절(TP) 도달", currentPrice);
                    return;
                }
            }
        }
    }

    public List<Candle> getCandles(String timeframe, int limit) {
        return (paperClient != null) ? paperClient.getCandles(pair, timeframe, limit) : (apiClient != null ? apiClient.getCandles(pair, timeframe, limit) : new ArrayList<>());
    }
}
