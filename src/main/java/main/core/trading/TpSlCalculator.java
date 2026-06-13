package main.core.trading;

import lombok.extern.slf4j.Slf4j;
import main.api.bitget.BitgetFuturesApiClient;
import main.api.bitget.TradeClient;
import main.analysis.indicator.TechnicalIndicators;
import main.job.TelegramNotifier;
import main.model.domain.Candle;
import main.model.domain.Position;
import main.model.config.SymbolConfig;
import main.model.config.TradingConfig;
import main.model.config.TradingMode;
import main.util.RetryPolicy;

import java.util.List;

@Slf4j
class TpSlCalculator {

    private final TradeClient apiClient;
    private final TradingConfig config;
    private final SymbolConfig symbolConfig;
    private final String pair;
    private final TelegramNotifier telegram;
    private final double fixedStopLossPercent;
    private final double fixedTakeProfitPercent;

    TpSlCalculator(TradeClient apiClient, TradingConfig config, SymbolConfig symbolConfig,
                   String pair, TelegramNotifier telegram,
                   double fixedStopLossPercent, double fixedTakeProfitPercent) {
        this.apiClient = apiClient;
        this.config = config;
        this.symbolConfig = symbolConfig;
        this.pair = pair;
        this.telegram = telegram;
        this.fixedStopLossPercent = fixedStopLossPercent;
        this.fixedTakeProfitPercent = fixedTakeProfitPercent;
    }

    void setAtrTpSl(Position position, List<Candle> candles) {
        try {
            if (candles != null && !candles.isEmpty()) {
                double atr = TechnicalIndicators.calculateATR(candles, 14);
                if (atr > 0) {
                    boolean isLong = "BUY".equals(position.getSide());
                    double entry = position.getEntryPrice();
                    double sl = isLong
                            ? entry - atr * symbolConfig.getSlMult()
                            : entry + atr * symbolConfig.getSlMult();
                    double tp = isLong
                            ? entry + atr * symbolConfig.getTpMult()
                            : entry - atr * symbolConfig.getTpMult();

                    // takeover 시 현재가 위에 TP가 있어야 40830 에러 방지
                    double currentPrice = apiClient.getTickerPrice(pair);
                    if (currentPrice > 0) {
                        if (isLong && tp <= currentPrice) {
                            tp = currentPrice * 1.005 + atr * symbolConfig.getTpMult();
                        } else if (!isLong && tp >= currentPrice) {
                            tp = currentPrice * 0.995 - atr * symbolConfig.getTpMult();
                        }
                    }

                    position.setStopLoss(sl);
                    position.setTakeProfit(tp);
                    log.info("[{}] ATR 기반 손익절 설정 - SL: {}, TP: {} (ATR={}, SLx{}, TPx{})",
                            pair, String.format("%.6f", sl), String.format("%.6f", tp),
                            String.format("%.6f", atr), symbolConfig.getSlMult(), symbolConfig.getTpMult());
                    return;
                }
            }
        } catch (Exception e) {
            log.warn("[{}] ATR 기반 SL/TP 계산 실패 — 고정값 사용: {}", pair, e.getMessage());
        }
        setFixedTpSl(position);
    }

    void setFixedTpSl(Position position) {
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

    void placeExchangeOrders(Position position) {
        if (config.getMode() != TradingMode.LIVE || !(apiClient instanceof BitgetFuturesApiClient)) return;

        BitgetFuturesApiClient futuresClient = (BitgetFuturesApiClient) apiClient;

        boolean slSuccess = RetryPolicy.withRetry(
                () -> futuresClient.placeTpSlOrder(
                        position.getSymbol(), position.getSide(), position.getQuantity(),
                        position.getStopLoss(), "loss_plan"),
                3, 1000, "[" + pair + "] 거래소 손절 주문(SL) 설정");
        if (!slSuccess) {
            log.error("[{}] 거래소 손절 주문(SL) 설정 최종 실패!", pair);
            telegram.notifyError(String.format("🚨 [%s] SL 주문 설정 최종 실패! 수동 확인 및 대응 필요!", pair));
        }

        boolean tpSuccess = RetryPolicy.withRetry(
                () -> futuresClient.placeTpSlOrder(
                        position.getSymbol(), position.getSide(), position.getQuantity(),
                        position.getTakeProfit(), "profit_plan"),
                3, 1000, "[" + pair + "] 거래소 익절 주문(TP) 설정");
        if (!tpSuccess) {
            log.error("[{}] 거래소 익절 주문(TP) 설정 최종 실패!", pair);
            telegram.notifyError(String.format("🚨 [%s] TP 주문 설정 최종 실패! 수동 확인 및 대응 필요!", pair));
        }
    }
}
