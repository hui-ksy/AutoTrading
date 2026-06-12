package main.util;

/**
 * Centralized price formatting utility.
 * Replaces duplicate formatPrice()/fmt() methods across BitgetTradingBot,
 * TelegramNotifier, and BollingerBandReversionStrategy.
 */
public final class PriceFormatter {

    private PriceFormatter() {}

    public static String format(double price) {
        if (price == 0) return "0.00";
        double abs = Math.abs(price);
        if (abs < 0.0001) return String.format("%.10f", price);
        if (abs < 0.01)   return String.format("%.7f", price);
        if (abs < 1.0)    return String.format("%.4f", price);
        return String.format("%,.4f", price);
    }
}
