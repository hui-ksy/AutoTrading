package main.strategy;

import main.model.Candle;
import main.model.Position;
import main.model.Signal;
import main.model.TradingConfig;

import java.util.List;

public class MacdAtrTrendStrategy implements TradingStrategy {
    private final TradingConfig config;

    public MacdAtrTrendStrategy(TradingConfig config) {
        this.config = config;
    }

    @Override
    public Signal generateSignal(List<Candle> candles, String pair, Position position) {
        return Signal.builder().action(Signal.Action.HOLD).build();
    }
}
