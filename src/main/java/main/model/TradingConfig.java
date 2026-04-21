package main.model;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import lombok.Data;

@Data
public class TradingConfig {
    private static volatile TradingConfig INSTANCE;

    // API
    private String apiKey;
    private String secretKey;
    private String passphrase;

    // 기본 설정
    private TradingMode mode;
    private TradingType tradingType;
    private StrategyType strategyType;
    private String tradingPair;
    private java.util.List<String> tradingPairs;
    private String timeframe;
    private double investmentAmount;
    private double feeRatePercent;
    private double initialBalance;
    private int candleLimit;
    
    private boolean allowLong;
    private boolean allowShort;

    // 선물 설정
    private int leverage;
    private String marginMode;
    private String productType;

    // 리스크 관리
    private double stopLossPercent;
    private double takeProfitPercent;
    private int atrPeriod;
    private double atrSlMultiplier;
    private double atrTpMultiplier;
    private double maxPositionSize;
    private double dailyMaxLossPercent;
    private int maxOpenPositions;
    private String orderSizingStrategy;
    private double orderPercentOfBalance;
    private double fixedOrderAmount;

    // EMA Trend + ADX + Stochastic 설정
    private int emaShortPeriod;
    private int emaMediumPeriod;
    private int emaLongPeriod;
    private int adxPeriod;
    private double adxThreshold;
    private int stochK;
    private int stochD;
    private int stochSmooth;

    // (사용하지 않는) 기타 전략 설정
    private int shortEmaPeriod, longEmaPeriod, stochasticKPeriod, stochasticDPeriod, stochasticSlowing;
    private double overboughtThreshold, oversoldThreshold;
    private int macdAtrTrendEmaPeriod, macdFastPeriod, macdSlowPeriod, macdSignalPeriod;
    private double orderBlockRewardToRiskRatio;
    private String orderBlockHtf, orderBlockLtf;
    private int bollingerBandsPeriod;
    private double bollingerBandsStdDev;
    private boolean bollingerBandsUseRsiFilter, bollingerBandsUseTrendFilter, bollingerBandsUseStochasticFilter;
    private int bollingerBandsRsiPeriod, bollingerBandsTrendEmaPeriod, bollingerBandsStochasticKPeriod, bollingerBandsStochasticDPeriod, bollingerBandsStochasticSlowing;
    private double bollingerBandsRsiOverbought, bollingerBandsRsiOversold;
    private int shortPeriod, mediumPeriod, trendFilterPeriod;
    private boolean useRsiFilter;
    private double rsiBuyThreshold, rsiSellThreshold;
    private int donchianPeriod, trendFilterEma;
    private int downtrendRsiTrendEmaPeriod, downtrendRsiRsiPeriod;
    private double downtrendRsiThreshold;
    private double volumeMultiplier;
    private int waveAnalysisDepth;
    private double fibonacciTolerance;

    // 텔레그램 설정
    private boolean telegramEnabled;
    private String telegramBotToken;
    private String telegramChatId;
    private long reportIntervalHours;

    // 실행 주기 설정
    private long candleIntervalSeconds;
    private long tickerIntervalSeconds;
    private long positionStatusMinutes;

    private TradingConfig() {}

    public static TradingConfig getInstance() {
        if (INSTANCE == null) {
            synchronized (TradingConfig.class) {
                if (INSTANCE == null) {
                    INSTANCE = loadInternal();
                }
            }
        }
        return INSTANCE;
    }

