package main.core.trading;

import lombok.extern.slf4j.Slf4j;
import main.account.LiveAccountBalanceProvider;
import main.api.bitget.BitgetFuturesApiClient;
import main.api.bitget.PaperTradingClient;
import main.api.bitget.TradeClient;
import main.job.TelegramNotifier;
import main.model.domain.CumulativeStats;
import main.model.domain.Position;
import main.model.domain.Signal;
import main.model.config.SymbolConfig;
import main.model.config.TradingConfig;
import main.model.config.TradingMode;
import main.model.config.TradingType;
import main.strategy.core.StrategyFactory;
import main.strategy.core.TradingStrategy;
import main.util.PriceFormatter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Slf4j
public class TradingBotOrchestrator {

  private final List<AutoTrader> traders;
  private final TradingConfig config;
  private final LiveAccountBalanceProvider balanceProvider;
  private final TelegramNotifier telegram;
  private final AtomicBoolean directionFilterEnabled;
  private final TradeRecorder recorder;
  private PaperTradingClient paperClient;
  private double initialBalance;

  public TradingBotOrchestrator(
      TradingConfig config,
      LiveAccountBalanceProvider balanceProvider,
      TelegramNotifier telegram,
      AtomicBoolean directionFilterEnabled,
      TradeRecorder recorder) {
    this.config = config;
    this.balanceProvider = balanceProvider;
    this.telegram = telegram;
    this.directionFilterEnabled = directionFilterEnabled;
    this.recorder = recorder;
    this.traders = new ArrayList<>();
    this.paperClient = null;
    this.initialBalance = 0;
  }

  public void setPaperClient(PaperTradingClient paperClient) {
    this.paperClient = paperClient;
  }

  public void addTrader(String pair) {
    TradeClient apiClient =
        (config.getMode() == TradingMode.LIVE) ? createApiClient(config, pair) : null;
    if (apiClient != null && initialBalance == 0) {
      initialBalance = ((BitgetFuturesApiClient) apiClient).getAccountEquity("USDT");
      recorder.setInitialBalance(initialBalance);
    }
    SymbolConfig symbolConfig =
        config.getSymbolConfigs().computeIfAbsent(pair, SymbolConfig::defaults);
    TradingStrategy strategy = StrategyFactory.createStrategy(config, symbolConfig);
    AutoTrader trader =
        new AutoTrader(
            apiClient,
            paperClient,
            strategy,
            config,
            pair,
            telegram,
            recorder::record,
            this::isDirectionBlocked,
            balanceProvider);
    traders.add(trader);
  }

  public List<AutoTrader> getTraders() {
    return traders;
  }

  private boolean isDirectionBlocked(String thisPair, Signal.Action action) {
    if (!directionFilterEnabled.get()) return false;
    for (AutoTrader trader : traders) {
      if (trader.getPair().equalsIgnoreCase(thisPair)) continue;
      Position pos = trader.getCurrentPosition();
      if (pos == null) continue;
      String side = pos.getSide();
      if ("BUY".equals(side) && action == Signal.Action.SHORT) return true;
      if ("SHORT".equals(side) && action == Signal.Action.BUY) return true;
    }
    return false;
  }

  private TradeClient createApiClient(TradingConfig config, String pair) {
    if (config.getTradingType() == TradingType.FUTURES) {
      BitgetFuturesApiClient client =
          new BitgetFuturesApiClient(
              config.getApiKey(),
              config.getSecretKey(),
              config.getPassphrase(),
              config.getProductType(),
              config.getMarginMode());
      client.setMarginMode(pair, config.getMarginMode());

      setLeverageWithRetry(client, pair, config.getLeverage(), "long");
      setLeverageWithRetry(client, pair, config.getLeverage(), "short");

      return client;
    }
    return null;
  }

  private void setLeverageWithRetry(
      BitgetFuturesApiClient client, String pair, int targetLeverage, String side) {
    int currentLeverage = targetLeverage;
    while (currentLeverage >= 1) {
      boolean success = client.setLeverage(pair, currentLeverage, side);
      if (success) {
        if (currentLeverage != targetLeverage) {
          log.warn(
              "[{}] {} 레버리지 조정됨: {}x -> {}x",
              pair,
              side,
              targetLeverage,
              currentLeverage);
        }
        return;
      }

      int nextLeverage = (int) (currentLeverage * 0.75);
      if (nextLeverage == currentLeverage) nextLeverage--;

      currentLeverage = nextLeverage;
      try { Thread.sleep(200); } catch (InterruptedException ignored) {}
    }
    log.error("[{}] {} 레버리지 설정 최종 실패", pair, side);
  }

  public void startBalanceMonitor() {
    if (traders.isEmpty()) return;

    ScheduledExecutorService balanceScheduler = Executors.newSingleThreadScheduledExecutor();
    balanceScheduler.scheduleAtFixedRate(
        () -> {
          try {
            TradeClient client = traders.get(0).getApiClient();
            if (client instanceof BitgetFuturesApiClient) {
              BitgetFuturesApiClient futuresClient = (BitgetFuturesApiClient) client;
              balanceProvider.update(
                  futuresClient.getAccountEquity("USDT"),
                  futuresClient.getAvailableBalance("USDT"));
            }
          } catch (Exception e) {
            log.error("잔고 조회 중 오류 발생", e);
          }
        },
        0,
        5,
        TimeUnit.SECONDS);
  }

