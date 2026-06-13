package main.strategy.impl;
import main.strategy.core.TradingStrategy;

import main.model.domain.Candle;
import main.model.domain.Position;
import main.model.domain.Signal;
import main.model.config.TradingConfig;

import java.util.List;

public class DonchianBreakoutStrategy implements TradingStrategy {
    private final TradingConfig config;

    public DonchianBreakoutStrategy(TradingConfig config) {
        this.config = config;
    }

    @Override
    public Signal generateSignal(List<Candle> candles, String pair, Position position) {
        // 이 전략은 청산 로직을 포함하지 않으므로, 포지션이 있으면 HOLD
        if (position != null) {
            return Signal.builder().action(Signal.Action.HOLD).build();
        }
        
        // ... (기존 진입 로직)
        return Signal.builder().action(Signal.Action.HOLD).build();
    }
}
