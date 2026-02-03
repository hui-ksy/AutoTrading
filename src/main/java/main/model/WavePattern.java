package main.model;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
public class WavePattern {

    public enum Type {
        IMPULSE,    // 충격파 (1-2-3-4-5)
        CORRECTION  // 조정파 (A-B-C)
    }

    public enum Direction {
        UP, DOWN
    }

    private Type type;
    private Direction direction;
    private int currentWave;
    private Map<Integer, Double> wavePoints = new HashMap<>();
    private double confidence;

    public void addWavePoint(int wave, double price) {
        wavePoints.put(wave, price);
    }

    public Double getWavePrice(int wave) {
        return wavePoints.get(wave);
    }

    public double getCurrentPrice() {
        // 가장 최근 파동의 가격
        return wavePoints.values().stream()
                .max(Double::compare)
                .orElse(0.0);
    }

    // 3파동 유효성 검증
    public boolean isValidWave3() {
        return currentWave == 3 && confidence > 0.7;
    }

    // 5파동 완성 여부
    public boolean isWave5Completing() {
        return currentWave == 5 && confidence > 0.6;
    }

    // B파동 여부
    public boolean isCorrectionBWave() {
        return type == Type.CORRECTION && currentWave == -2;
    }

    // 조정 완료 여부
    public boolean isCorrectionComplete() {
        return type == Type.CORRECTION && currentWave == -3 && confidence > 0.65;
    }
}
