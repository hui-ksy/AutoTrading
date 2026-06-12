package main.account;

/**
 * Abstracts the source of live account balance data.
 * Decouples AutoTrader from BitgetTradingBot static fields.
 */
public interface AccountBalanceProvider {

    double getTotalEquity();

    double getAvailableBalance();
}
