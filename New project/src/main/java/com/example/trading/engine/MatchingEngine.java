package com.example.trading.engine;

import com.example.trading.model.*;
import java.util.*;

/** 连续竞价引擎。所有可变状态只在同一把 synchronized 锁内访问。 */
public class MatchingEngine {
    private static final int MAX_QUANTITY = 1000000;
    private static final long MAX_PRICE = 100000000L;
    private final Map<String, Book> books = new LinkedHashMap<>();
    private final Map<String, Wallet> wallets = new LinkedHashMap<>();
    private final Map<String, Long> prices = new LinkedHashMap<>();
    private final Map<Long, Pending> orders = new LinkedHashMap<>();
    private final List<Trade> trades = new ArrayList<>();
    private long nextId = 1;
    private boolean closed;

    private static class Book {
        final PriorityQueue<Pending> bids = new PriorityQueue<>(
            Comparator.comparingLong((Pending o) -> o.price).reversed().thenComparingLong(o -> o.id));
        final PriorityQueue<Pending> asks = new PriorityQueue<>(
            Comparator.comparingLong((Pending o) -> o.price).thenComparingLong(o -> o.id));
    }
    private static class Pending {
        long id, price;
        String owner, symbol, status = "OPEN";
        Side side;
        OrderType type;
        int quantity, remaining;
        Order snapshot() {
            return new Order(id, owner, symbol, side, type, price, quantity, remaining, status);
        }
    }
    private static class Wallet {
        String id;
        long cash, reservedCash, initialEquity, peak, volume;
        double drawdown;
        final Map<String, Integer> holdings = new HashMap<>();
        final Map<String, Integer> reserved = new HashMap<>();
    }

    public MatchingEngine(Map<String, Long> initialPrices) {
        if (initialPrices.isEmpty()) throw new IllegalArgumentException("至少配置一只股票");
        initialPrices.forEach((symbol, price) -> {
            if (symbol == null || !symbol.matches("[A-Z0-9]{1,12}") || price == null || price <= 0 || price > MAX_PRICE)
                throw new IllegalArgumentException("股票代码或初始价格无效");
            prices.put(symbol, price); books.put(symbol, new Book());
        });
    }

