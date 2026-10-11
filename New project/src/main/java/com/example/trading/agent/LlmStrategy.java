package com.example.trading.agent;
import com.example.trading.app.Config;
import com.example.trading.model.Account;
import com.example.trading.model.Order;
import com.example.trading.model.Side;
import com.google.gson.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.List;

/** 可选的 OpenAI-compatible HTTP 接口。每次失败都返回 HOLD，不阻塞撮合锁。 */
public class LlmStrategy {
    public static final class Decision {
        public final Side side; public final long price; public final int quantity;
        Decision(Side side, long price, int quantity) { this.side = side; this.price = price; this.quantity = quantity; }
    }
    private final Config config;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private long lastRequest;
    public LlmStrategy(Config config) { this.config = config; }
    public synchronized Decision decide(String symbol, long last, long initial, long volume, Account account,
                                         List<Order> bids, List<Order> asks) {
        String key = System.getenv("LLM_API_KEY");
        if (key == null || key.isBlank()) return null;
        long now = System.nanoTime();
        long interval = Math.max(1000, config.integer("llm.rate.limit.ms")) * 1000000L;
        if (lastRequest != 0 && now - lastRequest < interval) return null;
        lastRequest = now;
        try {
            URI uri = URI.create(config.text("llm.endpoint"));
            if (!"https".equalsIgnoreCase(uri.getScheme())) return null;
            String prompt = "You trade in a classroom simulation. Symbol=" + symbol
                + "; last=" + Config.money(last) + "; changePercent=" + ((last-initial)*100.0/initial)
                + "; volume=" + volume + "; availableCash=" + Config.money(account.availableCash())
                + "; availableShares=" + account.availableShares(symbol) + "; pnl=" + Config.money(account.equity-account.initialEquity)
                + "; bids=" + depth(bids) + "; asks=" + depth(asks)
                + ". Reply ONLY JSON: {\"action\":\"BUY|SELL|HOLD\",\"price\":number,\"quantity\":integer}. Quantity 1..10.";
            JsonObject message = new JsonObject(); message.addProperty("role", "user"); message.addProperty("content", prompt);
            JsonArray messages = new JsonArray(); messages.add(message);
            JsonObject body = new JsonObject(); body.addProperty("model", config.text("llm.model")); body.add("messages", messages);
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8))
                .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return null;
            String content = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("choices")
                .get(0).getAsJsonObject().getAsJsonObject("message").get("content").getAsString();
            return parse(content, last, config.number("llm.price.bound.percent"));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt(); return null;
        } catch (Exception ex) { return null; }
    }
    private String depth(List<Order> orders) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < Math.min(5, orders.size()); i++) {
            Order o = orders.get(i); result.append(Config.money(o.price)).append(':').append(o.remaining).append(' ');
        }
        return result.toString();
    }
    /** 严格校验动作、数值类型、整数数量和相对最新价的边界。null 表示 HOLD。 */
    public static Decision parse(String content, long last, double bound) {
        try {
            JsonObject obj = JsonParser.parseString(content).getAsJsonObject();
            if (!obj.has("action") || !obj.get("action").isJsonPrimitive() || !obj.getAsJsonPrimitive("action").isString()) return null;
            String action = obj.get("action").getAsString();
            if (action.equals("HOLD")) return null;
            if (!action.equals("BUY") && !action.equals("SELL")) return null;
            if (!obj.get("price").isJsonPrimitive() || !obj.getAsJsonPrimitive("price").isNumber()
                || !obj.get("quantity").isJsonPrimitive() || !obj.getAsJsonPrimitive("quantity").isNumber()) return null;
            long price = Config.cents(obj.get("price").getAsString());
            int quantity = obj.get("quantity").getAsBigDecimal().intValueExact();
            if (last <= 0 || !Double.isFinite(bound) || bound < 0 || price <= 0 || quantity < 1 || quantity > 10
                || Math.abs(price-last) > last * bound / 100.0) return null;
            return new Decision(Side.valueOf(action), price, quantity);
        } catch (RuntimeException ex) { return null; }
    }
}
