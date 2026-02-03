package main.job;

import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.UpdatesListener;
import com.pengrad.telegrambot.model.Update;
import com.pengrad.telegrambot.request.SendMessage;
import lombok.extern.slf4j.Slf4j;
import main.model.CumulativeStats;
import main.model.Position;
import main.model.Signal;
import main.model.Trade;
import main.model.TradingConfig;

import java.text.DecimalFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
public class TelegramNotifier {

    public interface CommandHandler {
        String handle(String command);
    }

    private final TelegramBot bot;
    private final String chatId;
    private final boolean enabled;
    private final DecimalFormat priceFormat = new DecimalFormat("#,##0.0000");
    private final DecimalFormat usdtFormat = new DecimalFormat("#,##0.00");
    private final DecimalFormat percentFormat = new DecimalFormat("0.00");
    private final DateTimeFormatter timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    private CommandHandler commandHandler;

    public TelegramNotifier(String botToken, String chatId, boolean enabled) {
        this.chatId = chatId;
        this.enabled = enabled;
        if (enabled && botToken != null && !botToken.isEmpty()) {
            this.bot = new TelegramBot(botToken);
            
            this.bot.setUpdatesListener(updates -> {
                for (Update update : updates) {
                    if (update.message() != null && update.message().text() != null) {
                        String text = update.message().text();
                        long senderChatId = update.message().chat().id();
                        
                        if (String.valueOf(senderChatId).equals(this.chatId)) {
                            processCommand(text);
                        }
                    }
                }
                return UpdatesListener.CONFIRMED_UPDATES_ALL;
            });
            
        } else {
            this.bot = null;
        }
    }
    
    public void setCommandHandler(CommandHandler handler) {
        this.commandHandler = handler;
    }
    
    private void processCommand(String command) {
        if (commandHandler == null) return;
        
        log.info("텔레그램 명령어 수신: {}", command);
        String response = commandHandler.handle(command);
        
        if (response != null && !response.isEmpty()) {
            sendMessage(response);
        }
    }

    public void sendStartupMessage() {
        if (!enabled) return;
        sendMessage("🤖 <b>Bitget 트레이딩봇 시작</b>\n" + "⏰ " + getCurrentTime() + "\n\n명령어 목록을 보려면 /help 를 입력하세요.");
    }

    public void notifyEnterPosition(Position position, Signal signal, TradingConfig config) {
        if (!enabled) return;
        String sideEmoji = "BUY".equalsIgnoreCase(position.getSide()) ? "🟢" : "🔴";
        String sideText = "BUY".equalsIgnoreCase(position.getSide()) ? "롱(LONG)" : "숏(SHORT)";
        double marginUsed = (position.getEntryPrice() * position.getQuantity()) / config.getLeverage();

        String text = String.format(
                "%s <b>%s %s 진입 완료</b>\n\n" +
                        "<b>수량:</b> %.4f\n" +
                        "<b>단가:</b> $%s\n" +
                        "<b>증거금:</b> $%s (레버리지 %dx)\n\n" +
                        "<b>손절(SL):</b> $%s\n" +
                        "<b>익절(TP):</b> $%s",
                sideEmoji,
                position.getSymbol(),
                sideText,
                position.getQuantity(),
                priceFormat.format(position.getEntryPrice()),
                usdtFormat.format(marginUsed),
                config.getLeverage(),
                priceFormat.format(position.getStopLoss()),
                priceFormat.format(position.getTakeProfit())
        );
        sendMessage(text);
    }

    // [수정] 포지션 인수 알림에 증거금 정보 추가
    public void notifyPositionTakeover(Position position, TradingConfig config) {
        if (!enabled) return;
        String sideEmoji = "🔵";
        String sideText = "BUY".equalsIgnoreCase(position.getSide()) ? "롱(LONG)" : "숏(SHORT)";
        double marginUsed = (position.getEntryPrice() * position.getQuantity()) / config.getLeverage();
        
        String text = String.format(
                "%s <b>기존 %s 포지션 인수</b>\n\n" +
                        "<b>방향:</b> %s\n" +
                        "<b>수량:</b> %.4f\n" +
                        "<b>평단:</b> $%s\n" +
                        "<b>증거금:</b> $%s (레버리지 %dx)\n\n" +
                        "이제부터 해당 포지션의 익절/손절 관리를 시작합니다.",
                sideEmoji, 
                position.getSymbol(),
                sideText,
                position.getQuantity(),
                priceFormat.format(position.getEntryPrice()),
                usdtFormat.format(marginUsed),
                config.getLeverage()
        );
        sendMessage(text);
    }

