package main.model.domain;

import lombok.Data;

@Data
public class OrderResult {
    private String orderId;
    private String status;
    private double filledQuantity;
    private double averagePrice;
}
