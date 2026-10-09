package org.celanwang.mio.config;

import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * 麦当劳 MCP 接入配置：创建客户端，并将远程工具注册到智能体工具集中。
 */
@Configuration
public class McpConfig {

    private static final Logger log = LoggerFactory.getLogger(McpConfig.class);

    // 仅在配置项 app.mcp.mcd.enabled 为 true 时启用；token 缺失或连接失败时降级为不连接，
    // 不影响普通聊天。应用关闭时释放连接。
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "app.mcp.mcd", name = "enabled", havingValue = "true")
    public McpClientWrapper mcdMcpClient(
            @Value("${app.mcp.mcd.url:https://mcp.mcd.cn}") String url,
            @Value("${app.mcp.mcd.token:}") String token) {
        if (!StringUtils.hasText(token)) {
            log.warn("已启用麦当劳 MCP，但 MCD_MCP_TOKEN 为空，跳过麦当劳连接（点餐工具不可用）。");
            return null;
        }

        // 使用流式 HTTP 协议连接，通过请求头携带麦当劳平台申请的令牌。
        try {
            return McpClientBuilder.create("mcd-mcp")
                    .streamableHttpTransport(url)
                    .header("Authorization", "Bearer " + token.trim())
                    .timeout(Duration.ofSeconds(30))
                    .initializationTimeout(Duration.ofSeconds(30))
                    .buildAsync()
                    .block(Duration.ofSeconds(45));
        } catch (Exception e) {
            log.warn("连接麦当劳 MCP 失败，跳过麦当劳连接（点餐工具不可用）：{}", e.getMessage());
            return null;
        }
    }

    @Bean
    public Toolkit toolkit(ObjectProvider<McpClientWrapper> mcdMcpClient) {
        Toolkit toolkit = new Toolkit();
        McpClientWrapper client = mcdMcpClient.getIfAvailable();
        if (client != null) {
            // 自动发现并注册远程工具，智能体可根据用户的中文请求选择工具。
            toolkit.registerMcpClient(client).block(Duration.ofSeconds(45));
        }
        return toolkit;
    }
}