  public void startPositionReconciliation() {
    ScheduledExecutorService reconciliationScheduler = Executors.newSingleThreadScheduledExecutor();
    reconciliationScheduler.scheduleAtFixedRate(
        () -> {
          try {
            Optional<AutoTrader> liveTraderOpt =
                traders.stream().filter(t -> t.getApiClient() != null).findFirst();
            if (liveTraderOpt.isEmpty()) return;

            BitgetFuturesApiClient sampleClient =
                (BitgetFuturesApiClient) liveTraderOpt.get().getApiClient();
            List<Position> exchangePositions = sampleClient.getAllPositions();
            Map<String, Position> exchangePositionMap =
                exchangePositions.stream()
                    .collect(Collectors.toMap(Position::getSymbol, p -> p));

            Map<String, AutoTrader> traderMap =
                traders.stream().collect(Collectors.toMap(AutoTrader::getPair, t -> t));

            for (AutoTrader trader : traders) {
              if (trader.getCurrentPosition() != null
                  && !exchangePositionMap.containsKey(trader.getPair())) {
                trader.handleExternalClose();
              }
            }

            for (Map.Entry<String, Position> entry : exchangePositionMap.entrySet()) {
              String symbol = entry.getKey();
              AutoTrader trader = traderMap.get(symbol);
              if (trader != null && trader.getCurrentPosition() == null) {
                log.warn("[동기화] {}에 미인수 포지션을 발견했습니다. 관리를 시작합니다.", symbol);
                telegram.notifyReconciliationInfo(
                    String.format("🕵️ %s의 미인수 포지션을 발견하여 관리를 시작합니다.", symbol));
                trader.checkAndLoadExistingPosition();
              }
            }
          } catch (Exception e) {
            log.error("[동기화] 포지션 동기화 중 오류 발생", e);
          }
        },
        5,
        5,
        TimeUnit.SECONDS);
  }

  public void startSummaryLogging() {
    ScheduledExecutorService summaryScheduler = Executors.newSingleThreadScheduledExecutor();
    summaryScheduler.scheduleAtFixedRate(
        () -> {
          List<AutoTrader> tradersWithPositions =
              traders.stream()
                  .filter(t -> t.getCurrentPosition() != null)
                  .collect(Collectors.toList());

          if (tradersWithPositions.isEmpty()) {
            return;
          }
          List<String> telegramSummaries = new ArrayList<>();

          for (AutoTrader trader : tradersWithPositions) {
            Position position = trader.getCurrentPosition();

            double currentPrice =
                (config.getMode() == TradingMode.LIVE)
                    ? trader.getApiClient().getTickerPrice(trader.getPair())
                    : paperClient.getTickerPrice(trader.getPair());

            String currentPriceStr;
            String pnlStr;

            if (currentPrice > 0) {
              double entryPrice = position.getEntryPrice();
              double pnlPercent = (currentPrice / entryPrice - 1) * 100;
              if ("SHORT".equals(position.getSide())) {
                pnlPercent *= -1;
              }
              pnlPercent *= config.getLeverage();

              currentPriceStr = PriceFormatter.format(currentPrice);
              pnlStr = String.format("%.2f%%", pnlPercent);
            } else {
              currentPriceStr = "조회 실패";
              pnlStr = "N/A";
            }

            log.info("[{}] {} | 진입가: {} | 현재가: {} | 미실현 손익: {}",
                position.getSymbol(), position.getSide(),
                PriceFormatter.format(position.getEntryPrice()),
                currentPriceStr, pnlStr);

            if (currentPrice > 0) {
              String sideEmoji = "BUY".equals(position.getSide()) ? "🟢" : "🔴";
              String pnlEmoji =
                  Double.parseDouble(pnlStr.replace("%", "")) >= 0 ? "📈" : "📉";
              String telegramMsg =
                  String.format(
                      "%s <b>%s</b> %s\n" + "진입: $%s ➡️ 현재: $%s\n" + "%s <b>%s</b>",
                      sideEmoji,
                      position.getSymbol(),
                      position.getSide(),
                      PriceFormatter.format(position.getEntryPrice()),
                      currentPriceStr,
                      pnlEmoji,
                      pnlStr);
              telegramSummaries.add(telegramMsg);
            }
          }

          CumulativeStats stats = recorder.calculateStats();
          log.info("총 거래: {}, 승률: {}%, 총 손익: {}${}",
              stats.getTotalTrades(), String.format("%.2f", stats.getWinRate()),
              (stats.getTotalProfit() >= 0 ? "+" : ""),
              String.format("%.2f", stats.getTotalProfit()));

          if (!telegramSummaries.isEmpty()) {
            telegram.notifyStatusSummary(telegramSummaries, stats);
          }

        },
        5,
        5,
        TimeUnit.MINUTES);
  }
}
