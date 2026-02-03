package main.model;

import lombok.Data;

@Data
public class Position {
    private String symbol;
    private String side;
    private double quantity;
    private double entryPrice;
    private double stopLoss;
    private double takeProfit;
    private long entryTimestamp; // [추가] 진입 시점의 캔들 타임스탬프
}
