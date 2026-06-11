package main.job;

import main.model.OptimizationProposal;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Telegram report message builder for backtest results. */
class ReportFormatter {

    private static final String[] RANK_ICONS = {"1️⃣", "2️⃣", "3️⃣", "4️⃣", "5️⃣"};
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MM-dd");

    private ReportFormatter() {}

    static String buildBacktestReport(String symbol, List<OptimizationProposal> top5) {
        LocalDate today = LocalDate.now();
        LocalDate from  = today.minusDays(30);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(
            "📊 <b>%s 백테스트 리포트</b> (15m, 10x)\n기간: %s ~ %s\n\n",
            symbol, from.format(DATE_FMT), today.format(DATE_FMT)
        ));

        if (top5.isEmpty()) {
            sb.append("⚠️ 수익 플러스 조합을 찾지 못했습니다.");
            return sb.toString();
        }

        for (int i = 0; i < top5.size(); i++) {
            OptimizationProposal p = top5.get(i);
            sb.append(String.format(
                "%s rsiOS=%d/rsiOB=%d SL=%.1f TP=%.1f BBW=%s → %+.0f%%, WR %.0f%%, MDD %.0f%%\n",
                RANK_ICONS[i], p.rsiOS(), p.rsiOB(), p.slMult(), p.tpMult(),
                p.bbWidthMult() == 0.0 ? "OFF" : String.format("%.1f×", p.bbWidthMult()),
                p.returnPct(), p.winRate(), p.mdd()
            ));
        }
        return sb.toString();
    }
}