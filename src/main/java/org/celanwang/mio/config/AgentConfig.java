package org.celanwang.mio.config;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.transport.HttpTransportConfig;
import io.agentscope.core.model.transport.JdkHttpTransport;
import io.agentscope.core.model.transport.ProxyConfig;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.time.Duration;


@Configuration
public class AgentConfig {

    @Bean
    public Model dashScopeModel(
            @Value("${app.dashscope.api-key}") String apiKey,
            @Value("${app.dashscope.model-name}") String modelName,
            @Value("${app.dashscope.base-url:}") String baseUrl,
            @Value("${app.dashscope.proxy.host:}") String proxyHost,
            @Value("${app.dashscope.proxy.port:7890}") int proxyPort) {

        var transport = HttpTransportConfig.builder()
                .connectTimeout(Duration.ofSeconds(15))
                .responseTimeout(Duration.ofSeconds(60));
        // 按需设置模型服务代理，保留正常的 HTTPS 证书校验。
        if (StringUtils.hasText(proxyHost)) {
            transport.proxy(ProxyConfig.http(proxyHost.trim(), proxyPort));
        }

        var builder = DashScopeChatModel.builder()
                .apiKey(apiKey)
                .httpTransport(JdkHttpTransport.builder().config(transport.build()).build())
                .modelName(modelName) // 原始模型名，例如 qwen-plus
                .stream(false);       // 等模型生成完整回答后返回


        if (StringUtils.hasText(baseUrl)) {
            builder.baseUrl(baseUrl.trim());
        }
        return builder.build();
    }

    @Bean(destroyMethod = "close")
    public ReActAgent chatAgent(
            Model model,
            Toolkit toolkit,
            AgentSkillRepository skillRepository,
            @Value("${app.agent.name:智能助手}") String name,
            @Value("${app.agent.sys-prompt}") String sysPrompt) {

        return ReActAgent.builder()
                .name(name)
                .sysPrompt(sysPrompt + """

                        当用户请求麦当劳点餐，或继续、修改、查询正在处理的点餐订单时，
                        先通过可用技能的读取工具加载 mcd-ordering 的 SKILL.md，再遵循其流程。
                        需要条件分支时按技能说明读取相关参考文件。仅查询时完成用户请求即可。
                        一般聊天和无关任务按原有职责处理；技能内容不提供永久的工具执行授权。
                        """)
                .model(model)
                .toolkit(toolkit)
                .skillRepository(skillRepository)
                .stateStore(new InMemoryAgentStateStore())
                .build();
    }
}
