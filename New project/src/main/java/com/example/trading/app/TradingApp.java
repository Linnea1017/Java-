package com.example.trading.app;

import com.example.trading.agent.Simulation;
import com.example.trading.model.*;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.chart.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/** 简单 JavaFX 窗口：人工下单、五档深度、价格曲线、账户和历史。 */
public class TradingApp extends Application {
    private Simulation simulation;
    private final ScheduledExecutorService refresh = Executors.newSingleThreadScheduledExecutor();
    private final ComboBox<String> agent = new ComboBox<>(), symbol = new ComboBox<>();
    private final ComboBox<Side> side = new ComboBox<>();
    private final ComboBox<OrderType> type = new ComboBox<>();
    private final TextField price = new TextField("175.50"), quantity = new TextField("5"), cancelId = new TextField();
    private final Label account = new Label(), status = new Label("准备就绪"), pnl = new Label();
    private final TextArea depth = new TextArea(), history = new TextArea(), ranking = new TextArea();
    private final LineChart<Number, Number> chart = new LineChart<>(new NumberAxis(), new NumberAxis());
    private final BarChart<String, Number> depthChart = new BarChart<>(new CategoryAxis(), new NumberAxis());
    private final XYChart.Series<Number, Number> series = new XYChart.Series<>();
    private int sample;
    private boolean exported;
    @Override public void start(Stage stage) throws Exception {
        simulation = new Simulation(new Config());
        agent.getItems().addAll(simulation.humans); agent.getSelectionModel().selectFirst();
        symbol.getItems().addAll(simulation.engine.prices().keySet()); symbol.getSelectionModel().selectFirst();
        side.getItems().addAll(Side.values()); side.setValue(Side.BUY);
        type.getItems().addAll(OrderType.values()); type.setValue(OrderType.LIMIT);
        price.setPrefColumnCount(7); quantity.setPrefColumnCount(4); cancelId.setPrefColumnCount(5);
        cancelId.setPromptText("订单ID");
        price.setText(Config.money(simulation.engine.prices().get(symbol.getValue())));
        type.setOnAction(e -> price.setDisable(type.getValue() == OrderType.MARKET));
        symbol.setOnAction(e -> {
            series.getData().clear(); sample=0;
            price.setText(Config.money(simulation.engine.prices().get(symbol.getValue()))); update();
        });
        agent.setOnAction(e -> update());
        Button submit = new Button("提交订单"), cancel = new Button("撤单"), start = new Button("启动自动交易"), close = new Button("收盘并导出");
        submit.setOnAction(e -> perform(() -> {
            Order o = simulation.engine.submitOrder(agent.getValue(), symbol.getValue(), side.getValue(), type.getValue(),
                type.getValue() == OrderType.MARKET ? 0 : Config.cents(price.getText()), Integer.parseInt(quantity.getText()));
            status.setText("订单 #"+o.id+" "+o.status+"，剩余 "+o.remaining);
        }));
        cancel.setOnAction(e -> perform(() -> { simulation.engine.cancelOrder(agent.getValue(), Long.parseLong(cancelId.getText())); status.setText("撤单成功"); }));
        start.setOnAction(e -> { simulation.start(); start.setDisable(true); status.setText("自动交易运行中"); });
        close.setOnAction(e -> perform(() -> { simulation.close(); export(); }));
        FlowPane input = new FlowPane(8, 8, new Label("账户"), agent, new Label("股票"), symbol, side, type,
            new Label("价格"), price, new Label("数量"), quantity, submit, cancelId, cancel, start, close);
        chart.setTitle("成交价格趋势（每秒采样）"); chart.setAnimated(false); chart.setCreateSymbols(false);
        chart.setLegendVisible(false); chart.getData().add(series);
        ((NumberAxis)chart.getYAxis()).setForceZeroInRange(false);
        chart.getXAxis().setLabel("采样序号"); chart.getYAxis().setLabel("价格");
        depthChart.setTitle("五档委托数量"); depthChart.setAnimated(false);
        for (TextArea area : Arrays.asList(depth, history, ranking)) { area.setEditable(false); area.setStyle("-fx-font-family: monospace;"); }
        depth.setPrefRowCount(10); history.setPrefRowCount(9); ranking.setPrefRowCount(6);
        SplitPane charts = new SplitPane(chart, depthChart); charts.setPrefHeight(270);
        TabPane tabs = new TabPane(new Tab("订单簿", depth), new Tab("当前账户订单历史", history), new Tab("账户排名", ranking));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        VBox root = new VBox(10, new Label("连续竞价股票交易模拟器 · Java 课程项目"), input, account, pnl, charts, tabs, status);
        root.setPadding(new Insets(16)); VBox.setVgrow(tabs, Priority.ALWAYS);
        stage.setScene(new Scene(root, 1150, 760)); stage.setTitle("股票连续竞价模拟器"); stage.show();
        update();
        // UI 控件只能在 JavaFX 应用线程修改。
        refresh.scheduleWithFixedDelay(() -> Platform.runLater(this::update), 1, 1, TimeUnit.SECONDS);
    }
    private void perform(Runnable action) {
        try { action.run(); } catch (RuntimeException ex) { status.setText("操作失败："+ex.getMessage()); }
        update();
    }
    private void update() {
        if (simulation == null || symbol.getValue() == null) return;
        String stock = symbol.getValue(); Account a = simulation.engine.account(agent.getValue());
        account.setText("现金："+Config.money(a.cash)+"  可用资金："+Config.money(a.availableCash())
            +"  "+stock+" 持仓："+a.holdings.get(stock)+"  可用："+a.availableShares(stock)+"  总权益："+Config.money(a.equity));
        pnl.setText(String.format(Locale.ROOT,"浮动盈亏：%s  收益率：%.2f%%  最大回撤：%.2f%%",Config.money(a.equity-a.initialEquity),a.returnRate*100,a.maxDrawdown*100));
        pnl.setStyle(a.returnRate >= 0 ? "-fx-text-fill: #157347;" : "-fx-text-fill: #bb2d3b;");
        StringBuilder book = new StringBuilder("方向      价格      挂单数量\n");
        depthChart.getData().clear();
        for (Side direction : Side.values()) {
            Map<Long,Integer> levels = new LinkedHashMap<>();
            for (Order o : simulation.engine.depth(stock, direction)) levels.merge(o.price, o.remaining, Integer::sum);
            XYChart.Series<String,Number> data = new XYChart.Series<>(); data.setName(direction.toString());
            int i=0;
            for (Map.Entry<Long,Integer> entry : levels.entrySet()) {
                if (i++ == 5) break;
                book.append(direction).append("       ").append(Config.money(entry.getKey())).append("       ").append(entry.getValue()).append('\n');
                data.getData().add(new XYChart.Data<>(Config.money(entry.getKey()),entry.getValue()));
            }
            depthChart.getData().add(data);
        }
        depth.setText(book.toString());
        series.getData().add(new XYChart.Data<>(sample++, simulation.engine.prices().get(stock)/100.0));
        if (series.getData().size()>300) series.getData().remove(0);
        StringBuilder orders = new StringBuilder("ID   股票   方向  类型  价格  数量/剩余  状态\n");
        for (Order o : simulation.engine.orders()) if (o.agentId.equals(a.id))
            orders.append(o.id).append("  ").append(o.symbol).append("  ").append(o.side).append("  ").append(o.type).append("  ")
                .append(Config.money(o.price)).append("  ").append(o.quantity).append('/').append(o.remaining).append("  ").append(o.status).append('\n');
        history.setText(orders.toString());
        StringBuilder ranks = new StringBuilder("账户          总权益       收益率       成交股数\n");
        for (Account x : simulation.engine.accounts()) ranks.append(String.format(Locale.ROOT,"%-12s %12s %9.2f%% %8d%n",x.id,Config.money(x.equity),x.returnRate*100,x.volume));
        ranking.setText(ranks.toString());
        if (simulation.engine.isClosed() && !exported) export();
    }
    private void export() {
        try { CsvExporter.write(simulation.engine, Path.of("output")); exported=true; status.setText("已收盘；订单、成交、排名已保存至 output 文件夹"); }
        catch (Exception ex) { status.setText("导出失败："+ex.getMessage()); }
    }
    @Override public void stop() { refresh.shutdownNow(); if (simulation!=null) { simulation.close(); if (!exported) export(); } }
    public static void main(String[] args) { launch(args); }
}
