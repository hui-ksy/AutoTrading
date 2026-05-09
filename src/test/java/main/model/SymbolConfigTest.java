package main.model;

import main.strategy.BollingerBandReversionStrategy;
import main.strategy.StrategyFactory;
import main.strategy.TradingStrategy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SymbolConfigTest {

    private static TradingConfig config;

    @BeforeAll
    static void loadConfig() {
        config = TradingConfig.getInstance();
    }

    // ── SymbolConfig.defaults() ───────────────────────────────────────────────

    @Test
    void defaults_setsExpectedValues() {
        SymbolConfig sc = SymbolConfig.defaults("TESTUSDT");
        assertEquals("TESTUSDT", sc.getSymbol());
        assertEquals(StrategyType.BOLLINGER_BAND_REVERSION, sc.getStrategyType());
        assertEquals("15m", sc.getTimeframe());
        assertEquals(10, sc.getLeverage());
        assertEquals(100, sc.getCandleLimit());
        assertEquals(17, sc.getBbPeriod());
        assertEquals(2.6, sc.getBbStdDev(), 0.001);
        assertEquals(14, sc.getRsiPeriod());
        assertEquals(15, sc.getCandleIntervalSeconds());
        assertEquals(1, sc.getTickerIntervalSeconds());
    }

    // ── TradingConfig symbols 블록 파싱 ──────────────────────────────────────

    @Test
    void tradingConfig_loadsAllThreePairs() {
        Map<String, SymbolConfig> symbolConfigs = config.getSymbolConfigs();

        assertFalse(symbolConfigs.isEmpty(), "symbols 블록이 파싱되어야 함");
        assertTrue(symbolConfigs.containsKey("PEPEUSDT"), "PEPEUSDT 설정 누락");
        assertTrue(symbolConfigs.containsKey("DOGEUSDT"), "DOGEUSDT 설정 누락");
        assertTrue(symbolConfigs.containsKey("SOLUSDT"),  "SOLUSDT 설정 누락");
        assertFalse(symbolConfigs.containsKey("LTCUSDT"), "LTCUSDT는 제거됐어야 함");
    }

    @Test
    void pepeusdt_hasCorrectParams() {
        SymbolConfig sc = config.getSymbolConfigs().get("PEPEUSDT");
        assertNotNull(sc);
        assertEquals(17, sc.getBbPeriod());
        assertEquals(2.6, sc.getBbStdDev(), 0.001);
        assertEquals(30, sc.getRsiOversold());
        assertEquals(70, sc.getRsiOverbought());
        assertEquals(3.5, sc.getSlMult(), 0.001);
        assertEquals(5.0, sc.getTpMult(), 0.001);
    }

    @Test
    void dogeusdt_hasCorrectParams() {
        SymbolConfig sc = config.getSymbolConfigs().get("DOGEUSDT");
        assertNotNull(sc);
        assertEquals(2.0, sc.getSlMult(), 0.001);
        assertEquals(7.0, sc.getTpMult(), 0.001);
    }

    @Test
    void solusdt_hasCorrectParams() {
        SymbolConfig sc = config.getSymbolConfigs().get("SOLUSDT");
        assertNotNull(sc);
        assertEquals(4.0, sc.getSlMult(), 0.001);
        assertEquals(7.0, sc.getTpMult(), 0.001);
    }

    @Test
    void symbolConfigs_inheritsGlobalCandleInterval() {
        // symbols 블록에 candleIntervalSeconds 없으면 글로벌 값(15) 상속
        SymbolConfig sc = config.getSymbolConfigs().get("PEPEUSDT");
        assertNotNull(sc);
        assertEquals(config.getCandleIntervalSeconds(), sc.getCandleIntervalSeconds());
    }

    // ── 심볼별 파라미터 독립성 ────────────────────────────────────────────────

    @Test
    void symbols_haveIndependentSlTpParams() {
        SymbolConfig doge = config.getSymbolConfigs().get("DOGEUSDT");
        SymbolConfig sol  = config.getSymbolConfigs().get("SOLUSDT");
        assertNotNull(doge);
        assertNotNull(sol);
        assertNotEquals(doge.getSlMult(), sol.getSlMult(), "DOGE/SOL SL 배수가 달라야 함");
    }

    // ── StrategyFactory.createStrategy(config, sc) ────────────────────────────

    @Test
    void strategyFactory_createFromSymbolConfig_returnsBollingerStrategy() {
        SymbolConfig sc = SymbolConfig.defaults("TESTUSDT");
        TradingStrategy strategy = StrategyFactory.createStrategy(config, sc);

        assertNotNull(strategy);
        assertInstanceOf(BollingerBandReversionStrategy.class, strategy,
            "SymbolConfig 기반 전략은 BollingerBandReversionStrategy여야 함");
    }

    @Test
    void strategyFactory_differentSymbolConfigs_produceIndependentInstances() {
        SymbolConfig sc1 = config.getSymbolConfigs().get("PEPEUSDT");
        SymbolConfig sc2 = config.getSymbolConfigs().get("DOGEUSDT");

        TradingStrategy s1 = StrategyFactory.createStrategy(config, sc1);
        TradingStrategy s2 = StrategyFactory.createStrategy(config, sc2);

        assertNotSame(s1, s2, "심볼마다 독립된 전략 인스턴스여야 함");
    }

    // ── computeIfAbsent fallback ──────────────────────────────────────────────

    @Test
    void unknownSymbol_fallsBackToDefaults() {
        SymbolConfig sc = config.getSymbolConfigs().computeIfAbsent("UNKNOWNUSDT", SymbolConfig::defaults);
        assertNotNull(sc);
        assertEquals("UNKNOWNUSDT", sc.getSymbol());
        assertEquals(StrategyType.BOLLINGER_BAND_REVERSION, sc.getStrategyType());
    }
}