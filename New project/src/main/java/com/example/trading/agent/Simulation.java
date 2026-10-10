package com.example.trading.agent;
import com.example.trading.app.Config;
import com.example.trading.engine.MatchingEngine;
import com.example.trading.model.*;
import java.util.*;
import java.util.concurrent.*;

/** 定时器和三个独立代理线程。网络请求在代理线程执行。 */
public class Simulation implements AutoCloseable {
    public final MatchingEngine engine;
    public final List<String> humans = new ArrayList<>();
    private final List<String> bots = new ArrayList<>();
    private final Config config;
    private final ScheduledExecutorService workers;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final LlmStrategy llm;
    private boolean started;
    private final Map<String, Long> initialPrices;
    public Simulation(Config config) {
        this.config = config; initialPrices = config.prices(); engine = new MatchingEngine(initialPrices);
        int humanCount = config.integer("agent.human.count"), aiCount = config.integer("agent.ai.count");
        if (humanCount < 1 || humanCount > 20 || aiCount < 0 || aiCount > 20
            || config.number("simulation.speed") <= 0 || config.number("simulation.speed") > 100
            || config.integer("simulation.duration") <= 0 || config.integer("agent.ai.decision.interval") <= 0)
            throw new IllegalArgumentException("人数或模拟时间配置无效");
        for (int i=1; i<=humanCount; i++) {
            String id = "Human-"+i; humans.add(id);
            engine.registerAccount(id, Config.cents(config.text("agent.human.initial.balance")), config.integer("agent.initial.shares"));
        }
        for (int i=1; i<=aiCount; i++) {
            String id = "AI-"+i; bots.add(id);
            engine.registerAccount(id, Config.cents(config.text("agent.ai.initial.balance")), config.integer("agent.initial.shares"));
        }
        workers = Executors.newScheduledThreadPool(Math.max(1, aiCount)); llm = new LlmStrategy(config);
    }
    public synchronized void start() {
        if (started || engine.isClosed()) return;
        started = true;
        long interval = Math.max(100, (long)(config.integer("agent.ai.decision.interval") * 1000 / config.number("simulation.speed")));
        for (int i=0; i<bots.size(); i++) {
            final int index = i; final String id = bots.get(i); final Random random = new Random(2026+i);
            workers.scheduleWithFixedDelay(() -> tick(id, index, random), i*100L, interval, TimeUnit.MILLISECONDS);
        }
        long duration = Math.max(1, (long)(config.integer("simulation.duration") * 1000L / config.number("simulation.speed")));
        timer.schedule(this::close, duration, TimeUnit.MILLISECONDS);
    }
    private void tick(String id, int index, Random random) {
        if (engine.isClosed()) return;
        try {
            for (Order o : engine.orders())
                if (o.agentId.equals(id) && (o.status.equals("OPEN") || o.status.equals("PARTIAL"))) engine.cancelOrder(id, o.id);
            List<String> symbols = new ArrayList<>(engine.prices().keySet());
            String symbol = symbols.get(random.nextInt(symbols.size()));
            long last = engine.prices().get(symbol); Account account = engine.account(id);
            Side side; long price; int quantity;
            if (index == 0 && config.enabled("llm.enabled")) {
                long volume = 0;
                for (Trade t : engine.trades()) if (t.symbol.equals(symbol)) volume += t.quantity;
                LlmStrategy.Decision decision = llm.decide(symbol, last, initialPrices.get(symbol), volume, account,
                    engine.depth(symbol, Side.BUY), engine.depth(symbol, Side.SELL));
                if (decision == null) return;
                side = decision.side; price = decision.price; quantity = decision.quantity;
            } else {
                // 简单随机限价策略：围绕最新价上下 1%，方便观察价格发现。
                side = random.nextBoolean() ? Side.BUY : Side.SELL;
                price = Math.max(1, Math.round(last * (0.99 + random.nextDouble()*0.02)));
                quantity = 1 + random.nextInt(5);
            }
            engine.submitOrder(id, symbol, side, OrderType.LIMIT, price, quantity);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            // 资金不足、没有持仓或已收盘：此轮不交易。
        }
    }
    public synchronized void close() { engine.close(); workers.shutdownNow(); timer.shutdownNow(); }
}
