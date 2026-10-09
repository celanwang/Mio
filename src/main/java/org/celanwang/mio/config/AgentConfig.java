package org.celanwang.mio.config;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.LongTermMemoryMode;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.transport.HttpTransportConfig;
import io.agentscope.core.model.transport.JdkHttpTransport;
import io.agentscope.core.model.transport.ProxyConfig;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.judge.jev.JevClient;
import io.agentscope.extensions.judge.jev.example.JevModelRouterMiddleware;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import org.celanwang.mio.memory.MioLongTermMemory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.util.StringUtils;

import java.time.Duration;


@Configuration
public class AgentConfig {

    @Bean
    @Primary
    public Model dashScopeModel(
            @Value("${app.dashscope.api-key}") String apiKey,
            @Value("${app.dashscope.model-name}") String modelName,
            @Value("${app.dashscope.base-url:}") String baseUrl,
            @Value("${app.dashscope.proxy.host:}") String proxyHost,
            @Value("${app.dashscope.proxy.port:7890}") int proxyPort) {
        return buildModel(apiKey, modelName, baseUrl, proxyHost, proxyPort);
    }

    // 轻量模型独立成 Bean，供 Jev 路由中间件和画像提炼器共用。
    @Bean
    public Model dashScopeFastModel(
            @Value("${app.dashscope.api-key}") String apiKey,
            @Value("${app.dashscope.fast-model-name:qwen-flash}") String fastModelName,
            @Value("${app.dashscope.base-url:}") String baseUrl,
            @Value("${app.dashscope.proxy.host:}") String proxyHost,
            @Value("${app.dashscope.proxy.port:7890}") int proxyPort) {
        return buildModel(apiKey, fastModelName.trim(), baseUrl, proxyHost, proxyPort);
    }

    private Model buildModel(String apiKey, String modelName, String baseUrl,
                             String proxyHost, int proxyPort) {
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
            @Qualifier("dashScopeFastModel") Model fastModel,
            MioLongTermMemory longTermMemory,
            Toolkit toolkit,
            AgentSkillRepository skillRepository,
            ObjectProvider<JevClient> jevClient,
            @Value("${app.agent.name:智能助手}") String name,
            @Value("${app.agent.sys-prompt}") String sysPrompt) {

        var builder = ReActAgent.builder()
                .name(name)
                .sysPrompt(sysPrompt + """

                        你可以自主规划和执行任务：已知信息直接使用，不要按固定流程逐步向用户确认；
                        只读查询（菜单、价格、门店、订单等）直接执行，无需征求同意。
                        涉及麦当劳点餐时可参考 mcd-ordering 技能了解工具用法与业务边界，
                        技能是参考而非必须遵循的流程；一般聊天和无关任务按原有职责处理。
                        """)
                .model(model)
                .toolkit(toolkit)
                .skillRepository(skillRepository)
                .stateStore(new InMemoryAgentStateStore())
                .permissionContext(permissionContext())
                // 长期记忆：STATIC_CONTROL 模式下框架自动注入画像并异步回调 record。
                .longTermMemory(longTermMemory)
                .longTermMemoryMode(LongTermMemoryMode.STATIC_CONTROL)
                .longTermMemoryAsyncRecord(true);

        // Jev 可用时按请求复杂度路由模型：复杂任务用主模型，简单请求用轻量模型。
        JevClient jev = jevClient.getIfAvailable();
        if (jev != null && !modelName(fastModel).equals(modelName(model))) {
            builder.middleware(JevModelRouterMiddleware.builder(jev)
                    .choice("complex", model, "需要多步规划、工具编排、点餐下单、计算或复杂推理的请求")
                    .choice("simple", fastModel, "简单问答、闲聊或一步就能完成的查询")
                    .confidenceThreshold(0.7)
                    .failOpen(true)
                    .build());
        }
        return builder.build();
    }

    private String modelName(Model model) {
        return model instanceof DashScopeChatModel dashScope ? dashScope.getModelName() : "";
    }

    // 只读工具注册 allow 规则自动放行；写操作不设规则，回落到默认的审核流程。
    private PermissionContextState permissionContext() {
        var builder = PermissionContextState.builder().mode(PermissionMode.DEFAULT);
        for (String tool : ToolPolicies.READ_ONLY) {
            builder.addAllowRule(tool, new PermissionRule(tool, "", PermissionBehavior.ALLOW, "mio"));
        }
        return builder.build();
    }
}
