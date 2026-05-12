package main.backtest;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import main.model.Candle;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Bitget 캔들 API 동작 확인용 간단한 디버그 테스트.
 * 인증 불필요 (공개 엔드포인트).
 *
 * 실행: ./gradlew candleTest
 *
 * 주요 발견:
 *   - Bitget API는 캔들을 오름차순(oldest first)으로 반환
 *   - 페이지네이션: endTime = oldest_candle_ts - 1ms 로 이전 구간 조회
 */
public class CandleDebugTest {

    private static final String BASE_URL     = "https://api.bitget.com";
    private static final String SYMBOL       = "BTCUSDT";
    private static final String PRODUCT_TYPE = "USDT-FUTURES";
    private static final String GRANULARITY  = "4H";   // 4시간봉
    private static final int    BATCH_LIMIT  = 200;    // 한 번에 최대 요청 수
    private static final int    TARGET       = 600;    // 목표 캔들 수

    private static final DateTimeFormatter FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    public static void main(String[] args) throws Exception {
        OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();
        Gson gson = new Gson();

        // ── 1차: endTime 없이 최신 데이터 조회 ─────────────────────────
        System.out.println("=== Batch 1: endTime 없음 ===");
        List<Candle> batch1 = fetchBatch(http, gson, null);
        printBatchSummary(batch1, 1);

        if (batch1.isEmpty()) {
            System.out.println("데이터 없음 — 종료");
            return;
        }

        // ── 2차: endTime = batch1 의 가장 오래된 캔들 - 1ms ───────────
        long endTime2 = batch1.get(0).getTimestamp() - 1;  // batch1[0] = oldest
        System.out.println("\n=== Batch 2: endTime=" + FMT.format(Instant.ofEpochMilli(endTime2)) + " ===");
        List<Candle> batch2 = fetchBatch(http, gson, endTime2);
        printBatchSummary(batch2, 2);

        // ── 3차 ─────────────────────────────────────────────────────────
        if (!batch2.isEmpty()) {
            long endTime3 = batch2.get(0).getTimestamp() - 1;
            System.out.println("\n=== Batch 3: endTime=" + FMT.format(Instant.ofEpochMilli(endTime3)) + " ===");
            List<Candle> batch3 = fetchBatch(http, gson, endTime3);
            printBatchSummary(batch3, 3);
        }

        // ── 전체 누적 테스트 ─────────────────────────────────────────────
        System.out.println("\n=== 누적 수집 테스트 (목표 " + TARGET + "개) ===");
        List<Candle> all = collectCandles(http, gson, TARGET);
        System.out.printf("수집 완료: %d개%n", all.size());
        if (!all.isEmpty()) {
            System.out.printf("기간: %s ~ %s%n",
                FMT.format(Instant.ofEpochMilli(all.get(0).getTimestamp())),
                FMT.format(Instant.ofEpochMilli(all.get(all.size() - 1).getTimestamp())));
            // 중복 캔들 확인
            long distinct = all.stream().map(Candle::getTimestamp).distinct().count();
            System.out.printf("유니크 타임스탬프: %d개 (중복: %d개)%n", distinct, all.size() - distinct);
        }
    }

    private static List<Candle> fetchBatch(OkHttpClient http, Gson gson, Long endTime) throws Exception {
        StringBuilder url = new StringBuilder(BASE_URL)
            .append("/api/v2/mix/market/candles")
            .append("?symbol=").append(SYMBOL)
            .append("&productType=").append(PRODUCT_TYPE)
            .append("&granularity=").append(GRANULARITY)
            .append("&limit=").append(BATCH_LIMIT);

        if (endTime != null) {
            url.append("&endTime=").append(endTime);
        }

        System.out.println("URL: " + url);
        Request req = new Request.Builder().url(url.toString()).get().build();
        try (Response resp = http.newCall(req).execute()) {
            String body = resp.body().string();
            System.out.println("HTTP " + resp.code() + "  body길이=" + body.length());
            return parseCandles(gson, body);
        }
    }

    /**
     * Bitget API는 캔들을 오름차순(oldest first)으로 반환.
     * Collections.reverse() 하지 않음 — 그대로 oldest-first 유지.
     */
    private static List<Candle> parseCandles(Gson gson, String json) {
        List<Candle> result = new ArrayList<>();
        try {
            JsonObject root = gson.fromJson(json, JsonObject.class);
            String code = root.get("code").getAsString();
            System.out.println("API code=" + code);
            if (!"00000".equals(code)) {
                System.out.println("API 오류: " + root);
                return result;
            }
            JsonArray data = root.getAsJsonArray("data");
            System.out.println("data 배열 크기: " + data.size());
            for (var item : data) {
                JsonArray row = item.getAsJsonArray();
                Candle c = new Candle();
                c.setTimestamp(row.get(0).getAsLong());
                c.setOpen(row.get(1).getAsDouble());
                c.setHigh(row.get(2).getAsDouble());
                c.setLow(row.get(3).getAsDouble());
                c.setClose(row.get(4).getAsDouble());
                c.setVolume(row.get(5).getAsDouble());
                result.add(c);
            }
            // API는 이미 오름차순(oldest first) → reverse 불필요
            System.out.printf("첫 캔들(oldest): %s  마지막(newest): %s%n",
                result.isEmpty() ? "N/A" : FMT.format(Instant.ofEpochMilli(result.get(0).getTimestamp())),
                result.isEmpty() ? "N/A" : FMT.format(Instant.ofEpochMilli(result.get(result.size()-1).getTimestamp())));
        } catch (Exception e) {
            System.out.println("파싱 실패: " + e.getMessage());
        }
        return result;
    }

    private static void printBatchSummary(List<Candle> batch, int no) {
        if (batch.isEmpty()) {
            System.out.println("Batch " + no + ": 빈 결과");
            return;
        }
        long oldest = batch.get(0).getTimestamp();
        long newest = batch.get(batch.size() - 1).getTimestamp();
        long intervalMs = batch.size() > 1 ? batch.get(1).getTimestamp() - batch.get(0).getTimestamp() : 0;
        System.out.printf("Batch %d: %d개  oldest=%s  newest=%s  interval=%dh%n",
            no, batch.size(),
            FMT.format(Instant.ofEpochMilli(oldest)),
            FMT.format(Instant.ofEpochMilli(newest)),
            intervalMs / 3_600_000);
    }

    private static List<Candle> collectCandles(OkHttpClient http, Gson gson, int target) throws Exception {
        List<Candle> all = new ArrayList<>();
        Long endTime = null;
        int batchNo = 0;

        while (all.size() < target) {
            batchNo++;
            List<Candle> batch = fetchBatch(http, gson, endTime);
            if (batch.isEmpty()) {
                System.out.println("빈 배치 → 종료 (batchNo=" + batchNo + ")");
                break;
            }

            long batchOldest = batch.get(0).getTimestamp();

            // 중복/무한루프 방지: 새 배치의 oldest가 이전 endTime보다 최신이면 중단
            if (endTime != null && batchOldest >= endTime) {
                System.out.println("페이지네이션 멈춤 — API가 더 오래된 데이터를 주지 않음 (batchNo=" + batchNo + ")");
                break;
            }

            all.addAll(0, batch);  // prepend (오래된 데이터를 앞에)
            endTime = batchOldest - 1;
            System.out.printf("  → 누적: %d개  새 endTime: %s%n",
                all.size(), FMT.format(Instant.ofEpochMilli(endTime)));

            Thread.sleep(300);
        }
        return all;
    }
}