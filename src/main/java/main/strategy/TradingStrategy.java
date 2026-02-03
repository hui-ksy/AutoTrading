package main.strategy;

import main.model.Candle;
import main.model.Position;
import main.model.Signal;

import java.util.List;

public interface TradingStrategy {
    /**
     * 캔들 데이터와 현재 포지션 상태를 기반으로 매매 신호를 생성합니다.
     * @param candles 캔들 데이터 리스트
     * @param pair 거래 페어
     * @param position 현재 보유 중인 포지션 (없으면 null)
     * @return 매매 신호 (BUY, SHORT, EXIT, HOLD)
     */
    Signal generateSignal(List<Candle> candles, String pair, Position position);
}
