package main.model;

import lombok.Data;

@Data
public class Trade {
    private String symbol;
    private String side; // "LONG" or "SHORT"
    private double entryPrice;
    private double exitPrice;
    private double quantity;
    private double profit;
    private double profitPercent;
    private double fee; // 수수료
    private long entryTime;
    private long exitTime;
}
