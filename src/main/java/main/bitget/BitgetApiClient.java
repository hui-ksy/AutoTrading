package main.bitget;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import main.model.Candle;
import main.model.OrderResult;
import okhttp3.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Slf4j
public class BitgetApiClient implements MarketDataClient, TradeClient {
    private static final String BASE_URL = "https://api.bitget.com";
    private final OkHttpClient httpClient;
    private final Gson gson;

    private final String apiKey;
    private final String secretKey;
    private final String passphrase;

    public BitgetApiClient(String apiKey, String secretKey, String passphrase) {
        this.apiKey = apiKey;
        this.secretKey = secretKey;
        this.passphrase = passphrase;
        this.httpClient = new OkHttpClient();
        this.gson = new Gson();
    }

    // HMAC SHA256 서명 생성
    private String sign(String message) throws Exception {
        Mac sha256 = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKeySpec = new SecretKeySpec(
                secretKey.getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"
        );
        sha256.init(secretKeySpec);
        byte[] hash = sha256.doFinal(message.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(hash);
    }

    // 캔들 데이터 조회 (현물) - 타임스탬프 기반 페이징 병합
    public List<Candle> getCandles(String symbol, String timeframe, int limit) {
        try {
            String granularity = convertTimeframeToGranularity(timeframe);

            final String endpoint = "/api/v2/spot/market/candles";
            final int PAGE_CAP = Math.max(1, Math.min(500, limit)); // 요청당 최대치 보수적으로 제한

            List<Candle> all = new ArrayList<>();
            Set<Long> seen = new HashSet<>();
            int remaining = Math.max(1, limit);
            Long endTime = null; // 이전 페이지의 가장 오래된 시각 - 1ms

            while (remaining > 0) {
                int pageLimit = Math.min(PAGE_CAP, remaining);

                StringBuilder url = new StringBuilder(BASE_URL)
                        .append(endpoint)
                        .append("?symbol=").append(symbol)
                        .append("&granularity=").append(granularity)
                        .append("&limit=").append(pageLimit);

                if (endTime != null) {
                    url.append("&endTime=").append(endTime);
                }

                Request request = new Request.Builder()
                        .url(url.toString())
                        .get()
                        .build();

                Response response = httpClient.newCall(request).execute();
                if (!response.isSuccessful()) {
                    log.error("캔들 데이터 조회 실패: HTTP {}", response.code());
                    break;
                }

                String body = response.body().string();
                List<Candle> page = parseCandles(body);
                if (page == null || page.isEmpty()) {
                    break;
                }

                long oldest = Long.MAX_VALUE;
                for (Candle c : page) {
                    long ts = c.getTimestamp();
                    if (ts < oldest) oldest = ts;
                }
                endTime = oldest - 1; // 다음 페이지 경계

                for (Candle c : page) {
                    long ts = c.getTimestamp();
                    if (!seen.contains(ts)) {
                        all.add(c);
                        seen.add(ts);
                        remaining--;
                        if (remaining == 0) break;
                    }
                }

                if (page.size() < pageLimit) {
                    // 더 이상 페이지가 없을 가능성
                    break;
                }
            }

            if (all.isEmpty()) return all;

            // 시간 오름차순 정렬 후 요청 개수만 유지
            all.sort(Comparator.comparingLong(Candle::getTimestamp));
            if (all.size() > limit) {
                all = all.subList(all.size() - limit, all.size());
            }

            return new ArrayList<>(all);

        } catch (Exception e) {
            log.error("캔들 데이터 조회 실패", e);
            return new ArrayList<>();
        }
    }

    // ===== TradeClient 구현 (Spot 전용) =====
    // marketBuy/marketSell은 기존 동일 시그니처 공개 메소드로 충족됨
    @Override
    public OrderResult marketLong(String symbol, double quantity) {
        throw new UnsupportedOperationException("Futures-only operation");
    }

    @Override
    public OrderResult marketShort(String symbol, double quantity) {
        throw new UnsupportedOperationException("Futures-only operation");
    }

    @Override
    public OrderResult closeLong(String symbol, double quantity) {
        throw new UnsupportedOperationException("Futures-only operation");
    }

    @Override
    public OrderResult closeShort(String symbol, double quantity) {
        throw new UnsupportedOperationException("Futures-only operation");
    }

    @Override
    public boolean hasPosition(String symbol) {
        // Spot 실거래 보유잔고 조회는 현재 미구현. 호출측에서 별도 관리.
        return false;
    }

    // Timeframe을 Bitget granularity로 변환
    private String convertTimeframeToGranularity(String timeframe) {
        // Bitget API granularity: 1min, 5min, 15min, 30min, 1h, 4h, 12h, 1day, 1week
        Map<String, String> mapping = new HashMap<>();
        mapping.put("1m", "1min");
        mapping.put("5m", "5min");
        mapping.put("15m", "15min");
        mapping.put("30m", "30min");
        mapping.put("1h", "1h");
        mapping.put("4h", "4h");
        mapping.put("12h", "12h");
        mapping.put("1d", "1day");
        mapping.put("1w", "1week");

        return mapping.getOrDefault(timeframe, "1h");
    }

    // JSON 응답을 Candle 리스트로 파싱
    private List<Candle> parseCandles(String json) {
        List<Candle> candles = new ArrayList<>();

        try {
            JsonObject response = gson.fromJson(json, JsonObject.class);

            // Bitget API 응답 형식: {"code":"00000","msg":"success","data":[...]}
            String code = response.get("code").getAsString();

            if (!"00000".equals(code)) {
                log.error("API 에러 코드: {}, 메시지: {}", code, response.get("msg").getAsString());
                return candles;
            }

            JsonArray data = response.getAsJsonArray("data");

            // 각 캔들 데이터 파싱
            // 형식: [timestamp, open, high, low, close, volume, ...]
            for (int i = 0; i < data.size(); i++) {
                JsonArray candleData = data.get(i).getAsJsonArray();

                Candle candle = new Candle();
                candle.setTimestamp(candleData.get(0).getAsLong());
                candle.setOpen(candleData.get(1).getAsDouble());
                candle.setHigh(candleData.get(2).getAsDouble());
                candle.setLow(candleData.get(3).getAsDouble());
                candle.setClose(candleData.get(4).getAsDouble());
                candle.setVolume(candleData.get(5).getAsDouble());

                candles.add(candle);
            }

            log.debug("파싱된 캔들 개수: {}", candles.size());

        } catch (Exception e) {
            log.error("캔들 데이터 파싱 실패", e);
        }

        return candles;
    }

    // 시장가 매수 주문
    public OrderResult marketBuy(String symbol, double quantity) {
        return placeOrder(symbol, "buy", "market", quantity, null);
    }

    // 시장가 매도 주문
    public OrderResult marketSell(String symbol, double quantity) {
        return placeOrder(symbol, "sell", "market", quantity, null);
    }

    // 주문 실행
    private OrderResult placeOrder(String symbol, String side, String orderType,
                                   double quantity, Double price) {
        try {
            String timestamp = String.valueOf(System.currentTimeMillis());
            String endpoint = "/api/v2/spot/trade/place-order";

            // 주문 요청 body 생성
            Map<String, Object> orderData = new HashMap<>();
            orderData.put("symbol", symbol);
            orderData.put("side", side);
            orderData.put("orderType", orderType);
            orderData.put("size", String.valueOf(quantity));

            if (price != null) {
                orderData.put("price", String.valueOf(price));
            }

            String body = gson.toJson(orderData);

            // 서명 생성
            String signMessage = timestamp + "POST" + endpoint + body;
            String signature = sign(signMessage);

            // Passphrase도 서명 필요
            String passphraseSign = sign(passphrase);

            Request request = new Request.Builder()
                    .url(BASE_URL + endpoint)
                    .post(RequestBody.create(body, MediaType.parse("application/json")))
                    .addHeader("ACCESS-KEY", apiKey)
                    .addHeader("ACCESS-SIGN", signature)
                    .addHeader("ACCESS-TIMESTAMP", timestamp)
                    .addHeader("ACCESS-PASSPHRASE", passphraseSign)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("locale", "en-US")
                    .build();

            Response response = httpClient.newCall(request).execute();
            String responseBody = response.body().string();

            log.info("주문 실행: {} {} {} - 응답: {}", side, quantity, symbol, responseBody);

            return parseOrderResult(responseBody);

        } catch (Exception e) {
            log.error("주문 실행 실패", e);
            return null;
        }
    }

    // 주문 결과 파싱
    private OrderResult parseOrderResult(String json) {
        try {
            JsonObject response = gson.fromJson(json, JsonObject.class);

            String code = response.get("code").getAsString();

            if (!"00000".equals(code)) {
                log.error("주문 실패 - 코드: {}, 메시지: {}", code, response.get("msg").getAsString());
                return null;
            }

            JsonObject data = response.getAsJsonObject("data");

            OrderResult result = new OrderResult();
            result.setOrderId(data.get("orderId").getAsString());
            result.setStatus("filled"); // 시장가 주문은 즉시 체결로 가정

            // 체결 정보는 별도 API로 조회 필요
            // 여기서는 간단하게 처리
            result.setFilledQuantity(0); // TODO: 실제 체결량 조회
            result.setAveragePrice(0);    // TODO: 실제 체결가 조회

            return result;

        } catch (Exception e) {
            log.error("주문 결과 파싱 실패", e);
            return null;
        }
    }

    // 잔고 조회 (추가)
    public double getBalance(String currency) {
        try {
            String timestamp = String.valueOf(System.currentTimeMillis());
            String endpoint = "/api/v2/spot/account/assets";
            String queryString = "?coin=" + currency;

            String signMessage = timestamp + "GET" + endpoint + queryString;
            String signature = sign(signMessage);
            String passphraseSign = sign(passphrase);

            Request request = new Request.Builder()
                    .url(BASE_URL + endpoint + queryString)
                    .get()
                    .addHeader("ACCESS-KEY", apiKey)
                    .addHeader("ACCESS-SIGN", signature)
                    .addHeader("ACCESS-TIMESTAMP", timestamp)
                    .addHeader("ACCESS-PASSPHRASE", passphraseSign)
                    .addHeader("Content-Type", "application/json")
                    .build();

            Response response = httpClient.newCall(request).execute();
            String body = response.body().string();

            JsonObject jsonResponse = gson.fromJson(body, JsonObject.class);

            if ("00000".equals(jsonResponse.get("code").getAsString())) {
                JsonArray data = jsonResponse.getAsJsonArray("data");
                if (data.size() > 0) {
                    JsonObject asset = data.get(0).getAsJsonObject();
                    return asset.get("available").getAsDouble();
                }
            }

            return 0.0;

        } catch (Exception e) {
            log.error("잔고 조회 실패", e);
            return 0.0;
        }
    }
}
