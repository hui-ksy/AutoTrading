package main.strategy;

import lombok.extern.slf4j.Slf4j;
import main.model.Candle;
import main.model.TradingConfig;
import main.model.WavePattern;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public class WaveAnalyzer {

    private final TradingConfig config;

    public WaveAnalyzer(TradingConfig config) {
        this.config = config;
    }

    // 파동 패턴 감지
    public WavePattern detectPattern(List<Candle> candles) {

        // 1. 피봇 포인트 찾기 (고점, 저점)
        List<PivotPoint> pivots = findPivotPoints(candles);

        if (pivots.size() < 5) {
            return null; // 최소 5개의 피봇이 필요
        }

        // 2. 충격파 패턴 감지 (1-2-3-4-5)
        WavePattern impulsePattern = detectImpulseWave(pivots, candles);
        if (impulsePattern != null) {
            return impulsePattern;
        }

        // 3. 조정파 패턴 감지 (A-B-C)
        WavePattern correctionPattern = detectCorrectionWave(pivots, candles);
        if (correctionPattern != null) {
            return correctionPattern;
        }

        return null;
    }

    // 피봇 포인트 찾기
    private List<PivotPoint> findPivotPoints(List<Candle> candles) {
        List<PivotPoint> pivots = new ArrayList<>();
        int lookback = 5; // 좌우 5개 캔들 확인

        for (int i = lookback; i < candles.size() - lookback; i++) {
            Candle current = candles.get(i);

            // 고점 체크
            boolean isHigh = true;
            for (int j = i - lookback; j <= i + lookback; j++) {
                if (j != i && candles.get(j).getHigh() > current.getHigh()) {
                    isHigh = false;
                    break;
                }
            }

            if (isHigh) {
                pivots.add(new PivotPoint(i, current.getHigh(), PivotPoint.Type.HIGH));
            }

            // 저점 체크
            boolean isLow = true;
            for (int j = i - lookback; j <= i + lookback; j++) {
                if (j != i && candles.get(j).getLow() < current.getLow()) {
                    isLow = false;
                    break;
                }
            }

            if (isLow) {
                pivots.add(new PivotPoint(i, current.getLow(), PivotPoint.Type.LOW));
            }
        }

        return pivots;
    }

    // 충격파 감지
    private WavePattern detectImpulseWave(List<PivotPoint> pivots, List<Candle> candles) {

        // 엘리엇 파동 규칙:
        // - 파동 2는 파동 1의 시작점을 넘지 않음
        // - 파동 3은 가장 짧은 파동이 아님
        // - 파동 4는 파동 1의 영역과 겹치지 않음

        for (int i = 0; i < pivots.size() - 8; i++) {

            // 5개의 파동 후보 찾기
            PivotPoint wave0 = pivots.get(i);
            PivotPoint wave1 = pivots.get(i + 1);
            PivotPoint wave2 = pivots.get(i + 2);
            PivotPoint wave3 = pivots.get(i + 3);
            PivotPoint wave4 = pivots.get(i + 4);
            PivotPoint wave5 = pivots.get(i + 5);

            // 상승 충격파 검증
            if (validateImpulseWave(wave0, wave1, wave2, wave3, wave4, wave5)) {

                WavePattern pattern = new WavePattern();
                pattern.setType(WavePattern.Type.IMPULSE);
                pattern.setDirection(WavePattern.Direction.UP);
                pattern.addWavePoint(0, wave0.getPrice());
                pattern.addWavePoint(1, wave1.getPrice());
                pattern.addWavePoint(2, wave2.getPrice());
                pattern.addWavePoint(3, wave3.getPrice());
                pattern.addWavePoint(4, wave4.getPrice());
                pattern.addWavePoint(5, wave5.getPrice());

                // 현재 파동 단계 판단
                int currentWave = determineCurrentWave(pattern, candles);
                pattern.setCurrentWave(currentWave);

                return pattern;
            }
        }

        return null;
    }

    // 조정파 감지
    private WavePattern detectCorrectionWave(List<PivotPoint> pivots, List<Candle> candles) {

        // A-B-C 조정 패턴 (지그재그)
        for (int i = 0; i < pivots.size() - 3; i++) {
            PivotPoint waveA_start = pivots.get(i);
            PivotPoint waveA_end = pivots.get(i + 1);
            PivotPoint waveB_end = pivots.get(i + 2);
            PivotPoint waveC_end = pivots.get(i + 3);

            if (validateCorrectionWave(waveA_start, waveA_end, waveB_end, waveC_end)) {

                WavePattern pattern = new WavePattern();
                pattern.setType(WavePattern.Type.CORRECTION);
                pattern.addWavePoint(-1, waveA_start.getPrice());
                pattern.addWavePoint(-2, waveA_end.getPrice());
                pattern.addWavePoint(-3, waveB_end.getPrice());
                pattern.addWavePoint(-4, waveC_end.getPrice());

                int currentWave = determineCurrentWave(pattern, candles);
                pattern.setCurrentWave(currentWave);

                return pattern;
            }
        }

        return null;
    }

    // 충격파 검증
    private boolean validateImpulseWave(PivotPoint w0, PivotPoint w1, PivotPoint w2,
                                        PivotPoint w3, PivotPoint w4, PivotPoint w5) {

        double wave1 = Math.abs(w1.getPrice() - w0.getPrice());
        double wave2 = Math.abs(w2.getPrice() - w1.getPrice());
        double wave3 = Math.abs(w3.getPrice() - w2.getPrice());
        double wave4 = Math.abs(w4.getPrice() - w3.getPrice());
        double wave5 = Math.abs(w5.getPrice() - w4.getPrice());

        // 규칙 1: 파동 2가 파동 0 시작점을 넘지 않음
        if (w2.getPrice() < w0.getPrice()) {
            return false;
        }

        // 규칙 2: 파동 3이 가장 짧은 파동이 아님
        if (wave3 < wave1 && wave3 < wave5) {
            return false;
        }

        // 규칙 3: 파동 4가 파동 1 영역과 겹치지 않음
        if (w4.getPrice() < w1.getPrice()) {
            return false;
        }

        return true;
    }

    // 조정파 검증
    private boolean validateCorrectionWave(PivotPoint start, PivotPoint a,
                                           PivotPoint b, PivotPoint c) {
        // 간단한 A-B-C 패턴 검증
        // C파동이 A파동 시작점 근처에서 끝나야 함
        double tolerance = config.getFibonacciTolerance();
        double cRetracement = Math.abs(c.getPrice() - start.getPrice()) /
                Math.abs(a.getPrice() - start.getPrice());

        return cRetracement >= (1 - tolerance) && cRetracement <= (1 + tolerance);
    }

    // 현재 파동 단계 판단
    private int determineCurrentWave(WavePattern pattern, List<Candle> candles) {
        // 최신 캔들과 파동 포인트 비교하여 현재 위치 판단
        // TODO: 구현 필요
        return 3; // 임시로 3파동 반환
    }

    // 피봇 포인트 내부 클래스
    @lombok.Data
    private static class PivotPoint {
        private final int index;
        private final double price;
        private final Type type;

        enum Type {
            HIGH, LOW
        }
    }
}
