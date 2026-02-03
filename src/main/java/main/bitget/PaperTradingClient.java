package main.bitget;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import main.model.Candle;
import main.model.OrderResult;
import main.model.Position;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class PaperTradingClient implements TradeClient {

    private final MarketDataClient marketDataClient;
    @Getter
    private double totalEquity;
    private final double feeRate;
    private final Map<String, Position> positions = new HashMap<>();

    public PaperTradingClient(MarketDataClient marketDataClient, double initialBalance, double feeRatePercent) {
        this.marketDataClient = marketDataClient;
        this.totalEquity = initialBalance;
        this.feeRate = feeRatePercent / 100.0;
    }

    public OrderResult openPosition(String symbol, String side, double quantity, double price, int leverage) {
        if (positions.containsKey(symbol)) {
            log.warn("[PAPER] 이미 {} 포지션이 존재합니다.", symbol);
            return null;
        }

        double positionValue = quantity * price;
        double marginRequired = positionValue / leverage;

        if (totalEquity < marginRequired) {
            log.error("[PAPER] 증거금 부족. 필요: {}, 보유: {}", marginRequired, totalEquity);
            return null;
        }

        Position position = new Position();
        position.setSymbol(symbol);
        position.setSide(side);
        position.setQuantity(quantity);
        position.setEntryPrice(price);
        positions.put(symbol, position);

        double fee = positionValue * feeRate;
        totalEquity -= fee;

        log.info("[PAPER] {} {} 진입. 단가: {}, 수량: {}, 수수료: {}", symbol, side, price, quantity, fee);

        OrderResult result = new OrderResult();
        result.setOrderId(String.valueOf(System.currentTimeMillis()));
        result.setStatus("filled");
        result.setAveragePrice(price);
        result.setFilledQuantity(quantity);
        return result;
    }

    public OrderResult closePosition(String symbol, double price) {
        Position position = positions.get(symbol);
        if (position == null) {
            log.warn("[PAPER] 청산할 {} 포지션이 없습니다.", symbol);
            return null;
        }

        double entryValue = position.getQuantity() * position.getEntryPrice();
        double exitValue = position.getQuantity() * price;
        double pnl = "BUY".equals(position.getSide()) ? (exitValue - entryValue) : (entryValue - exitValue);
        double fee = exitValue * feeRate;
        totalEquity += pnl - fee;

        positions.remove(symbol);

        log.info("[PAPER] {} {} 청산. 단가: {}, 손익: {}, 수수료: {}", symbol, position.getSide(), price, pnl, fee);
        
        OrderResult result = new OrderResult();
        result.setOrderId(String.valueOf(System.currentTimeMillis()));
        result.setStatus("filled");
        result.setAveragePrice(price);
        result.setFilledQuantity(position.getQuantity());
        return result;
    }

    @Override
    public List<Candle> getCandles(String symbol, String timeframe, int limit) {
        return marketDataClient.getCandles(symbol, timeframe, limit);
    }
    
    // [추가] 현재가 조회를 marketDataClient에 위임
    public double getTickerPrice(String symbol) {
        return marketDataClient.getTickerPrice(symbol);
    }

    @Override
    public boolean hasPosition(String symbol) {
        return positions.containsKey(symbol);
    }

    // 사용하지 않는 메서드들
    @Override
    public OrderResult marketBuy(String symbol, double quantity) { return null; }
    @Override
    public OrderResult marketSell(String symbol, double quantity) { return null; }
    @Override
    public OrderResult marketLong(String symbol, double quantity) { return null; }
    @Override
    public OrderResult marketShort(String symbol, double quantity) { return null; }
    @Override
    public OrderResult closeLong(String symbol, double quantity) { return null; }
    @Override
    public OrderResult closeShort(String symbol, double quantity) { return null; }
}
