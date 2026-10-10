package com.example.trading.model;
import java.time.Instant;
/** 不可变成交记录，兼容 Java 11，不使用 record 语法。 */
public final class Trade {
    public final String symbol, buyerId, sellerId;
    public final long price, buyOrderId, sellOrderId;
    public final int quantity;
    public final Instant time;
    public Trade(String symbol, long price, int quantity, String buyerId, String sellerId,
                 long buyOrderId, long sellOrderId) {
        this.symbol = symbol; this.price = price; this.quantity = quantity;
        this.buyerId = buyerId; this.sellerId = sellerId;
        this.buyOrderId = buyOrderId; this.sellOrderId = sellOrderId;
        this.time = Instant.now();
    }
}
