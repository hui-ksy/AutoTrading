package main.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class Signal {
    public enum Action {
        BUY,    // Long 진입
        SHORT,  // Short 진입
        EXIT,   // 포지션 청산 (Long/Short 구분 없이 현재 포지션 종료)
        HOLD    // 관망
    }

    private Action action;
    private double entryPrice;
    private double stopLoss;
    private double takeProfit;
    private String reason;
}
