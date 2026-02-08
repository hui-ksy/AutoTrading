package main.bitget;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import main.model.Candle;
import main.model.OrderResult;
import main.model.PlanOrder;
import main.model.Position;
import main.model.Trade;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
public class BitgetFuturesApiClient implements MarketDataClient, TradeClient {

    private static final String BASE_URL = "https://api.bitget.com";
    private final OkHttpClient httpClient;
    private final Gson gson;

    private final String apiKey;
    private final String secretKey;
    private final String passphrase;
    private final String productType;
    private final String marginMode;
    private final String marginCoin;

    private final Map<String, Integer> pricePlaces = new ConcurrentHashMap<>();

    public BitgetFuturesApiClient(String apiKey, String secretKey, String passphrase,
                                  String productType, String marginMode) {
        this.apiKey = apiKey;
        this.secretKey = secretKey;
        this.passphrase = passphrase;
        this.productType = productType;
        this.marginMode = marginMode;
        this.marginCoin = "USDT";
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
        this.gson = new Gson();
    }

    // ===== TradeClient 인터페이스 구현 (시장가) =====
    @Override
    public OrderResult marketBuy(String symbol, double quantity) { return marketLong(symbol, quantity); }
    @Override
    public OrderResult marketSell(String symbol, double quantity) { return marketShort(symbol, quantity); }
    @Override
    public OrderResult marketLong(String symbol, double quantity) { return placeFuturesOrder(symbol, "open", "buy", "market", quantity, null); }
    @Override
    public OrderResult marketShort(String symbol, double quantity) { return placeFuturesOrder(symbol, "open", "sell", "market", quantity, null); }
    
    @Override
    public OrderResult closeLong(String symbol, double quantity) {
        return placeFuturesOrder(symbol, "close", "sell", "market", quantity, null);
    }
    @Override
    public OrderResult closeShort(String symbol, double quantity) {
        return placeFuturesOrder(symbol, "close", "buy", "market", quantity, null);
    }

