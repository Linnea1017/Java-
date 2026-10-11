package com.example.trading.app;
import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
/** 先读取默认配置，再用项目目录的 config.properties 覆盖。 */
public class Config {
    private final Properties values = new Properties();
    public Config() throws IOException {
        try (InputStream in = Config.class.getResourceAsStream("/config.properties")) {
            if (in == null) throw new IOException("默认配置缺失");
            values.load(in);
        }
        if (Files.exists(Path.of("config.properties"))) {
            try (InputStream in = Files.newInputStream(Path.of("config.properties"))) { values.load(in); }
        }
    }
    public String text(String key) { return values.getProperty(key).trim(); }
    public int integer(String key) { return Integer.parseInt(text(key)); }
    public double number(String key) {
        double v = Double.parseDouble(text(key));
        if (!Double.isFinite(v)) throw new IllegalArgumentException(key + " 必须是有限数");
        return v;
    }
    public boolean enabled(String key) { return Boolean.parseBoolean(text(key)); }
    public Map<String, Long> prices() {
        Map<String, Long> result = new LinkedHashMap<>();
        for (String symbol : text("stocks").split(",")) result.put(symbol.trim(), cents(text(symbol.trim())));
        return result;
    }
    public static long cents(String dollars) { return new BigDecimal(dollars.trim()).movePointRight(2).longValueExact(); }
    public static String money(long cents) { return BigDecimal.valueOf(cents, 2).toPlainString(); }
}
