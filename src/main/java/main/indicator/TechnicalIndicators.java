package main.indicator;

import lombok.Builder;
import lombok.Value;
import main.model.Candle;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class TechnicalIndicators {

    @Value
    @Builder
    public static class MacdResult {
        List<Double> macdLine;
        List<Double> signalLine;
        List<Double> histogram;
    }

    @Value
    @Builder
    public static class BollingerBandsResult {
        double upperBand;
        double middleBand;
        double lowerBand;
    }

    @Value
    @Builder
    public static class StochasticResult {
        List<Double> k;
        List<Double> d;
    }

    @Value
    @Builder
    public static class OrderBlock {
        Candle candle;
        String type; // "bullish" or "bearish"
        boolean hasImbalance;
    }
    
    @Value
    @Builder
    public static class IchimokuResult {
        List<Double> conversionLine; // 전환선 (Tenkan-sen)
        List<Double> baseLine;       // 기준선 (Kijun-sen)
        List<Double> leadingSpanA;   // 선행스팬1 (Senkou Span A)
        List<Double> leadingSpanB;   // 선행스팬2 (Senkou Span B)
    }
    
    @Value
    @Builder
    public static class SuperTrendResult {
        List<Double> superTrend; // SuperTrend 라인 값
        List<Boolean> trend;     // true: 상승(Long), false: 하락(Short)
    }

    public static MacdResult calculateMACD(List<Candle> candles, int fastPeriod, int slowPeriod, int signalPeriod) {
        List<Double> closePrices = candles.stream().map(Candle::getClose).collect(Collectors.toList());
        
        List<Double> fastEma = calculateEmaForDoubles(closePrices, fastPeriod);
        List<Double> slowEma = calculateEmaForDoubles(closePrices, slowPeriod);

        int offset = fastEma.size() - slowEma.size();

        List<Double> macdLine = new ArrayList<>();
        for (int i = 0; i < slowEma.size(); i++) {
            macdLine.add(fastEma.get(i + offset) - slowEma.get(i));
        }

        List<Double> signalLine = calculateEmaForDoubles(macdLine, signalPeriod);
        
        offset = macdLine.size() - signalLine.size();
        List<Double> histogram = new ArrayList<>();
        for (int i = 0; i < signalLine.size(); i++) {
            histogram.add(macdLine.get(i + offset) - signalLine.get(i));
        }

        return MacdResult.builder()
                .macdLine(macdLine)
                .signalLine(signalLine)
                .histogram(histogram)
                .build();
    }
    
    private static List<Double> calculateEmaForDoubles(List<Double> prices, int period) {
        List<Double> emaValues = new ArrayList<>();
        if (prices.size() < period) return emaValues;

        double multiplier = 2.0 / (period + 1);
        double initialSma = prices.subList(0, period).stream().mapToDouble(d -> d).average().orElse(0);
        emaValues.add(initialSma);

        for (int i = period; i < prices.size(); i++) {
            double price = prices.get(i);
            double prevEma = emaValues.get(emaValues.size() - 1);
            double ema = (price - prevEma) * multiplier + prevEma;
            emaValues.add(ema);
        }
        return emaValues;
    }

    public static List<OrderBlock> findOrderBlocks(List<Candle> candles) {
        List<OrderBlock> orderBlocks = new ArrayList<>();
        if (candles.size() < 3) return orderBlocks;

        for (int i = 0; i < candles.size() - 2; i++) {
            Candle candle1 = candles.get(i);
            Candle candle3 = candles.get(i + 2);

            boolean isBullishFvg = candle1.getHigh() < candle3.getLow();
            if (isBullishFvg && candle1.getClose() < candle1.getOpen()) {
                orderBlocks.add(OrderBlock.builder()
                        .candle(candle1)
                        .type("bullish")
                        .hasImbalance(true)
                        .build());
            }

            boolean isBearishFvg = candle1.getLow() > candle3.getHigh();
            if (isBearishFvg && candle1.getClose() > candle1.getOpen()) {
                orderBlocks.add(OrderBlock.builder()
                        .candle(candle1)
                        .type("bearish")
                        .hasImbalance(true)
                        .build());
            }
        }
        return orderBlocks;
    }

    public static List<Double> calculateEMA(List<Candle> candles, int period) {
        List<Double> closePrices = candles.stream().map(Candle::getClose).collect(Collectors.toList());
        return calculateEmaForDoubles(closePrices, period);
    }

    public static BollingerBandsResult calculateBollingerBands(List<Candle> candles, int period, double stdDev) {
        if (candles.size() < period) return null;

        List<Candle> relevantCandles = candles.subList(candles.size() - period, candles.size());
        double sma = relevantCandles.stream().mapToDouble(Candle::getClose).average().orElse(0);
        double standardDeviation = Math.sqrt(relevantCandles.stream().mapToDouble(c -> Math.pow(c.getClose() - sma, 2)).average().orElse(0));

        return BollingerBandsResult.builder()
                .middleBand(sma)
                .upperBand(sma + stdDev * standardDeviation)
                .lowerBand(sma - stdDev * standardDeviation)
                .build();
    }

    public static List<Double> calculateRSI(List<Candle> candles, int period) {
        List<Double> rsiValues = new ArrayList<>();
        if (candles.size() <= period) return rsiValues;

        double gain = 0, loss = 0;
        for (int i = 1; i <= period; i++) {
            double change = candles.get(i).getClose() - candles.get(i - 1).getClose();
            if (change > 0) gain += change;
            else loss -= change;
        }
        double avgGain = gain / period;
        double avgLoss = loss / period;

        double rs = (avgLoss == 0) ? Double.POSITIVE_INFINITY : avgGain / avgLoss;
        rsiValues.add(100 - (100 / (1 + rs)));

        for (int i = period + 1; i < candles.size(); i++) {
            double change = candles.get(i).getClose() - candles.get(i - 1).getClose();
            gain = (change > 0) ? change : 0;
            loss = (change < 0) ? -change : 0;

            avgGain = (avgGain * (period - 1) + gain) / period;
            avgLoss = (avgLoss * (period - 1) + loss) / period;

            rs = (avgLoss == 0) ? Double.POSITIVE_INFINITY : avgGain / avgLoss;
            rsiValues.add(100 - (100 / (1 + rs)));
        }
        return rsiValues;
    }

    public static StochasticResult calculateStochastic(List<Candle> candles, int kPeriod, int dPeriod, int slowing) {
        List<Double> kValues = new ArrayList<>();
        List<Double> dValues = new ArrayList<>();
        if (candles.size() < kPeriod) return StochasticResult.builder().k(kValues).d(dValues).build();

        for (int i = kPeriod - 1; i < candles.size(); i++) {
            List<Candle> sublist = candles.subList(i - kPeriod + 1, i + 1);
            double highestHigh = sublist.stream().mapToDouble(Candle::getHigh).max().orElse(0);
            double lowestLow = sublist.stream().mapToDouble(Candle::getLow).min().orElse(0);
            double currentClose = sublist.get(sublist.size() - 1).getClose();
            
            double percentK = 100 * ((currentClose - lowestLow) / (highestHigh - lowestLow));
            kValues.add(percentK);
        }

        List<Double> slowedK = new ArrayList<>();
        if (slowing > 1) {
            for (int i = slowing - 1; i < kValues.size(); i++) {
                double sum = 0;
                for (int j = 0; j < slowing; j++) {
                    sum += kValues.get(i - j);
                }
                slowedK.add(sum / slowing);
            }
        } else {
            slowedK = kValues;
        }

        if (slowedK.size() >= dPeriod) {
            for (int i = dPeriod - 1; i < slowedK.size(); i++) {
                double sum = 0;
                for (int j = 0; j < dPeriod; j++) {
                    sum += slowedK.get(i - j);
                }
                dValues.add(sum / dPeriod);
            }
        }

        return StochasticResult.builder().k(slowedK).d(dValues).build();
    }

    public static double calculateATR(List<Candle> candles, int period) {
        if (candles.size() < period + 1) return 0;

        List<Double> trValues = new ArrayList<>();
        for (int i = 1; i < candles.size(); i++) {
            double high = candles.get(i).getHigh();
            double low = candles.get(i).getLow();
            double prevClose = candles.get(i - 1).getClose();
            
            double tr = Math.max(high - low, Math.max(Math.abs(high - prevClose), Math.abs(low - prevClose)));
            trValues.add(tr);
        }

        if (trValues.size() < period) return 0;

        double sum = 0;
        for (int i = 0; i < period; i++) {
            sum += trValues.get(i);
        }
        double atr = sum / period;

        for (int i = period; i < trValues.size(); i++) {
            atr = (atr * (period - 1) + trValues.get(i)) / period;
        }

        return atr;
    }
    
    // [추가] ATR 리스트 반환 (SuperTrend 계산용)
    public static List<Double> calculateATRList(List<Candle> candles, int period) {
        List<Double> atrList = new ArrayList<>();
        if (candles.size() < period + 1) return atrList;

        List<Double> trValues = new ArrayList<>();
        for (int i = 1; i < candles.size(); i++) {
            double high = candles.get(i).getHigh();
            double low = candles.get(i).getLow();
            double prevClose = candles.get(i - 1).getClose();
            
            double tr = Math.max(high - low, Math.max(Math.abs(high - prevClose), Math.abs(low - prevClose)));
            trValues.add(tr);
        }

        if (trValues.size() < period) return atrList;

        double sum = 0;
        for (int i = 0; i < period; i++) {
            sum += trValues.get(i);
        }
        double atr = sum / period;
        atrList.add(atr);

        for (int i = period; i < trValues.size(); i++) {
            atr = (atr * (period - 1) + trValues.get(i)) / period;
            atrList.add(atr);
        }

        return atrList;
    }

    public static double calculateAverageVolume(List<Candle> candles, int period) {
        if (candles.size() < period) {
            return 0;
        }
        List<Candle> relevantCandles = candles.subList(candles.size() - period, candles.size());
        return relevantCandles.stream()
                .mapToDouble(Candle::getVolume)
                .average()
                .orElse(0.0);
    }
    
    public static List<Candle> calculateHeikinAshi(List<Candle> candles) {
        List<Candle> haCandles = new ArrayList<>();
        if (candles.isEmpty()) return haCandles;

        Candle first = candles.get(0);
        Candle haFirst = new Candle();
        haFirst.setTimestamp(first.getTimestamp());
        haFirst.setOpen(first.getOpen());
        haFirst.setClose(first.getClose());
        haFirst.setHigh(first.getHigh());
        haFirst.setLow(first.getLow());
        haFirst.setVolume(first.getVolume());
        haCandles.add(haFirst);

        for (int i = 1; i < candles.size(); i++) {
            Candle curr = candles.get(i);
            Candle prevHa = haCandles.get(i - 1);

            double haClose = (curr.getOpen() + curr.getHigh() + curr.getLow() + curr.getClose()) / 4.0;
            double haOpen = (prevHa.getOpen() + prevHa.getClose()) / 2.0;
            double haHigh = Math.max(curr.getHigh(), Math.max(haOpen, haClose));
            double haLow = Math.min(curr.getLow(), Math.min(haOpen, haClose));

            Candle ha = new Candle();
            ha.setTimestamp(curr.getTimestamp());
            ha.setOpen(haOpen);
            ha.setClose(haClose);
            ha.setHigh(haHigh);
            ha.setLow(haLow);
            ha.setVolume(curr.getVolume());
            haCandles.add(ha);
        }
        return haCandles;
    }

    public static IchimokuResult calculateIchimoku(List<Candle> candles, int conversionPeriod, int basePeriod, int spanBPeriod) {
        List<Double> conversionLine = new ArrayList<>();
        List<Double> baseLine = new ArrayList<>();
        List<Double> leadingSpanA = new ArrayList<>();
        List<Double> leadingSpanB = new ArrayList<>();

        for (int i = 0; i < candles.size(); i++) {
            conversionLine.add(calculateMidPoint(candles, i, conversionPeriod));
            baseLine.add(calculateMidPoint(candles, i, basePeriod));
            
            if (i >= basePeriod) {
                 double spanA = (conversionLine.get(i) != null && baseLine.get(i) != null) 
                         ? (conversionLine.get(i) + baseLine.get(i)) / 2.0 
                         : 0.0; 
                 leadingSpanA.add(spanA);
            } else {
                leadingSpanA.add(null);
            }

            leadingSpanB.add(calculateMidPoint(candles, i, spanBPeriod));
        }

        return IchimokuResult.builder()
                .conversionLine(conversionLine)
                .baseLine(baseLine)
                .leadingSpanA(leadingSpanA)
                .leadingSpanB(leadingSpanB)
                .build();
    }

    private static Double calculateMidPoint(List<Candle> candles, int currentIndex, int period) {
        if (currentIndex < period - 1) return null;
        
        double highest = Double.MIN_VALUE;
        double lowest = Double.MAX_VALUE;
        
        for (int i = 0; i < period; i++) {
            Candle c = candles.get(currentIndex - i);
            if (c.getHigh() > highest) highest = c.getHigh();
            if (c.getLow() < lowest) lowest = c.getLow();
        }
        
        return (highest + lowest) / 2.0;
    }
    
    public static List<Double> calculateADX(List<Candle> candles, int period) {
        List<Double> adxValues = new ArrayList<>();
        if (candles.size() < period * 2) return adxValues;

        List<Double> tr = new ArrayList<>();
        List<Double> dmPlus = new ArrayList<>();
        List<Double> dmMinus = new ArrayList<>();

        for (int i = 1; i < candles.size(); i++) {
            Candle curr = candles.get(i);
            Candle prev = candles.get(i - 1);

            double highDiff = curr.getHigh() - prev.getHigh();
            double lowDiff = prev.getLow() - curr.getLow();

            tr.add(Math.max(curr.getHigh() - curr.getLow(), 
                   Math.max(Math.abs(curr.getHigh() - prev.getClose()), 
                            Math.abs(curr.getLow() - prev.getClose()))));

            dmPlus.add((highDiff > lowDiff && highDiff > 0) ? highDiff : 0.0);
            dmMinus.add((lowDiff > highDiff && lowDiff > 0) ? lowDiff : 0.0);
        }

        List<Double> str = calculateSmoothed(tr, period);
        List<Double> sdmPlus = calculateSmoothed(dmPlus, period);
        List<Double> sdmMinus = calculateSmoothed(dmMinus, period);

        List<Double> dx = new ArrayList<>();
        for (int i = 0; i < str.size(); i++) {
            double diPlus = 100 * sdmPlus.get(i) / str.get(i);
            double diMinus = 100 * sdmMinus.get(i) / str.get(i);
            double sum = diPlus + diMinus;
            dx.add(sum == 0 ? 0 : 100 * Math.abs(diPlus - diMinus) / sum);
        }

        return calculateSmoothed(dx, period);
    }

    private static List<Double> calculateSmoothed(List<Double> values, int period) {
        List<Double> smoothed = new ArrayList<>();
        if (values.size() < period) return smoothed;

        double sum = 0;
        for (int i = 0; i < period; i++) sum += values.get(i);
        smoothed.add(sum / period);

        for (int i = period; i < values.size(); i++) {
            double prev = smoothed.get(smoothed.size() - 1);
            smoothed.add((prev * (period - 1) + values.get(i)) / period);
        }
        return smoothed;
    }
    
    // [추가] SuperTrend 계산
    public static SuperTrendResult calculateSuperTrend(List<Candle> candles, int period, double multiplier) {
        List<Double> superTrend = new ArrayList<>();
        List<Boolean> trend = new ArrayList<>(); // true: Long, false: Short
        
        List<Double> atr = calculateATRList(candles, period);
        
        // ATR 계산에 필요한 데이터만큼 앞부분은 스킵
        int offset = candles.size() - atr.size();
        
        // 초기값 설정
        superTrend.add(0.0);
        trend.add(true);
        
        double prevFinalUpper = 0.0;
        double prevFinalLower = 0.0;
        
        for (int i = 1; i < atr.size(); i++) {
            int candleIdx = i + offset;
            Candle curr = candles.get(candleIdx);
            Candle prev = candles.get(candleIdx - 1);
            double currentAtr = atr.get(i);
            
            double basicUpper = (curr.getHigh() + curr.getLow()) / 2.0 + multiplier * currentAtr;
            double basicLower = (curr.getHigh() + curr.getLow()) / 2.0 - multiplier * currentAtr;
            
            double finalUpper = (basicUpper < prevFinalUpper || prev.getClose() > prevFinalUpper) ? basicUpper : prevFinalUpper;
            double finalLower = (basicLower > prevFinalLower || prev.getClose() < prevFinalLower) ? basicLower : prevFinalLower;
            
            boolean prevTrend = trend.get(i - 1);
            boolean currTrend = prevTrend;
            
            if (prevTrend && curr.getClose() < prevFinalLower) {
                currTrend = false;
            } else if (!prevTrend && curr.getClose() > prevFinalUpper) {
                currTrend = true;
            }
            
            trend.add(currTrend);
            superTrend.add(currTrend ? finalLower : finalUpper);
            
            prevFinalUpper = finalUpper;
            prevFinalLower = finalLower;
        }
        
        return SuperTrendResult.builder()
                .superTrend(superTrend)
                .trend(trend)
                .build();
    }
}