    public OrderResult closeEntirePosition(String symbol) {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("symbol", symbol);
            data.put("productType", productType);
            data.put("marginCoin", marginCoin);
            
            JsonObject response = post("/api/v2/mix/order/close-positions", data);
            if (response != null && "00000".equals(response.get("code").getAsString())) {
                OrderResult result = new OrderResult();
                result.setStatus("filled");
                return result;
            }
        } catch (Exception e) {
            log.error("{} 포지션 전체 청산 실패", symbol, e);
        }
        return null;
    }

    // ===== TP/SL 주문 =====
    public boolean placeTpSlOrder(String symbol, String side, double quantity, double triggerPrice, String planType) {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("symbol", symbol);
            data.put("productType", productType);
            data.put("marginCoin", marginCoin);
            data.put("planType", planType);
            
            int pricePlace = getPricePlace(symbol);
            String triggerPriceStr = BigDecimal.valueOf(triggerPrice).setScale(pricePlace, RoundingMode.HALF_UP).toPlainString();
            data.put("triggerPrice", triggerPriceStr);
            
            data.put("holdSide", "BUY".equalsIgnoreCase(side) ? "long" : "short");
            
            String sizeStr = BigDecimal.valueOf(quantity).setScale(4, RoundingMode.DOWN).toPlainString();
            data.put("size", sizeStr);
            
            JsonObject response = post("/api/v2/mix/order/place-tpsl-order", data);
            
            if (response != null && "00000".equals(response.get("code").getAsString())) {
                return true;
            } else {
                String errorCode = (response != null && response.has("code")) ? response.get("code").getAsString() : "N/A";
                String errorMessage = (response != null && response.has("msg")) ? response.get("msg").getAsString() : "N/A";
                log.error("[TP/SL 응답] {} - 실패 (코드: {}, 메시지: {}): {}", planType, errorCode, errorMessage, response);
                return false;
            }
        } catch (Exception e) {
            log.error("TP/SL 주문 설정 중 예외 발생", e);
            return false;
        }
    }
    
    public boolean cancelAllPlanOrders(String symbol) {
        try {
            List<PlanOrder> orders = getPlanOrders(symbol);
            if (orders.isEmpty()) {
                return true;
            }
            
            boolean allSuccess = true;
            
            for (PlanOrder order : orders) {
                boolean success = cancelPlanOrder(symbol, order.getOrderId(), order.getPlanType());
                if (!success) allSuccess = false;
                try { Thread.sleep(100); } catch (InterruptedException ignored) {} 
            }
            
            return allSuccess;
            
        } catch (Exception e) {
            log.error("Plan Order 전체 취소 중 오류", e);
            return false;
        }
    }
    
    private boolean cancelPlanOrder(String symbol, String orderId, String planType) {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("symbol", symbol);
            data.put("productType", productType);
            data.put("orderId", orderId);
            data.put("planType", planType);
            
            JsonObject response = post("/api/v2/mix/order/cancel-plan-order", data);
            
            if (response != null && "00000".equals(response.get("code").getAsString())) {
                return true;
            } else {
                String errorCode = (response != null && response.has("code")) ? response.get("code").getAsString() : "N/A";
                String errorMessage = (response != null && response.has("msg")) ? response.get("msg").getAsString() : "N/A";
                log.error("[Plan Order 취소] ID: {} - 실패 (코드: {}, 메시지: {}): {}", orderId, errorCode, errorMessage, response);
                return false;
            }
        } catch (Exception e) {
            log.error("[Plan Order 취소] ID: {} - 예외 발생", orderId, e);
            return false;
        }
    }
    
    public List<PlanOrder> getPlanOrders(String symbol) {
        List<PlanOrder> allOrders = new ArrayList<>();
        allOrders.addAll(getPlanOrdersByType(symbol, "profit_plan"));
        allOrders.addAll(getPlanOrdersByType(symbol, "loss_plan"));
        return allOrders;
    }
    
    private List<PlanOrder> getPlanOrdersByType(String symbol, String planType) {
        List<PlanOrder> planOrders = new ArrayList<>();
        try {
            String endpoint = "/api/v2/mix/order/current-plan-order";
            String queryString = "?symbol=" + symbol + "&productType=" + productType + "&planType=" + planType;
            
            JsonObject response = get(endpoint, queryString);
            
            if (response != null && "00000".equals(response.get("code").getAsString())) {
                JsonArray data = response.getAsJsonArray("data");
                for (JsonElement item : data) {
                    JsonObject order = item.getAsJsonObject();
                    PlanOrder planOrder = new PlanOrder();
                    if (order.has("orderId") && !order.get("orderId").isJsonNull()) {
                        planOrder.setOrderId(order.get("orderId").getAsString());
                    }
                    planOrder.setPlanType(order.get("planType").getAsString());
                    planOrder.setTriggerPrice(order.get("triggerPrice").getAsDouble());
                    planOrders.add(planOrder);
                }
            }
        } catch (Exception e) {
            log.error("Plan Order 조회 실패 ({}) : {}", planType, symbol, e);
        }
        return planOrders;
    }

    // ===== 계정 설정 =====
    public boolean setLeverage(String symbol, int leverage, String holdSide) {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("symbol", symbol);
            data.put("productType", productType);
            data.put("marginCoin", marginCoin);
            data.put("leverage", String.valueOf(leverage));
            data.put("holdSide", holdSide);
            JsonObject response = post("/api/v2/mix/account/set-leverage", data);
            
            if (response != null && "00000".equals(response.get("code").getAsString())) {
                log.info("[레버리지 설정] {} {} {}x 성공", symbol, holdSide, leverage);
                return true;
            } else {
                log.error("[레버리지 설정] {} {} {}x 실패: {}", symbol, holdSide, leverage, response);
                return false;
            }
        } catch (Exception e) { log.error("레버리지 설정 실패", e); return false; }
    }

    public boolean setMarginMode(String symbol, String marginMode) {
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("symbol", symbol);
            data.put("productType", productType);
            data.put("marginCoin", marginCoin);
            data.put("marginMode", marginMode);
            JsonObject response = post("/api/v2/mix/account/set-margin-mode", data);
            return response != null && "00000".equals(response.get("code").getAsString());
        } catch (Exception e) { log.error("마진 모드 설정 실패", e); return false; }
    }

    // ===== 정보 조회 =====
    @Override
    public List<Candle> getCandles(String symbol, String timeframe, int limit) {
        List<Candle> allCandles = new ArrayList<>();
        try {
            String granularity = convertTimeframeToGranularity(timeframe);
            int batchSize = 100;
            Long endTime = null;
            
            while (allCandles.size() < limit) {
                int currentLimit = Math.min(batchSize, limit - allCandles.size());
                
                StringBuilder urlBuilder = new StringBuilder(BASE_URL)
                        .append("/api/v2/mix/market/candles?symbol=").append(symbol)
                        .append("&productType=").append(productType)
                        .append("&granularity=").append(granularity)
                        .append("&limit=").append(currentLimit);
                
                if (endTime != null) {
                    urlBuilder.append("&endTime=").append(endTime);
                }
                             
                Request request = new Request.Builder().url(urlBuilder.toString()).get().build();
                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        log.error("캔들 조회 실패: HTTP {}", response.code());
                        break;
                    }
                    
                    List<Candle> batch = parseCandles(response.body().string());
                    if (batch.isEmpty()) break;
                    
                    allCandles.addAll(0, batch);
                    endTime = batch.get(0).getTimestamp() - 1;
                    Thread.sleep(300);
                }
            }
            
            if (allCandles.size() > 1) {
                if (allCandles.get(0).getTimestamp() > allCandles.get(allCandles.size() - 1).getTimestamp()) {
                    Collections.reverse(allCandles);
                }
            }
            
            if (allCandles.size() > limit) {
                allCandles = allCandles.subList(allCandles.size() - limit, allCandles.size());
            }
            
            return allCandles;
            
        } catch (Exception e) { 
            log.error("캔들 데이터 조회 중 오류", e); 
            return new ArrayList<>(); 
        }
    }
    
    @Override
    public double getTickerPrice(String symbol) {
        try {
            String url = BASE_URL + "/api/v2/mix/market/ticker?symbol=" + symbol + "&productType=" + productType;
            Request request = new Request.Builder().url(url).get().build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) return 0.0;
                
                JsonObject jsonResponse = gson.fromJson(response.body().string(), JsonObject.class);
                if ("00000".equals(jsonResponse.get("code").getAsString())) {
                    JsonArray data = jsonResponse.getAsJsonArray("data");
                    if (data.size() > 0) {
                        return data.get(0).getAsJsonObject().get("lastPr").getAsDouble();
                    }
                }
            }
        } catch (Exception e) {
            log.error("현재가 조회 실패: {}", symbol, e);
        }
        return 0.0;
    }
    
    @Override
    public boolean hasPosition(String symbol) {
        return getSinglePosition(symbol) != null;
    }

    public Position getSinglePosition(String symbol) {
        try {
            String endpoint = "/api/v2/mix/position/single-position";
            String queryString = "?symbol=" + symbol + "&productType=" + productType + "&marginCoin=" + marginCoin;
            JsonObject response = get(endpoint, queryString);
            if (response != null && "00000".equals(response.get("code").getAsString())) {
                JsonArray data = response.getAsJsonArray("data");
                if (data.size() > 0) {
                    return parsePosition(data.get(0).getAsJsonObject());
                }
            }
        } catch (Exception e) { log.error("단일 포지션 조회 실패", e); }
        return null;
    }

    public List<Position> getAllPositions() {
        List<Position> positions = new ArrayList<>();
        try {
            String endpoint = "/api/v2/mix/position/all-position";
            String queryString = "?productType=" + productType + "&marginCoin=" + marginCoin;
            JsonObject response = get(endpoint, queryString);
            if (response != null && "00000".equals(response.get("code").getAsString())) {
                JsonArray data = response.getAsJsonArray("data");
                for (JsonElement item : data) {
                    Position position = parsePosition(item.getAsJsonObject());
                    if (position != null) {
                        positions.add(position);
                    }
                }
            }
        } catch (Exception e) { log.error("모든 포지션 조회 실패", e); }
        return positions;
    }

    public double getAccountEquity(String coin) {
        JsonObject account = getAccountData(coin);
        if (account != null) {
            JsonElement equityElement = account.get("accountEquity");
            if (equityElement != null && !equityElement.isJsonNull()) {
                return equityElement.getAsDouble();
            }
        }
        return 0.0;
    }
    
    public double getAvailableBalance(String coin) {
        JsonObject account = getAccountData(coin);
        if (account != null) {
            JsonElement availableElement = account.get("available"); 
            if (availableElement != null && !availableElement.isJsonNull()) {
                return availableElement.getAsDouble();
            }
        }
        return 0.0;
    }

    public int getPricePlace(String symbol) {
        if (pricePlaces.containsKey(symbol)) {
            return pricePlaces.get(symbol);
        }
        try {
            String endpoint = "/api/v2/mix/market/contracts";
            String queryString = "?productType=" + productType + "&symbol=" + symbol;
            JsonObject response = get(endpoint, queryString);
            if (response != null && "00000".equals(response.get("code").getAsString())) {
                JsonArray data = response.getAsJsonArray("data");
                if (data.size() > 0) {
                    JsonObject contract = data.get(0).getAsJsonObject();
                    int place = contract.get("pricePlace").getAsInt();
                    pricePlaces.put(symbol, place);
                    return place;
                }
            }
        } catch (Exception e) {
            log.error("{}의 소수점 자릿수 조회 실패", symbol, e);
        }
        return 2;
    }

    public Trade getLatestClosedPosition(String symbol) {
        try {
            String endpoint = "/api/v2/mix/position/history-position";
            String queryString = "?symbol=" + symbol + "&productType=" + productType + "&limit=1";
            
            JsonObject response = get(endpoint, queryString);
            
            if (response != null && "00000".equals(response.get("code").getAsString())) {
                JsonObject data = response.getAsJsonObject("data");
                if (data != null && data.has("list")) {
                    JsonArray list = data.getAsJsonArray("list");
                    if (list.size() > 0) {
                        JsonObject posData = list.get(0).getAsJsonObject();
                        Trade trade = new Trade();
                        
                        // [수정] 필드명 변경 및 파싱
                        if (posData.has("openPriceAvg") && !posData.get("openPriceAvg").isJsonNull()) {
                            trade.setEntryPrice(posData.get("openPriceAvg").getAsDouble());
                        }
                        if (posData.has("closePriceAvg") && !posData.get("closePriceAvg").isJsonNull()) {
                            trade.setExitPrice(posData.get("closePriceAvg").getAsDouble());
                        }
                        if (posData.has("netProfit") && !posData.get("netProfit").isJsonNull()) {
                            trade.setProfit(posData.get("netProfit").getAsDouble());
                        }
                        
                        double openFee = posData.has("openFee") ? Math.abs(posData.get("openFee").getAsDouble()) : 0.0;
                        double closeFee = posData.has("closeFee") ? Math.abs(posData.get("closeFee").getAsDouble()) : 0.0;
                        trade.setFee(openFee + closeFee);
                        
                        return trade;
                    }
                }
            }
        } catch (Exception e) {
            log.error("{} 최근 포지션 종료 내역 조회 실패", symbol, e);
        }
        return null;
    }

    private JsonObject getAccountData(String coin) {
        try {
            String endpoint = "/api/v2/mix/account/accounts";
            String queryString = "?productType=" + productType;
            JsonObject response = get(endpoint, queryString);
            if (response != null && "00000".equals(response.get("code").getAsString())) {
                for (JsonElement item : response.getAsJsonArray("data")) {
                    JsonObject account = item.getAsJsonObject();
                    if (account.has("marginCoin") && coin.equalsIgnoreCase(account.get("marginCoin").getAsString())) {
                        return account;
                    }
                }
            }
        } catch (Exception e) { log.error("계좌 데이터 조회 실패", e); }
        return null;
    }

    private JsonObject get(String endpoint, String queryString) throws Exception {
        String timestamp = String.valueOf(System.currentTimeMillis());
        String signMessage = timestamp + "GET" + endpoint + queryString;
        String signature = sign(signMessage);
        Request request = new Request.Builder()
                .url(BASE_URL + endpoint + queryString)
                .get()
                .addHeader("ACCESS-KEY", apiKey)
                .addHeader("ACCESS-SIGN", signature)
                .addHeader("ACCESS-TIMESTAMP", timestamp)
                .addHeader("ACCESS-PASSPHRASE", passphrase)
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            return gson.fromJson(response.body().string(), JsonObject.class);
        }
    }

    private JsonObject post(String endpoint, Map<String, Object> data) throws Exception {
        String timestamp = String.valueOf(System.currentTimeMillis());
        String body = gson.toJson(data);
        String signMessage = timestamp + "POST" + endpoint + body;
        String signature = sign(signMessage);
        Request request = new Request.Builder()
                .url(BASE_URL + endpoint)
                .post(okhttp3.RequestBody.create(body, okhttp3.MediaType.parse("application/json")))
                .addHeader("ACCESS-KEY", apiKey)
                .addHeader("ACCESS-SIGN", signature)
                .addHeader("ACCESS-TIMESTAMP", timestamp)
                .addHeader("ACCESS-PASSPHRASE", passphrase)
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            return gson.fromJson(response.body().string(), JsonObject.class);
        }
    }

    private OrderResult placeFuturesOrder(String symbol, String tradeSide, String side, String orderType, double quantity, Double price) {
        try {
            Map<String, Object> orderData = new HashMap<>();
            orderData.put("symbol", symbol);
            orderData.put("productType", productType);
            orderData.put("marginMode", marginMode);
            orderData.put("marginCoin", marginCoin);
            
            String sizeStr = BigDecimal.valueOf(quantity).setScale(4, RoundingMode.DOWN).toPlainString();
            orderData.put("size", sizeStr);
            
            orderData.put("side", side);
            orderData.put("tradeSide", tradeSide);
            orderData.put("orderType", orderType);
            if ("limit".equals(orderType) && price != null) {
                orderData.put("price", String.valueOf(price));
            }
            
            // log.info("[주문 요청] {}", gson.toJson(orderData));

            JsonObject response = post("/api/v2/mix/order/place-order", orderData);
            
            if (response != null && "00000".equals(response.get("code").getAsString())) {
                // log.info("[주문 성공] {}", response);
                return parseOrderResult(response, quantity, price);
            } else {
                String errorCode = (response != null && response.has("code")) ? response.get("code").getAsString() : "N/A";
                String errorMessage = (response != null && response.has("msg")) ? response.get("msg").getAsString() : "N/A";
                log.error("[주문 실패] 코드: {}, 메시지: {}, 응답: {}", errorCode, errorMessage, response);
                return null;
            }
        } catch (Exception e) { 
            log.error("선물 주문 실행 중 예외 발생", e); 
            return null; 
        }
    }

    private String sign(String message) throws Exception {
        Mac sha256 = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKeySpec = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        sha256.init(secretKeySpec);
        byte[] hash = sha256.doFinal(message.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(hash);
    }

    private List<Candle> parseCandles(String json) {
        List<Candle> candles = new ArrayList<>();
        try {
            JsonObject response = gson.fromJson(json, JsonObject.class);
            if (!"00000".equals(response.get("code").getAsString())) return candles;
            for (var item : response.getAsJsonArray("data")) {
                JsonArray candleData = item.getAsJsonArray();
                Candle candle = new Candle();
                candle.setTimestamp(candleData.get(0).getAsLong());
                candle.setOpen(candleData.get(1).getAsDouble());
                candle.setHigh(candleData.get(2).getAsDouble());
                candle.setLow(candleData.get(3).getAsDouble());
                candle.setClose(candleData.get(4).getAsDouble());
                candle.setVolume(candleData.get(5).getAsDouble());
                candles.add(candle);
            }
        } catch (Exception e) { log.error("캔들 데이터 파싱 실패", e); }
        Collections.reverse(candles);
        return candles;
    }

    private Position parsePosition(JsonObject posData) {
        if (posData != null && posData.has("total") && !posData.get("total").isJsonNull() && posData.get("total").getAsDouble() > 0 &&
            posData.has("openPriceAvg") && !posData.get("openPriceAvg").isJsonNull() &&
            posData.has("holdSide") && !posData.get("holdSide").isJsonNull()) {
            
            Position position = new Position();
            position.setSymbol(posData.get("symbol").getAsString());
            position.setQuantity(posData.get("total").getAsDouble());
            position.setEntryPrice(posData.get("openPriceAvg").getAsDouble());
            position.setSide("long".equals(posData.get("holdSide").getAsString()) ? "BUY" : "SHORT");
            return position;
        }
        return null;
    }

    private OrderResult parseOrderResult(JsonObject response, double requestedQuantity, Double requestedPrice) {
        try {
            if (response != null && "00000".equals(response.get("code").getAsString())) {
                OrderResult result = new OrderResult();
                JsonObject data = response.getAsJsonObject("data");
                result.setOrderId(data.get("orderId").getAsString());

                if (data.has("fillPrice") && !data.get("fillPrice").isJsonNull()) {
                    result.setStatus("filled");
                    result.setAveragePrice(data.get("fillPrice").getAsDouble());
                    result.setFilledQuantity(data.get("fillSize").getAsDouble());
                } else {
                    result.setStatus("new");
                    result.setFilledQuantity(requestedQuantity);
                    result.setAveragePrice(requestedPrice != null ? requestedPrice : 0);
                }
                return result;
            }
        } catch (Exception e) { log.error("주문 결과 파싱 실패", e); }
        return null;
    }

    private String convertTimeframeToGranularity(String timeframe) {
        Map<String, String> mapping = new HashMap<>();
        mapping.put("1m", "1m");
        mapping.put("5m", "5m");
        mapping.put("15m", "15m");
        mapping.put("30m", "30m");
        mapping.put("1h", "1H");
        mapping.put("4h", "4H");
        return mapping.getOrDefault(timeframe, "1H");
    }
}
