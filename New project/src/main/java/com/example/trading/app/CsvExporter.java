package com.example.trading.app;
import com.example.trading.engine.MatchingEngine;
import com.example.trading.model.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
/** 将收盘后的订单、成交和排名保存为 UTF-8 CSV。 */
public final class CsvExporter {
    private CsvExporter() { }
    public static void write(MatchingEngine engine, Path dir) throws IOException {
        if (!engine.isClosed()) throw new IllegalStateException("请先收盘再导出，确保三个文件状态一致");
        Files.createDirectories(dir);
        List<String> lines = new ArrayList<>();
        lines.add("id,agent,symbol,side,type,price,quantity,remaining,status");
        for (Order o : engine.orders()) lines.add(o.id+","+o.agentId+","+o.symbol+","+o.side+","+o.type+","+Config.money(o.price)+","+o.quantity+","+o.remaining+","+o.status);
        Files.write(dir.resolve("orders.csv"), lines, StandardCharsets.UTF_8);
        lines.clear(); lines.add("time,symbol,price,quantity,buyer,seller,buy_order,sell_order");
        for (Trade t : engine.trades()) lines.add(t.time+","+t.symbol+","+Config.money(t.price)+","+t.quantity+","+t.buyerId+","+t.sellerId+","+t.buyOrderId+","+t.sellOrderId);
        Files.write(dir.resolve("trades.csv"), lines, StandardCharsets.UTF_8);
        lines.clear(); lines.add("rank,agent,initial_equity,cash,equity,return_rate,max_drawdown,trade_volume");
        int rank=1;
        for (Account a : engine.accounts()) lines.add(rank+++","+a.id+","+Config.money(a.initialEquity)+","+Config.money(a.cash)+","+Config.money(a.equity)+","+a.returnRate+","+a.maxDrawdown+","+a.volume);
        Files.write(dir.resolve("ranking.csv"), lines, StandardCharsets.UTF_8);
    }
}
