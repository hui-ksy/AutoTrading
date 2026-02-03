package main.bitget;

import main.model.OrderResult;

/**
 * 주문/포지션 공통 추상화 인터페이스.
 * 구현체는 Spot/Futures/Paper 환경별로 실제 로직을 수행합니다.
 */
public interface TradeClient extends MarketDataClient {
    // 공통
    OrderResult marketBuy(String symbol, double quantity);
    OrderResult marketSell(String symbol, double quantity);

    // 선물 전용 (Spot에서는 UnsupportedOperationException 가능)
    OrderResult marketLong(String symbol, double quantity);
    OrderResult marketShort(String symbol, double quantity);
    OrderResult closeLong(String symbol, double quantity);
    OrderResult closeShort(String symbol, double quantity);

    // 포지션 유무(Spot은 보유 코인 수, Futures는 오픈 포지션 여부 기준)
    boolean hasPosition(String symbol);
}
