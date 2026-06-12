package main.account;

/**
 * Live implementation updated by the balance monitor thread in BitgetTradingBot.
 */
public class LiveAccountBalanceProvider implements AccountBalanceProvider {

    private volatile double totalEquity;
    private volatile double availableBalance;

    public void update(double totalEquity, double availableBalance) {
        this.totalEquity = totalEquity;
        this.availableBalance = availableBalance;
    }

    @Override
    public double getTotalEquity() {
        return totalEquity;
    }

    @Override
    public double getAvailableBalance() {
        return availableBalance;
    }
}
