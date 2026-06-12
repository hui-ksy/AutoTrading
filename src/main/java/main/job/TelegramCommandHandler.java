package main.job;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import main.AutoTrader;
import main.account.AccountBalanceProvider;
import main.model.TradingConfig;
import main.util.ConfigFileUpdater;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class TelegramCommandHandler implements TelegramNotifier.CommandHandler {

    private final AtomicBoolean botRunning;
    private final AtomicBoolean gracefulShutdown;
    private final AtomicBoolean directionFilterEnabled;
    private final TradingConfig config;
    private final AccountBalanceProvider balanceProvider;
    private final List<AutoTrader> autoTraders;
    private final Runnable stopAll;
    private final Runnable startAll;

    @Setter private volatile DailyOptimizer dailyOptimizer;
    @Setter private volatile CoinAdder coinAdder;

    public TelegramCommandHandler(
            AtomicBoolean botRunning, AtomicBoolean gracefulShutdown, AtomicBoolean directionFilterEnabled,
            TradingConfig config, AccountBalanceProvider balanceProvider, List<AutoTrader> autoTraders,
            Runnable stopAll, Runnable startAll) {
        this.botRunning           = botRunning;
        this.gracefulShutdown     = gracefulShutdown;
        this.directionFilterEnabled = directionFilterEnabled;
        this.config               = config;
        this.balanceProvider      = balanceProvider;
        this.autoTraders          = autoTraders;
        this.stopAll              = stopAll;
        this.startAll             = startAll;
    }

    @Override
    public String handle(String command) {
        if (!botRunning.get() && !command.equals("/start") && !command.equals("/help")) {
            return "봇이 현재 정지 상태입니다. /start 로 재시작하거나 /help 를 입력하세요.";
        }

        if (command.equals("/stop")) {
            gracefulShutdown.set(true);
            stopAll.run();
            return "🚨 모든 봇이 정지되었습니다. /start 로 재시작하세요.";
        }
        if (command.equals("/start")) {
            startAll.run();
            return "✅ 모든 봇이 재시작되었습니다.";
        }
        if (command.equals("/balance")) {
            return String.format("💰 <b>계좌 잔고 현황</b>\n\n<b>총 자산:</b> $%.2f\n<b>가용 잔고:</b> $%.2f",
                    balanceProvider.getTotalEquity(), balanceProvider.getAvailableBalance());
        }
        if (command.equals("/close all")) {
            closeAllPositions();
            return "⚠️ 모든 포지션 시장가 청산 명령을 실행했습니다.";
        }
        if (command.startsWith("/close ")) {
            String symbol = resolveSymbol(command.substring("/close ".length()));
            return closeSpecificPosition(symbol);
        }
        if (command.equals("/direction on")) {
            directionFilterEnabled.set(true);
            return "✅ 방향성 필터가 활성화되었습니다.";
        }
        if (command.equals("/direction off")) {
            directionFilterEnabled.set(false);
            return "🛑 방향성 필터가 비활성화되었습니다.";
        }
        if (command.startsWith("/percent ")) {
            try {
                double pct = Double.parseDouble(command.substring("/percent ".length()).trim());
                if (pct <= 0 || pct > 100) return "❌ 1~100 사이의 값을 입력하세요.";
                config.setOrderPercentOfBalance(pct);
                updateConfigPercent(pct);
                return String.format("✅ 진입 비율이 <b>%.1f%%</b>로 변경되었습니다.", pct);
            } catch (NumberFormatException e) {
                return "❌ 잘못된 숫자 형식입니다. 예: /percent 10";
            }
        }
        if (command.equals("/help")) return getHelpMessage();
        if (command.equals("/holdtime off")) {
            config.setMaxHoldEnabled(false);
            updateConfigHoldEnabled(false);
            return "🛑 최대 보유 시간 제한이 <b>비활성화</b>되었습니다.";
        }
        if (command.equals("/holdtime on")) {
            int hours = config.getMaxHoldHours() > 0 ? config.getMaxHoldHours() : 12;
            config.setMaxHoldEnabled(true);
            config.setMaxHoldHours(hours);
            updateConfigHoldEnabled(true);
            updateConfigHoldTime(hours);
            return String.format("✅ 최대 보유 시간이 <b>%d시간</b>으로 활성화되었습니다.", hours);
        }
        if (command.startsWith("/holdtime ")) {
            try {
                int hours = Integer.parseInt(command.substring("/holdtime ".length()).trim());
                if (hours <= 0) return "❌ 1 이상의 값을 입력하세요.";
                config.setMaxHoldEnabled(true);
                config.setMaxHoldHours(hours);
                updateConfigHoldEnabled(true);
                updateConfigHoldTime(hours);
                return String.format("✅ 최대 보유 시간이 <b>%d시간</b>으로 변경되었습니다.", hours);
            } catch (NumberFormatException e) {
                return "❌ 잘못된 숫자 형식입니다. 예: /holdtime 24";
            }
        }
        if (command.equals("/optimize")) {
            if (dailyOptimizer != null) dailyOptimizer.triggerNow();
            return "⏳ 전체 코인 파라미터 최적화를 시작합니다. 잠시 후 결과가 전송됩니다.";
        }
        if (command.startsWith("/optimize ")) {
            String sym = resolveSymbol(command.substring("/optimize ".length()));
            if (dailyOptimizer != null) dailyOptimizer.triggerNow(sym);
            return "⏳ " + sym + " 파라미터 최적화를 시작합니다. 잠시 후 결과가 전송됩니다.";
        }
        if (command.startsWith("/backtest ")) {
            String sym = resolveSymbol(command.substring("/backtest ".length()));
            if (dailyOptimizer != null) dailyOptimizer.triggerBacktest(sym);
            return "⏳ " + sym + " 백테스트를 시작합니다. 잠시 후 결과가 전송됩니다.";
        }
        if (command.startsWith("/add ")) {
            String sym = resolveSymbol(command.substring("/add ".length()));
            if (coinAdder != null) coinAdder.triggerAdd(sym);
            return "⏳ " + sym + " 추가를 시작합니다. 백테스트 완료 후 자동으로 봇이 시작됩니다.";
        }

        String optimizerReply = dailyOptimizer != null ? dailyOptimizer.handleReply(command) : null;
        if (optimizerReply != null) return optimizerReply;

        return "알 수 없는 명령어입니다. /help 를 입력하세요.";
    }

    private void closeAllPositions() {
        for (AutoTrader trader : autoTraders) {
            if (trader.getCurrentPosition() != null) {
                trader.closePosition("강제 청산");
            }
        }
    }

    private String closeSpecificPosition(String symbol) {
        Optional<AutoTrader> targetTrader = autoTraders.stream()
                .filter(t -> t.getPair().equalsIgnoreCase(symbol))
                .findFirst();
        if (targetTrader.isPresent() && targetTrader.get().getCurrentPosition() != null) {
            targetTrader.get().closePosition("강제 청산 (텔레그램)");
            return String.format("<b>%s</b> 포지션 시장가 청산 명령을 보냈습니다.", symbol);
        } else if (targetTrader.isPresent()) {
            return String.format("<b>%s</b> 포지션이 현재 없습니다.", symbol);
        } else {
            return String.format("<b>%s</b> 페어를 찾을 수 없습니다.", symbol);
        }
    }

    static String resolveSymbol(String input) {
        String upper = input.trim().toUpperCase();
        if (!upper.endsWith("USDT")) upper = upper + "USDT";
        return upper;
    }

    private void updateConfigPercent(double pct) {
        try {
            ConfigFileUpdater.replace("orderPercentOfBalance\\s*=\\s*[0-9.]+",
                    String.format("orderPercentOfBalance = %.1f", pct));
            log.info("application.conf orderPercentOfBalance → {}% 업데이트 완료", pct);
        } catch (IOException e) {
            log.error("application.conf 업데이트 실패: {}", e.getMessage());
        }
    }

    private void updateConfigHoldTime(int hours) {
        try {
            ConfigFileUpdater.replace("maxHoldHours\\s*=\\s*[0-9]+", "maxHoldHours = " + hours);
            log.info("application.conf maxHoldHours → {} 업데이트 완료", hours);
        } catch (IOException e) {
            log.error("application.conf 업데이트 실패: {}", e.getMessage());
        }
    }

    private void updateConfigHoldEnabled(boolean enabled) {
        try {
            ConfigFileUpdater.replace("maxHoldEnabled\\s*=\\s*(true|false)", "maxHoldEnabled = " + enabled);
            log.info("application.conf maxHoldEnabled → {} 업데이트 완료", enabled);
        } catch (IOException e) {
            log.error("application.conf 업데이트 실패: {}", e.getMessage());
        }
    }

    private String getHelpMessage() {
        return "<b>사용 가능한 명령어:</b>\n" +
                "/balance - 계좌 잔고 현황\n" +
                "/stop - 모든 봇 정지\n" +
                "/stop [심볼] - 특정 봇 정지 (예: /stop xrp)\n" +
                "/start - 모든 봇 재시작\n" +
                "/start [심볼] - 특정 봇 재시작 (예: /start xrp)\n" +
                "/close all - 모든 포지션 청산\n" +
                "/close [심볼] - 특정 포지션 청산 (예: /close xrp)\n" +
                "/direction on/off - 방향성 필터 활성화/비활성화\n" +
                "/percent [N] - 진입 비율 변경 (예: /percent 10)\n" +
                "/holdtime [N] - 최대 보유 시간 변경 (예: /holdtime 12)\n" +
                "/holdtime on - 최대 보유 시간 활성화\n" +
                "/holdtime off - 최대 보유 시간 비활성화\n" +
                "/optimize - 전체 코인 파라미터 최적화\n" +
                "/optimize [코인] - 특정 코인만 최적화 (예: /optimize pepe)\n" +
                "/backtest [코인] - 백테스트 결과 조회 (예: /backtest sol)\n" +
                "/add [코인] - 새 코인 추가 및 봇 시작 (예: /add link)\n" +
                "/help - 명령어 목록 표시";
    }
}
