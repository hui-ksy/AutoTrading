package main;

import lombok.extern.slf4j.Slf4j;
import main.account.LiveAccountBalanceProvider;
import main.job.TelegramNotifier;
import main.model.CumulativeStats;
import main.model.Position;
import main.model.Trade;
import main.model.TradingConfig;
import main.model.TradingMode;

import java.util.ArrayList;
import java.util.List;

@Slf4j
class TradeRecorder {

  private final List<Trade> tradeHistory;
  private final TradingConfig config;
  private final LiveAccountBalanceProvider balanceProvider;
  private final TelegramNotifier telegram;
  private double initialBalance;

  TradeRecorder(
      TradingConfig config,
      LiveAccountBalanceProvider balanceProvider,
      TelegramNotifier telegram) {
    this.config = config;
    this.balanceProvider = balanceProvider;
    this.telegram = telegram;
    this.tradeHistory = new ArrayList<>();
    this.initialBalance = 0;
  }

  void setInitialBalance(double initialBalance) {
    this.initialBalance = initialBalance;
  }

  synchronized void record(
      Position closedPosition,
      double exitPrice,
      double pnlFromApi,
      double feeFromApi) {
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

      double grossPnl =
          ("BUY".equals(trade.getSide()))
              ? (exitValue - entryValue)
              : (entryValue - exitValue);
      netPnl = grossPnl - fee;
    }

    trade.setProfit(netPnl);

    double marginUsed = (trade.getEntryPrice() * trade.getQuantity()) / config.getLeverage();
    double pnlPercent = (marginUsed > 0) ? (netPnl / marginUsed) * 100 : 0;
    trade.setProfitPercent(pnlPercent);

    tradeHistory.add(trade);

    CumulativeStats stats = calculateStats();
    logAndNotify(trade, stats);
  }

  CumulativeStats calculateStats() {
    int wins = 0;
    double totalProfit = 0;
    for (Trade trade : tradeHistory) {
      if (trade.getProfit() > 0) wins++;
      totalProfit += trade.getProfit();
    }

    int totalTrades = tradeHistory.size();
    int losses = totalTrades - wins;
    double winRate = (totalTrades > 0) ? (double) wins / totalTrades * 100 : 0;

    double equity = balanceProvider.getTotalEquity();
    double currentBalance =
        (config.getMode() == TradingMode.LIVE && equity > 0)
            ? equity
            : initialBalance + totalProfit;
    double totalReturnPercent =
        (initialBalance > 0) ? (currentBalance / initialBalance - 1) * 100 : 0;

    return new CumulativeStats(
        totalTrades, wins, losses, winRate, totalProfit, totalReturnPercent, currentBalance);
  }

  private void logAndNotify(Trade trade, CumulativeStats stats) {
    log.info(
        "[거래 종료] {}, 손익: {}${} ({}%), 수수료: ${}",
        trade.getSymbol(),
        (trade.getProfit() >= 0 ? "+" : ""),
        String.format("%.2f", trade.getProfit()),
        String.format("%.2f", trade.getProfitPercent()),
        String.format("%.2f", trade.getFee()));
    log.info(
        "[누적 통계] 총 거래 {}, 승률 {}%, 총 손익 {}${}",
        stats.getTotalTrades(),
        String.format("%.2f", stats.getWinRate()),
        (stats.getTotalProfit() >= 0 ? "+" : ""),
        String.format("%.2f", stats.getTotalProfit()));

    telegram.notifyExitSummary(trade, stats);
  }
}
