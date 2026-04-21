package main.backtest;

import lombok.extern.slf4j.Slf4j;
import main.model.Candle;
import main.model.Position;
import main.model.Signal;
import main.strategy.TradingStrategy;

import java.util.ArrayList;
import java.util.List;

/**
 * 백테스트 엔진.
 * <p>
 * 사용법:
 * <pre>
 *   Backtester bt = new Backtester(strategy, candles, 10_000.0, 0.06, 50);
 *   BacktestResult result = bt.run();
 *   System.out.println(result);
 * </pre>
 *
 * @param strategy        테스트할 전략
 * @param allCandles      전체 캔들 (오래된 것부터 최신 순으로 정렬)
 * @param initialBalance  초기 잔고 (USDT)
 * @param feeRatePct      편도 수수료율 (예: 0.06 = 0.06%)
 * @param warmupCandles   전략이 지표를 계산하기 위해 필요한 워밍업 캔들 수
 */
@Slf4j
public class Backtester {

    private final TradingStrategy strategy;
    private final List<Candle> allCandles;
    private final double initialBalance;
    private final double feeRatePct;   // 0.06 = 0.06%
    private final int warmupCandles;
    private final String pair;
    private final int leverage;        // 레버리지 배수 (1 = 레버리지 없음)

    public Backtester(TradingStrategy strategy,
                      List<Candle> allCandles,
                      double initialBalance,
                      double feeRatePct,
                      int warmupCandles,
                      String pair) {
        this(strategy, allCandles, initialBalance, feeRatePct, warmupCandles, pair, 1);
    }

    public Backtester(TradingStrategy strategy,
                      List<Candle> allCandles,
                      double initialBalance,
                      double feeRatePct,
                      int warmupCandles,
                      String pair,
                      int leverage) {
        this.strategy = strategy;
        this.allCandles = allCandles;
        this.initialBalance = initialBalance;
        this.feeRatePct = feeRatePct;
        this.warmupCandles = warmupCandles;
        this.pair = pair;
        this.leverage = Math.max(1, leverage);
    }

    public BacktestResult run() {
        double balance = initialBalance;
        double peak = initialBalance;
        double maxDrawdown = 0.0;

        Position position = null;
        List<BacktestResult.TradeRecord> trades = new ArrayList<>();
        List<Double> returns = new ArrayList<>();  // 거래별 수익률 (Sharpe 계산용)

        for (int i = warmupCandles; i < allCandles.size(); i++) {
            List<Candle> window = allCandles.subList(0, i + 1);
            Candle current = allCandles.get(i);
            double close = current.getClose();
            double high = current.getHigh();
            double low = current.getLow();

            // ── 포지션 보유 중: SL/TP 선체크 ──────────────────────────
            if (position != null) {
                String exitReason = null;
                double exitPrice = 0;

                if ("BUY".equals(position.getSide())) {
                    if (low <= position.getStopLoss()) {
                        exitReason = "SL";
                        exitPrice = position.getStopLoss();
                    } else if (high >= position.getTakeProfit()) {
                        exitReason = "TP";
                        exitPrice = position.getTakeProfit();
                    }
                } else { // SHORT
                    if (high >= position.getStopLoss()) {
                        exitReason = "SL";
                        exitPrice = position.getStopLoss();
                    } else if (low <= position.getTakeProfit()) {
                        exitReason = "TP";
                        exitPrice = position.getTakeProfit();
                    }
                }

                if (exitReason != null) {
                    BacktestResult.TradeRecord record = closePosition(
                        position, exitPrice, current.getTimestamp(), exitReason, balance, feeRatePct);
                    trades.add(record);
                    balance += record.getPnl();
                    returns.add(record.getPnlPct());
                    position = null;

                    peak = Math.max(peak, balance);
                    double dd = (peak - balance) / peak * 100.0;
                    maxDrawdown = Math.max(maxDrawdown, dd);
                    continue;
                }
            }

            // ── 전략 신호 생성 ─────────────────────────────────────────
            Signal signal = strategy.generateSignal(window, pair, position);
            if (signal == null) continue;

            // ── EXIT 신호 처리 ────────────────────────────────────────
            if (signal.getAction() == Signal.Action.EXIT && position != null) {
                BacktestResult.TradeRecord record = closePosition(
                    position, close, current.getTimestamp(), "SIGNAL", balance, feeRatePct);
                trades.add(record);
                balance += record.getPnl();
                returns.add(record.getPnlPct());
                position = null;

                peak = Math.max(peak, balance);
                double dd = (peak - balance) / peak * 100.0;
                maxDrawdown = Math.max(maxDrawdown, dd);
            }

            // ── 신규 진입 ─────────────────────────────────────────────
            if (position == null
                && (signal.getAction() == Signal.Action.BUY || signal.getAction() == Signal.Action.SHORT)) {

                // 매매 수수료 (진입) — 노셔널 기준이므로 레버리지 비례
                double entryFee = balance * (feeRatePct / 100.0) * leverage;
                balance -= entryFee;
                if (balance <= 0) break; // 수수료만으로 잔고 소진 (초고레버 방어)

                position = new Position();
                position.setSymbol(pair);
                position.setSide(signal.getAction() == Signal.Action.BUY ? "BUY" : "SHORT");
                position.setEntryPrice(close);
                position.setStopLoss(signal.getStopLoss() > 0 ? signal.getStopLoss() : defaultStopLoss(signal.getAction(), close));
                position.setTakeProfit(signal.getTakeProfit() > 0 ? signal.getTakeProfit() : defaultTakeProfit(signal.getAction(), close));
                position.setEntryTimestamp(current.getTimestamp());
                position.setQuantity(balance * leverage / close); // 레버리지 적용 수량

                log.debug("[{}] 진입 {} @ {:.2f}  SL={:.2f}  TP={:.2f}",
                    i, position.getSide(), close, position.getStopLoss(), position.getTakeProfit());
            }
        }

        // ── 마지막 미청산 포지션 강제 청산 ───────────────────────────
        if (position != null) {
            Candle last = allCandles.get(allCandles.size() - 1);
            BacktestResult.TradeRecord record = closePosition(
                position, last.getClose(), last.getTimestamp(), "END", balance, feeRatePct);
            trades.add(record);
            balance += record.getPnl();
            returns.add(record.getPnlPct());
        }

        return buildResult(trades, returns, balance, maxDrawdown);
    }

