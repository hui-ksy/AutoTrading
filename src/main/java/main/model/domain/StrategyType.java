package main.model.domain;

public enum StrategyType {
    BOLLINGER_BANDS_SCALPING,
    DONCHIAN_BREAKOUT,
    DOWNTREND_RSI,
    EMA_STOCHASTIC_SCALPING,
    TRIPLE_EMA,
    ELLIOTT_WAVE,
    ORDER_BLOCK,
    MACD_ATR_TREND,
    HEIKIN_ASHI_ICHIMOKU,
    SUPER_TREND,           // SuperTrend 전략
    DYNAMIC_EXIT_SCALPING,  // E0V1E_53_Sharpe 류 동적 손절/익절 스캘핑
    BOLLINGER_BAND_REVERSION, // BB 평균회귀
    ALTCOIN_EMA_SCALPING      // 알트코인 EMA 크로스 스캘핑 (현재 활성)
}
