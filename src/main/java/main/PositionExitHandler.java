package main;

import lombok.extern.slf4j.Slf4j;
import main.bitget.BitgetFuturesApiClient;
import main.bitget.PaperTradingClient;
import main.bitget.TradeClient;
import main.job.TelegramNotifier;
import main.model.OrderResult;
import main.model.Position;
import main.model.Trade;
import main.model.TradingConfig;
import main.model.TradingMode;

@Slf4j
class PositionExitHandler {

    private final TradeClient apiClient;
    private final PaperTradingClient paperClient;
    private final TradingConfig config;
    private final String pair;
    private final TelegramNotifier telegram;
    private final AutoTrader.TradeHandler tradeHandler;

    PositionExitHandler(TradeClient apiClient, PaperTradingClient paperClient,
                        TradingConfig config, String pair, TelegramNotifier telegram,
                        AutoTrader.TradeHandler tradeHandler) {
        this.apiClient = apiClient;
        this.paperClient = paperClient;
        this.config = config;
        this.pair = pair;
        this.telegram = telegram;
        this.tradeHandler = tradeHandler;
    }

    void executeExitPosition(Position currentPosition, String reason, double exitPrice) {
        if (currentPosition == null) return;

        log.info("[시장가 청산 실행] 사유: {}", reason);

        if (config.getMode() == TradingMode.PAPER) {
            OrderResult result = paperClient.closePosition(pair, exitPrice);
            if (result == null) {
                log.warn("[PAPER] 페이퍼 클라이언트에서 포지션을 찾을 수 없으나, 봇 내부 포지션을 강제 청산 처리합니다.");
            }
            tradeHandler.handle(currentPosition, exitPrice, 0, 0);
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

            try { Thread.sleep(2000); } catch (InterruptedException ignored) {}

            Trade closedTrade = ((BitgetFuturesApiClient) apiClient).getLatestClosedPosition(pair);
            if (closedTrade != null) {
                double resolvedExitPrice = closedTrade.getExitPrice() > 0
                        ? closedTrade.getExitPrice()
                        : apiClient.getTickerPrice(pair);
                if (resolvedExitPrice <= 0) resolvedExitPrice = exitPrice;
                tradeHandler.handle(currentPosition, resolvedExitPrice, closedTrade.getProfit(), closedTrade.getFee());
            } else {
                double currentPrice = apiClient.getTickerPrice(pair);
                if (currentPrice <= 0) currentPrice = exitPrice;
                log.warn("[{}] 포지션 종료 내역 조회 실패. 현재가({})로 손익을 추정합니다.", pair, currentPrice);
                tradeHandler.handle(currentPosition, currentPrice, 0, 0);
            }
        }
    }

    void handleExternalClose(Position currentPosition) {
        if (currentPosition == null) return;

        log.info("[상태 감지] {} 포지션이 외부에서 청산되었습니다. 거래 내역을 확인합니다.", pair);

        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        Trade closedTrade = ((BitgetFuturesApiClient) apiClient).getLatestClosedPosition(pair);
        if (closedTrade != null) {
            double resolvedExitPrice = closedTrade.getExitPrice() > 0
                    ? closedTrade.getExitPrice()
                    : apiClient.getTickerPrice(pair);
            log.info("[{}] 외부 청산 거래 내역: ExitPrice={}, PnL={}", pair, resolvedExitPrice, closedTrade.getProfit());
            tradeHandler.handle(currentPosition, resolvedExitPrice, closedTrade.getProfit(), closedTrade.getFee());
        } else {
            double currentPrice = apiClient.getTickerPrice(pair);
            if (currentPrice <= 0) {
                log.warn("[{}] 최근 거래 내역 및 현재가 조회 모두 실패 (네트워크 오류). 손익 기록 없이 포지션만 초기화합니다.", pair);
            } else {
                log.warn("[{}] 최근 거래 내역 조회 실패. 현재가({})로 손익을 추정합니다.", pair, currentPrice);
                tradeHandler.handle(currentPosition, currentPrice, 0, 0);
            }
        }

        if (apiClient instanceof BitgetFuturesApiClient) {
            ((BitgetFuturesApiClient) apiClient).cancelAllPlanOrders(pair);
        }
    }
}