    // ── 청산 로직 ─────────────────────────────────────────────────────────

    private BacktestResult.TradeRecord closePosition(
            Position pos, double exitPrice, long exitTimestamp,
            String exitReason, double balance, double feeRatePct) {

        double entryPrice = pos.getEntryPrice();
        double rawPnlPct;

        if ("BUY".equals(pos.getSide())) {
            rawPnlPct = (exitPrice - entryPrice) / entryPrice * 100.0;
        } else {
            rawPnlPct = (entryPrice - exitPrice) / entryPrice * 100.0;
        }

        // 레버리지 적용 + 청산 수수료 차감 (수수료도 노셔널 기준)
        double leveragedPnlPct = rawPnlPct * leverage;
        double exitFeePct = (feeRatePct / 100.0) * leverage;
        double netPnlPct = leveragedPnlPct - exitFeePct * 100.0;
        // 강제청산: 손실이 마진(잔고) 100% 초과 불가
        if (netPnlPct < -100.0) netPnlPct = -100.0;
        double pnl = balance * (netPnlPct / 100.0);

        return BacktestResult.TradeRecord.builder()
            .side(pos.getSide())
            .entryTimestamp(pos.getEntryTimestamp())
            .exitTimestamp(exitTimestamp)
            .entryPrice(entryPrice)
            .exitPrice(exitPrice)
            .pnlPct(netPnlPct)
            .pnl(pnl)
            .exitReason(exitReason)
            .build();
    }

    // ── 지표 계산 ─────────────────────────────────────────────────────────

    private BacktestResult buildResult(List<BacktestResult.TradeRecord> trades,
                                       List<Double> returns,
                                       double finalBalance,
                                       double maxDrawdown) {
        int total = trades.size();
        int wins = (int) trades.stream().filter(t -> t.getPnlPct() > 0).count();
        int losses = total - wins;

        double winRate = total > 0 ? (double) wins / total * 100.0 : 0;
        double totalReturnPct = (finalBalance - initialBalance) / initialBalance * 100.0;

        double avgWin = trades.stream().filter(t -> t.getPnlPct() > 0)
            .mapToDouble(BacktestResult.TradeRecord::getPnlPct).average().orElse(0);
        double avgLoss = trades.stream().filter(t -> t.getPnlPct() <= 0)
            .mapToDouble(BacktestResult.TradeRecord::getPnlPct).average().orElse(0);

        double grossProfit = trades.stream().filter(t -> t.getPnl() > 0)
            .mapToDouble(BacktestResult.TradeRecord::getPnl).sum();
        double grossLoss = Math.abs(trades.stream().filter(t -> t.getPnl() <= 0)
            .mapToDouble(BacktestResult.TradeRecord::getPnl).sum());
        double profitFactor = grossLoss > 0 ? grossProfit / grossLoss : grossProfit > 0 ? Double.MAX_VALUE : 0;

        double sharpe = calculateSharpe(returns);

        long start = allCandles.isEmpty() ? 0 : allCandles.get(0).getTimestamp();
        long end = allCandles.isEmpty() ? 0 : allCandles.get(allCandles.size() - 1).getTimestamp();

        return BacktestResult.builder()
            .startTimestamp(start)
            .endTimestamp(end)
            .totalCandles(allCandles.size())
            .totalTrades(total)
            .winTrades(wins)
            .lossTrades(losses)
            .winRate(winRate)
            .initialBalance(initialBalance)
            .finalBalance(finalBalance)
            .totalReturnPct(totalReturnPct)
            .totalPnl(finalBalance - initialBalance)
            .maxDrawdownPct(maxDrawdown)
            .sharpeRatio(sharpe)
            .avgWinPct(avgWin)
            .avgLossPct(avgLoss)
            .profitFactor(profitFactor)
            .trades(trades)
            .build();
    }

    /**
     * 연율화하지 않은 단순 Sharpe (평균 수익 / 표준편차).
     * 연율화하려면 반환값에 sqrt(연간 거래 수)를 곱할 것.
     */
    private double calculateSharpe(List<Double> returns) {
        if (returns.size() < 2) return 0;
        double mean = returns.stream().mapToDouble(d -> d).average().orElse(0);
        double variance = returns.stream()
            .mapToDouble(r -> Math.pow(r - mean, 2))
            .average().orElse(0);
        double stdDev = Math.sqrt(variance);
        return stdDev == 0 ? 0 : mean / stdDev;
    }

    private double defaultStopLoss(Signal.Action action, double price) {
        return action == Signal.Action.BUY ? price * 0.98 : price * 1.02;
    }

    private double defaultTakeProfit(Signal.Action action, double price) {
        return action == Signal.Action.BUY ? price * 1.04 : price * 0.96;
    }
}