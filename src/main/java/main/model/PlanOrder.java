package main.model;

import lombok.Data;

@Data
public class PlanOrder {
    private String orderId; // [추가] 주문 ID
    private String planType; // "profit_plan" or "loss_plan"
    private double triggerPrice;
}