    /** 用初始现金购买种子持仓，保证所有账户的起始权益仍等于初始资金。 */
    public synchronized void registerAccount(String id, long initialCash, int sharesPerSymbol) {
        if (closed || !orders.isEmpty()) throw new IllegalStateException("交易开始后不能增加账户");
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,30}") || wallets.containsKey(id))
            throw new IllegalArgumentException("账户名称无效或重复");
        if (initialCash <= 0 || initialCash > 100000000000000L || sharesPerSymbol < 0 || sharesPerSymbol > MAX_QUANTITY)
            throw new IllegalArgumentException("初始资金或股数无效");
        long cost = 0;
        for (long price : prices.values()) cost = Math.addExact(cost, Math.multiplyExact(price, sharesPerSymbol));
        if (cost > initialCash) throw new IllegalArgumentException("初始资金不足以购买种子持仓");
        Wallet w = new Wallet(); w.id = id; w.cash = initialCash - cost;
        w.initialEquity = initialCash; w.peak = initialCash;
        for (String symbol : prices.keySet()) w.holdings.put(symbol, sharesPerSymbol);
        wallets.put(id, w);
    }

    /** 校验、冻结、撮合和结算是一个原子操作。市价买单先计算当时可成交部分的准确成本。 */
    public synchronized Order submitOrder(String agent, String symbol, Side side, OrderType type,
                                          long price, int quantity) {
        if (closed) throw new IllegalStateException("市场已经收盘");
        Wallet owner = wallet(agent);
        Book book = books.get(symbol);
        if (book == null || side == null || type == null) throw new IllegalArgumentException("股票、方向或类型无效");
        if (quantity <= 0 || quantity > MAX_QUANTITY || price < 0 || price > MAX_PRICE || (type == OrderType.LIMIT && price == 0))
            throw new IllegalArgumentException("价格或数量超出范围");
        long reserve = 0;
        PriorityQueue<Pending> opposite = side == Side.BUY ? book.asks : book.bids;
        if (side == Side.BUY) {
            reserve = type == OrderType.LIMIT ? price * quantity : marketCost(opposite, agent, quantity);
            if (owner.cash - owner.reservedCash < reserve) throw new IllegalArgumentException("可用资金不足");
        } else if (owner.holdings.get(symbol) - owner.reserved.getOrDefault(symbol, 0) < quantity) {
            throw new IllegalArgumentException("可用持仓不足");
        }
        Pending order = new Pending(); order.id = nextId++; order.owner = agent; order.symbol = symbol;
        order.side = side; order.type = type; order.price = price; order.quantity = quantity; order.remaining = quantity;
        orders.put(order.id, order);
        if (side == Side.BUY) owner.reservedCash += reserve;
        else owner.reserved.merge(symbol, quantity, Integer::sum);
        // 从优先队列复制排序，跳过自己的订单，防止自成交。
        List<Pending> candidates = sorted(opposite);
        for (Pending resting : candidates) {
            if (order.remaining == 0) break;
            if (type == OrderType.LIMIT && (side == Side.BUY ? price < resting.price : price > resting.price)) break;
            if (resting.owner.equals(agent)) continue;
            int qty = Math.min(order.remaining, resting.remaining);
            Pending buy = side == Side.BUY ? order : resting;
            Pending sell = side == Side.SELL ? order : resting;
            Wallet buyer = wallet(buy.owner), seller = wallet(sell.owner);
            long cost = resting.price * qty;
            buyer.reservedCash -= buy.type == OrderType.LIMIT ? buy.price * qty : cost;
            buyer.cash -= cost; seller.cash += cost;
            buyer.holdings.merge(symbol, qty, Integer::sum);
            seller.holdings.merge(symbol, -qty, Integer::sum);
            seller.reserved.merge(symbol, -qty, Integer::sum);
            buyer.volume += qty; seller.volume += qty;
            order.remaining -= qty; resting.remaining -= qty;
            resting.status = resting.remaining == 0 ? "FILLED" : "PARTIAL";
            if (resting.remaining == 0) opposite.remove(resting);
            trades.add(new Trade(symbol, resting.price, qty, buy.owner, sell.owner, buy.id, sell.id));
            prices.put(symbol, resting.price);
            sampleEquity();
        }
        if (order.remaining == 0) order.status = "FILLED";
        else if (type == OrderType.MARKET) {
            // 市价买单只冻结可成交成本，因此没有剩余冻结现金。
            if (side == Side.SELL) owner.reserved.merge(symbol, -order.remaining, Integer::sum);
            order.status = "CANCELLED";
        } else {
            order.status = order.remaining == quantity ? "OPEN" : "PARTIAL";
            (side == Side.BUY ? book.bids : book.asks).add(order);
        }
        return order.snapshot();
    }

    private long marketCost(PriorityQueue<Pending> queue, String agent, int quantity) {
        long cost = 0;
        for (Pending p : sorted(queue)) {
            if (p.owner.equals(agent)) continue;
            int qty = Math.min(quantity, p.remaining);
            cost += qty * p.price; quantity -= qty;
            if (quantity == 0) break;
        }
        return cost;
    }
    private List<Pending> sorted(PriorityQueue<Pending> queue) {
        List<Pending> list = new ArrayList<>(queue); list.sort(queue.comparator()); return list;
    }
    private Wallet wallet(String id) {
        Wallet w = wallets.get(id);
        if (w == null) throw new IllegalArgumentException("未知账户");
        return w;
    }
    private long equity(Wallet w) {
        long value = w.cash;
        for (String s : prices.keySet()) value += (long) w.holdings.get(s) * prices.get(s);
        return value;
    }
    private void sampleEquity() {
        for (Wallet w : wallets.values()) {
            long value = equity(w); w.peak = Math.max(w.peak, value);
            w.drawdown = Math.max(w.drawdown, (w.peak - value) / (double) w.peak);
        }
    }
    /** 撤单必须属于当前账户，并释放剩余冻结资源。 */
    public synchronized void cancelOrder(String agent, long id) {
        Pending p = orders.get(id);
        if (p == null || !p.owner.equals(agent)) throw new IllegalArgumentException("订单不存在或不属于此账户");
        if (!p.status.equals("OPEN") && !p.status.equals("PARTIAL")) throw new IllegalArgumentException("订单不能撤销");
        Wallet w = wallet(agent); Book b = books.get(p.symbol);
        if (p.side == Side.BUY) { b.bids.remove(p); w.reservedCash -= p.price * p.remaining; }
        else { b.asks.remove(p); w.reserved.merge(p.symbol, -p.remaining, Integer::sum); }
        p.status = "CANCELLED";
    }
    public synchronized void close() {
        if (closed) return;
        closed = true;
        for (Pending p : orders.values())
            if (p.status.equals("OPEN") || p.status.equals("PARTIAL")) cancelOrder(p.owner, p.id);
        sampleEquity();
    }
    public synchronized boolean isClosed() { return closed; }
    public synchronized Account account(String id) {
        Wallet w = wallet(id);
        return new Account(id, w.cash, w.reservedCash, w.initialEquity, equity(w), w.holdings, w.reserved, w.drawdown, w.volume);
    }
    public synchronized List<Account> accounts() {
        List<Account> result = new ArrayList<>();
        for (String id : wallets.keySet()) result.add(account(id));
        result.sort(Comparator.comparingDouble((Account a) -> a.returnRate).reversed());
        return result;
    }
    public synchronized Map<String, Long> prices() { return new LinkedHashMap<>(prices); }
    public synchronized List<Trade> trades() { return new ArrayList<>(trades); }
    public synchronized List<Order> orders() {
        List<Order> result = new ArrayList<>();
        for (Pending p : orders.values()) result.add(p.snapshot());
        return result;
    }
    public synchronized List<Order> depth(String symbol, Side side) {
        Book b = books.get(symbol);
        if (b == null) throw new IllegalArgumentException("未知股票");
        List<Order> result = new ArrayList<>();
        for (Pending p : sorted(side == Side.BUY ? b.bids : b.asks)) result.add(p.snapshot());
        return result;
    }
}
