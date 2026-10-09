package org.celanwang.mio.config;

import io.agentscope.extensions.judge.jev.JevClient;
import io.agentscope.extensions.judge.jev.JevRetryPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * Jev 决策审核客户端配置：写操作执行前由 Jev 判断，通过则自动执行，不通过转人工确认。
 * 未配置 key 时不创建客户端，所有写操作回落到人工确认。
 */
@Configuration
public class JevConfig {

    private static final Logger log = LoggerFactory.getLogger(JevConfig.class);

    @Bean
    @ConditionalOnProperty(prefix = "app.jev", name = "enabled", havingValue = "true", matchIfMissing = true)
    public JevClient jevClient(Environment env) {
        String apiKey = env.getProperty("app.jev.api-key", "");
        if (!StringUtils.hasText(apiKey)) {
            log.info("未配置 TYPESAFE_API_KEY，Jev 决策审核不可用，写操作将转人工确认。");
            return null;
        }
        return JevClient.builder()
                .apiKey(apiKey.trim())
                .model(env.getProperty("app.jev.model", "jev-latest"))
                .timeout(Duration.ofSeconds(10))
                .retryPolicy(new JevRetryPolicy(2, Duration.ofMillis(500)))
                .build();
    }
}
