package org.celanwang.mio.config;

import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 启动时从项目根目录的 .env 读取 KEY=VALUE 配置，优先级最低：
 * 已存在的系统环境变量和 JVM 系统属性会覆盖 .env 中的值。
 */
public final class DotenvSupport {

    private DotenvSupport() {
    }

    public static void loadInto(ConfigurableEnvironment environment) {
        Path dotenv = Path.of(".env");
        if (!Files.isRegularFile(dotenv)) {
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(dotenv)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int eq = trimmed.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = trimmed.substring(0, eq).trim();
                String value = trimmed.substring(eq + 1).trim();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                if (!key.isEmpty()) {
                    values.put(key, value);
                }
            }
        } catch (IOException e) {
            return;
        }
        if (!values.isEmpty()) {
            environment.getPropertySources().addLast(new MapPropertySource("dotenv", values));
        }
    }
}
