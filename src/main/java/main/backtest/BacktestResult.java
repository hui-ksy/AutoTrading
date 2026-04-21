package main.backtest;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class BacktestResult {

    // 기간
    long startTimestamp;
    long endTimestamp;
    int totalCandles;

    // 거래 통계
    int totalTrades;
    int winTrades;
    int lossTrades;
    double winRate;           // %

    // 수익률
    double initialBalance;
    double finalBalance;
    double totalReturnPct;    // %
    double totalPnl;

    // 리스크 지표
    double maxDrawdownPct;    // %
    double sharpeRatio;
    double avgWinPct;
    double avgLossPct;
    double profitFactor;      // gross profit / gross loss

    // 개별 거래 내역
    List<TradeRecord> trades;

    @Value
    @Builder
    public static class TradeRecord {
        String side;          // "LONG" or "SHORT"
        long entryTimestamp;
        long exitTimestamp;
        double entryPrice;
        double exitPrice;
        double pnlPct;        // %
        double pnl;           // USDT
        String exitReason;    // "TP" / "SL" / "SIGNAL"
    }

    @Override
    public String toString() {
        return String.format(
            "=== Backtest Result ===\n" +
            "기간: %d 캔들\n" +
            "총 거래: %d  (승: %d / 패: %d)  승률: %.1f%%\n" +
            "최종 잔고: %.2f  수익률: %.2f%%\n" +
            "최대 낙폭: %.2f%%  Sharpe: %.2f\n" +
            "Profit Factor: %.2f  평균 수익: %.2f%%  평균 손실: %.2f%%",
            totalCandles,
            totalTrades, winTrades, lossTrades, winRate,
            finalBalance, totalReturnPct,
            maxDrawdownPct, sharpeRatio,
            profitFactor, avgWinPct, avgLossPct
        );
    }
}