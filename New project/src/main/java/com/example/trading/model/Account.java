package com.example.trading.model;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
/** 账户只读快照。现金包含冻结现金，持仓包含冻结持仓。 */
public final class Account {
    public final String id;
    public final long cash, reservedCash, initialEquity, equity;
    public final Map<String, Integer> holdings, reservedShares;
    public final double returnRate, maxDrawdown;
    public final long volume;
    public Account(String id, long cash, long reservedCash, long initialEquity, long equity,
                   Map<String, Integer> holdings, Map<String, Integer> reservedShares,
                   double maxDrawdown, long volume) {
        this.id = id; this.cash = cash; this.reservedCash = reservedCash;
        this.initialEquity = initialEquity; this.equity = equity;
        this.holdings = Collections.unmodifiableMap(new HashMap<>(holdings));
        this.reservedShares = Collections.unmodifiableMap(new HashMap<>(reservedShares));
        this.returnRate = (equity - initialEquity) / (double) initialEquity;
        this.maxDrawdown = maxDrawdown; this.volume = volume;
    }
    public long availableCash() { return cash - reservedCash; }
    public int availableShares(String symbol) {
        return holdings.getOrDefault(symbol, 0) - reservedShares.getOrDefault(symbol, 0);
    }
}
