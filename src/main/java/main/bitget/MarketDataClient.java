package main.bitget;

import main.model.Candle;

import java.util.List;

/**
 * 공통 시세 조회 인터페이스.
 * Spot/Futures 클라이언트가 공통으로 구현하여, 호출부(Object 캐스팅)를 줄입니다.
 */
public interface MarketDataClient {
    /**
     * 심볼/타임프레임 기준 캔들 조회
     * @param symbol 거래 페어 (예: BTCUSDT)
     * @param timeframe 타임프레임 (예: 1m, 5m, 1h)
     * @param limit 조회 개수
     * @return 캔들 리스트 (가장 오래된 순서 -> 최신 순서)
     */
    List<Candle> getCandles(String symbol, String timeframe, int limit);

    /**
     * [추가] 현재가(Ticker) 조회
     * @param symbol 거래 페어
     * @return 현재가 (실패 시 0.0)
     */
    default double getTickerPrice(String symbol) {
        return 0.0;
    }
}
