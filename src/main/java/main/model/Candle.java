package main.model;

import lombok.Data;

@Data
public class Candle {
    private long timestamp;
    private double open;
    private double high;
    private double low;
    private double close;
    private double volume;
}