    public void notifyReconciliationInfo(String message) {
        if (!enabled) return;
        sendMessage("🔧 <b>포지션 동기화 알림</b>\n\n" + message);
    }

    public void notifyExitSummary(Trade trade, CumulativeStats stats) {
        if (!enabled) return;

        String tradeEmoji = trade.getProfit() >= 0 ? "💰" : "😢";
        String resultText = trade.getProfit() >= 0 ? "수익 실현" : "손실 확정";
        String sideText = "BUY".equalsIgnoreCase(trade.getSide()) ? "롱" : "숏";

        String tradeResultText = String.format(
                "%s <b>%s (%s / %s)</b>\n" +
                        "<b>순손익:</b> %s$%.2f (%.2f%%)\n" +
                        "<b>수수료:</b> $%.2f\n" +
                        "<b>진입:</b> $%s, <b>청산:</b> $%s",
                tradeEmoji, resultText, trade.getSymbol(), sideText,
                trade.getProfit() >= 0 ? "+" : "", trade.getProfit(), trade.getProfitPercent(),
                trade.getFee(),
                priceFormat.format(trade.getEntryPrice()), priceFormat.format(trade.getExitPrice())
        );

        String profitEmoji = stats.getTotalProfit() >= 0 ? "📈" : "📉";
        String cumulativeStatsText = String.format(
                "📊 <b>누적 통계</b>\n" +
                        "<b>현재 잔고:</b> $%.2f\n" +
                        "%s <b>총 손익:</b> %s$%.2f (%.2f%%)\n" +
                        "<b>총 거래:</b> %d (승: %d / 패: %d)\n" +
                        "<b>승률:</b> %.2f%%",
                stats.getCurrentBalance(),
                profitEmoji, stats.getTotalProfit() >= 0 ? "+" : "", stats.getTotalProfit(), stats.getTotalReturnPercent(),
                stats.getTotalTrades(), stats.getWins(), stats.getLosses(),
                stats.getWinRate()
        );

        String finalMessage = tradeResultText + "\n\n" + "----------------------------------------" + "\n\n" + cumulativeStatsText;
        sendMessage(finalMessage);
    }
    
    public void notifyStatusSummary(List<String> positionSummaries, CumulativeStats stats) {
        if (!enabled) return;
        
        StringBuilder sb = new StringBuilder();
        sb.append("📋 <b>현재 거래 상황 요약</b>\n\n");
        
        if (positionSummaries.isEmpty()) {
            sb.append("현재 보유 중인 포지션이 없습니다.\n");
        } else {
            for (String summary : positionSummaries) {
                sb.append(summary).append("\n");
            }
        }
        
        sb.append("\n----------------------------------------\n\n");
        
        String profitEmoji = stats.getTotalProfit() >= 0 ? "📈" : "📉";
        sb.append(String.format(
                "📊 <b>누적 통계</b>\n" +
                "<b>총 거래:</b> %d건 (승률 %.2f%%)\n" +
                "%s <b>총 손익:</b> %s$%.2f",
                stats.getTotalTrades(), stats.getWinRate(),
                profitEmoji, (stats.getTotalProfit() >= 0 ? "+" : ""), stats.getTotalProfit()
        ));
        
        sendMessage(sb.toString());
    }

    public void notifyError(String errorMessage) {
        if (!enabled) return;
        sendMessage("⚠️ <b>오류 발생!</b>\n" + errorMessage);
    }

    private void sendMessage(String text) {
        if (bot == null) return;
        try {
            bot.execute(new SendMessage(chatId, text).parseMode(com.pengrad.telegrambot.model.request.ParseMode.HTML));
        } catch (Exception e) {
            log.warn("텔레그램 메시지 전송 실패: {}", e.getMessage());
        }
    }

    private String getCurrentTime() {
        return LocalDateTime.now().format(timeFormat);
    }

    public void shutdown() {
        if (bot != null) {
            sendMessage("🛑 <b>트레이딩봇 종료</b>\n" + "⏰ " + getCurrentTime());
            bot.shutdown();
        }
    }
}