    private static TradingConfig loadInternal() {
        Config config = ConfigFactory.load();
        TradingConfig tc = new TradingConfig();

        // API
        tc.setApiKey(config.getString("bitget.apiKey"));
        tc.setSecretKey(config.getString("bitget.secretKey"));
        tc.setPassphrase(config.getString("bitget.passphrase"));

        // 기본 설정
        tc.setMode(TradingMode.valueOf(config.getString("trading.mode")));
        tc.setTradingType(TradingType.valueOf(config.getString("trading.type")));
        tc.setStrategyType(StrategyType.valueOf(config.getString("trading.strategy")));
        tc.setTradingPair(config.getString("trading.pair"));
        if (config.hasPath("trading.pairs")) tc.setTradingPairs(config.getStringList("trading.pairs"));
        tc.setTimeframe(config.getString("trading.timeframe"));
        if (config.hasPath("trading.investmentAmount")) tc.setInvestmentAmount(config.getDouble("trading.investmentAmount"));
        if (config.hasPath("trading.feeRatePercent")) tc.setFeeRatePercent(config.getDouble("trading.feeRatePercent"));
        tc.setCandleLimit(config.getInt("trading.candleLimit"));
        
        tc.setAllowLong(config.hasPath("trading.allowLong") ? config.getBoolean("trading.allowLong") : true);
        tc.setAllowShort(config.hasPath("trading.allowShort") ? config.getBoolean("trading.allowShort") : true);

        // 페이퍼 트레이딩
        if (tc.getMode() == TradingMode.PAPER) tc.setInitialBalance(config.getDouble("trading.paper.initialBalance"));

        // 선물
        if (tc.getTradingType() == TradingType.FUTURES) {
            tc.setLeverage(config.getInt("trading.futures.leverage"));
            tc.setMarginMode(config.getString("trading.futures.marginMode"));
            tc.setProductType(config.getString("trading.futures.productType"));
        }

        // 리스크 관리
        tc.setStopLossPercent(config.getDouble("risk.stopLossPercent"));
        tc.setTakeProfitPercent(config.getDouble("risk.takeProfitPercent"));
        tc.setAtrPeriod(config.getInt("risk.atrPeriod"));
        tc.setAtrSlMultiplier(config.getDouble("risk.atrSlMultiplier"));
        tc.setAtrTpMultiplier(config.hasPath("risk.atrTpMultiplier") ? config.getDouble("risk.atrTpMultiplier") : tc.getAtrSlMultiplier() * 1.5);
        tc.setMaxPositionSize(config.getDouble("risk.maxPositionSize"));
        tc.setDailyMaxLossPercent(config.getDouble("risk.dailyMaxLossPercent"));
        tc.setMaxOpenPositions(config.getInt("risk.maxOpenPositions"));
        tc.setOrderSizingStrategy(config.getString("risk.orderSizingStrategy"));
        tc.setOrderPercentOfBalance(config.getDouble("risk.orderPercentOfBalance"));
        tc.setFixedOrderAmount(config.getDouble("risk.fixedOrderAmount"));

        // [수정] EMA Trend + ADX + Stochastic 설정 로드
        if (config.hasPath("emaTrend")) {
            tc.setEmaShortPeriod(config.getInt("emaTrend.emaShortPeriod"));
            tc.setEmaMediumPeriod(config.getInt("emaTrend.emaMediumPeriod"));
            tc.setEmaLongPeriod(config.getInt("emaTrend.emaLongPeriod"));
            tc.setAdxPeriod(config.getInt("emaTrend.adxPeriod"));
            tc.setAdxThreshold(config.getDouble("emaTrend.adxThreshold"));
            tc.setStochK(config.getInt("emaTrend.stochK"));
            tc.setStochD(config.getInt("emaTrend.stochD"));
            tc.setStochSmooth(config.getInt("emaTrend.stochSmooth"));
        }
        
        // 볼린저 밴드 설정 로드 (필요 시)
        if (config.hasPath("bollingerBands")) {
            tc.setBollingerBandsPeriod(config.getInt("bollingerBands.period"));
            tc.setBollingerBandsStdDev(config.getDouble("bollingerBands.stdDev"));
            tc.setBollingerBandsRsiPeriod(config.getInt("bollingerBands.rsiPeriod"));
            tc.setBollingerBandsRsiOverbought(config.getDouble("bollingerBands.rsiOverbought"));
            tc.setBollingerBandsRsiOversold(config.getDouble("bollingerBands.rsiOversold"));
        }

        // 텔레그램
        tc.setTelegramEnabled(config.getBoolean("telegram.enabled"));
        tc.setTelegramBotToken(config.getString("telegram.botToken"));
        tc.setTelegramChatId(config.getString("telegram.chatId"));
        tc.setReportIntervalHours(config.getLong("telegram.reportIntervalHours"));

        // 실행 주기
        tc.setCandleIntervalSeconds(config.hasPath("trading.candleIntervalSeconds") ? config.getLong("trading.candleIntervalSeconds") : 5);
        tc.setTickerIntervalSeconds(config.hasPath("trading.tickerIntervalSeconds") ? config.getLong("trading.tickerIntervalSeconds") : 1);
        tc.setPositionStatusMinutes(config.getLong("trading.positionStatusMinutes"));

        return tc;
    }
}
