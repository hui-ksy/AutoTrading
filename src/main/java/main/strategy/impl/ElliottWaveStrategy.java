package main.strategy.impl;
import main.strategy.core.TradingStrategy;

import main.model.domain.Candle;
import main.model.domain.Position;
import main.model.domain.Signal;
import main.model.config.TradingConfig;

import java.util.List;

public class ElliottWaveStrategy implements TradingStrategy {
    private final TradingConfig config;

    public ElliottWaveStrategy(TradingConfig config) {
        this.config = config;
    }

    @Override
    public Signal generateSignal(List<Candle> candles, String pair, Position position) {
        return Signal.builder().action(Signal.Action.HOLD).build();
    }
}
