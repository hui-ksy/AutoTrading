package main.core.trading;

import lombok.extern.slf4j.Slf4j;
import main.api.bitget.BitgetFuturesApiClient;
import main.api.bitget.PaperTradingClient;
import main.api.bitget.TradeClient;
import main.account.AccountBalanceProvider;
import main.job.TelegramNotifier;
import main.model.domain.Candle;
import main.model.domain.OrderResult;
import main.model.domain.Position;
import main.model.domain.Signal;
import main.model.config.TradingConfig;
import main.model.config.TradingMode;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
class PositionExecutor {

    private final TradeClient apiClient;
    private final PaperTradingClient paperClient;
    private final TradingConfig config;
    private final String pair;
    private final TelegramNotifier telegram;
    private final AccountBalanceProvider balanceProvider;
    private final TpSlCalculator tpSlCalculator;
    private final PositionExitHandler exitHandler;

    private Position currentPosition;
    private volatile boolean isEntering;
    private volatile Signal entrySignal;
    private long enteringStartTime = 0;

    private static final DateTimeFormatter timeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    PositionExecutor(TradeClient apiClient, PaperTradingClient paperClient,
                     TradingConfig config, String pair, TelegramNotifier telegram,
                     AutoTrader.TradeHandler tradeHandler, AccountBalanceProvider balanceProvider,
                     TpSlCalculator tpSlCalculator) {
        this.apiClient = apiClient;
        this.paperClient = paperClient;
        this.config = config;
        this.pair = pair;
        this.telegram = telegram;
        this.balanceProvider = balanceProvider;
        this.tpSlCalculator = tpSlCalculator;
        this.exitHandler = new PositionExitHandler(apiClient, paperClient, config, pair, telegram, tradeHandler);
        this.isEntering = false;
    }

    void executeEnterPosition(Signal signal, List<Candle> candles) {
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
            enterPaperPosition(signal, quantity, entryPrice, lastCandle);
        } else {
            submitLiveOrder(signal, quantity);
        }
    }

    private void enterPaperPosition(Signal signal, double quantity, double entryPrice, Candle lastCandle) {
        OrderResult result = paperClient.openPosition(pair, signal.getAction().toString(), quantity, entryPrice, config.getLeverage());
        if (result != null) {
            currentPosition = new Position();
            currentPosition.setSymbol(pair);
            currentPosition.setSide(signal.getAction().toString());
            currentPosition.setEntryPrice(result.getAveragePrice());
            currentPosition.setQuantity(result.getFilledQuantity());
            currentPosition.setEntryTimestamp(lastCandle.getTimestamp());

            double marginUsed = (currentPosition.getEntryPrice() * currentPosition.getQuantity()) / config.getLeverage();
            log.info("[{}] 진입 증거금: ${}", pair, String.format("%.2f", marginUsed));

            if (signal.getStopLoss() > 0 && signal.getTakeProfit() > 0) {
                currentPosition.setStopLoss(signal.getStopLoss());
                currentPosition.setTakeProfit(signal.getTakeProfit());
                log.info("[{}] ATR 기반 손익절 설정 - SL: {}, TP: {}", pair,
                        String.format("%.4f", signal.getStopLoss()),
                        String.format("%.4f", signal.getTakeProfit()));
            } else {
                tpSlCalculator.setFixedTpSl(currentPosition);
            }

            telegram.notifyEnterPosition(currentPosition, signal, config);
            this.entrySignal = null;
        }
    }

    private void submitLiveOrder(Signal signal, double quantity) {
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

    synchronized void confirmPositionEntered() {
        log.info("[{}] 체결 상태 확인 중...", pair);
        Position position = ((BitgetFuturesApiClient) apiClient).getSinglePosition(pair);

        if (position != null) {
            log.info("[진입 완료] {} {} @ ${}",
                    position.getSide(), String.format("%.4f", position.getQuantity()), pair,
                    String.format("%.4f", position.getEntryPrice()));

            this.currentPosition = position;
            this.currentPosition.setEntryTimestamp(System.currentTimeMillis());

            double marginUsed = (this.currentPosition.getEntryPrice() * this.currentPosition.getQuantity()) / config.getLeverage();
            log.info("[{}] 진입 증거금: ${}", pair, String.format("%.2f", marginUsed));

            if (apiClient instanceof BitgetFuturesApiClient) {
                ((BitgetFuturesApiClient) apiClient).cancelAllPlanOrders(pair);
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            }

            if (this.entrySignal != null && this.entrySignal.getStopLoss() > 0 && this.entrySignal.getTakeProfit() > 0) {
                this.currentPosition.setStopLoss(this.entrySignal.getStopLoss());
                this.currentPosition.setTakeProfit(this.entrySignal.getTakeProfit());
                log.info("[{}] ATR 기반 손익절 설정 - SL: {}, TP: {}", pair,
                        String.format("%.6f", this.entrySignal.getStopLoss()),
                        String.format("%.6f", this.entrySignal.getTakeProfit()));
            } else {
                tpSlCalculator.setFixedTpSl(this.currentPosition);
            }
            tpSlCalculator.placeExchangeOrders(this.currentPosition);

            telegram.notifyEnterPosition(this.currentPosition, this.entrySignal, config);
            this.entrySignal = null;
            this.isEntering = false;
        }
    }

    void executeExitPosition(String reason, double exitPrice) {
        exitHandler.executeExitPosition(currentPosition, reason, exitPrice);
        currentPosition = null;
    }

    synchronized void handleExternalClose() {
        exitHandler.handleExternalClose(currentPosition);
        currentPosition = null;
        isEntering = false;
    }

    private double calculateOrderQuantity(double price) {
        double totalEquity = (config.getMode() == TradingMode.PAPER) ? paperClient.getTotalEquity() : balanceProvider.getTotalEquity();
        double availableBalance = (config.getMode() == TradingMode.PAPER) ? totalEquity : balanceProvider.getAvailableBalance();

        if (totalEquity <= 0) return 0;

        double requiredMargin = totalEquity * (config.getOrderPercentOfBalance() / 100.0);
        if (availableBalance < requiredMargin) return 0;

        double marginToUse = requiredMargin;
        double totalOrderValue = marginToUse * config.getLeverage();
        return (totalOrderValue / price) * 0.95;
    }

    double getCurrentPrice() {
        if (apiClient != null) return apiClient.getTickerPrice(pair);
        if (paperClient != null) return paperClient.getTickerPrice(pair);
        return 0.0;
    }

    boolean hasPosition() {
        return this.currentPosition != null;
    }

    void setCurrentPosition(Position position) {
        this.currentPosition = position;
    }

    Position getCurrentPosition() {
        return this.currentPosition;
    }

    boolean hasSufficientBalance() {
        double totalEquity = (config.getMode() == TradingMode.PAPER) ? paperClient.getTotalEquity() : balanceProvider.getTotalEquity();
        double availableBalance = (config.getMode() == TradingMode.PAPER) ? totalEquity : balanceProvider.getAvailableBalance();

        if (availableBalance > 0) {
            double requiredMargin = totalEquity * (config.getOrderPercentOfBalance() / 100.0);
            return availableBalance >= 5.0 && availableBalance >= requiredMargin;
        }
        return true;
    }

    boolean isEntering() {
        return isEntering;
    }

    long getEnteringStartTime() {
        return enteringStartTime;
    }

    void resetEntering() {
        this.isEntering = false;
        this.entrySignal = null;
    }
}
