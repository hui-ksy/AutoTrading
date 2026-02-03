package main.model;

import lombok.Data;

@Data
public class CumulativeStats {
    private final int totalTrades;
    private final int wins;
    private final int losses;
    private final double winRate;
    private final double totalProfit;
    private final double totalReturnPercent;
    private final double currentBalance;
}
