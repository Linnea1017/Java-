package com.example.trading.app;
import com.example.trading.agent.Simulation;
import com.example.trading.model.*;
import java.nio.file.Path;
/** 可重复的控制台演示，不调用外部 API。 */
public class Main {
    public static void main(String[] args) throws Exception {
        Config config = new Config();
        try (Simulation sim = new Simulation(config)) {
            if (sim.humans.size() < 2) throw new IllegalArgumentException("控制台演示需要至少两个人工账户");
            String symbol = sim.engine.prices().keySet().iterator().next();
            long price = sim.engine.prices().get(symbol);
            sim.engine.submitOrder("Human-2", symbol, Side.SELL, OrderType.LIMIT, price, 10);
            sim.engine.submitOrder("Human-1", symbol, Side.BUY, OrderType.LIMIT, price+100, 6);
            sim.engine.submitOrder("Human-1", symbol, Side.BUY, OrderType.MARKET, 0, 8);
            sim.close(); CsvExporter.write(sim.engine, Path.of("output"));
            for (Trade t : sim.engine.trades()) System.out.println("成交 " + t.symbol + " " + t.quantity + " 股 @ " + Config.money(t.price));
            for (Account a : sim.engine.accounts()) System.out.printf("%s 权益 %s 收益率 %.2f%%%n", a.id, Config.money(a.equity), a.returnRate*100);
            System.out.println("已导出 output/orders.csv、trades.csv、ranking.csv");
        }
    }
}
