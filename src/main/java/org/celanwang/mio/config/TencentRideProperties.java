package org.celanwang.mio.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;

@Getter
@Setter
@Component
@ConfigurationProperties("app.tencent-ride")
public class TencentRideProperties {
    private boolean enabled = Boolean.parseBoolean(System.getenv().getOrDefault("TENCENT_RIDE_ENABLED", "true"));
    private String python = System.getenv().getOrDefault("TENCENT_RIDE_PYTHON", "python");
    private String token = System.getenv().getOrDefault("TENCENT_RIDE_TOKEN", "");
    private String residentCity = System.getenv().getOrDefault("TENCENT_RIDE_CITY", "");
    private Path dataDirectory = Path.of(".tencent-ride");
    private Duration timeout = Duration.ofSeconds(65);
}
