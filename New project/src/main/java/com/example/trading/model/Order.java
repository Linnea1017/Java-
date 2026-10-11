package com.example.trading.model;
/** 只读订单快照。价格使用分，避免浮点误差；序号在引擎收到订单时生成。 */
public final class Order {
    public final long id, price;
    public final String agentId, symbol;
    public final Side side;
    public final OrderType type;
    public final int quantity, remaining;
    public final String status;
    public Order(long id, String agentId, String symbol, Side side, OrderType type,
                 long price, int quantity, int remaining, String status) {
        this.id = id; this.agentId = agentId; this.symbol = symbol;
        this.side = side; this.type = type; this.price = price;
        this.quantity = quantity; this.remaining = remaining; this.status = status;
    }
}
