package org.celanwang.mio.service;

import org.celanwang.mio.orchestration.CapabilityAvailability;

import org.celanwang.mio.config.WeiboProperties;
import org.celanwang.mio.model.vo.WeiboAction;
import org.celanwang.mio.security.ToolRegistrationGuard;
import org.celanwang.mio.tool.WeiboConnectionTool;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpAsyncClientWrapper;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class WeiboMcpService {
    private static final Logger log = LoggerFactory.getLogger(WeiboMcpService.class);
    private final WeiboProperties properties;
    private final WeiboOAuthService oauth;
    private final Toolkit toolkit;
    private final ToolRegistrationGuard registrationGuard;
    private volatile McpClientWrapper client;
    private int toolCount;
    private String error;
    private volatile java.util.List<io.agentscope.core.tool.ToolBase> businessTools = java.util.List.of();
    private final Map<String, WeiboAction> conversationActions = new ConcurrentHashMap<>();

    @Autowired
    public WeiboMcpService(WeiboProperties properties, WeiboOAuthService oauth, ReActAgent agent,
                           ToolRegistrationGuard registrationGuard) {
        // AgentScope 构建 Agent 时会复制 Toolkit；授权后的工具必须注册到实际运行的副本。
        this(properties, oauth, agent.getToolkit(), registrationGuard);
    }

    WeiboMcpService(WeiboProperties properties, WeiboOAuthService oauth, Toolkit toolkit,
                    ToolRegistrationGuard registrationGuard) {
        this.properties = properties;
        this.oauth = oauth;
        this.toolkit = toolkit;
        this.registrationGuard = registrationGuard;
        toolkit.removeTool(WeiboConnectionTool.NAME);
        toolkit.registerAgentTool(new WeiboConnectionTool(this));
    }

    public synchronized String prepareForConversation(String sessionId) {
        conversationActions.remove(sessionId);
        if (!properties.isEnabled()) return "disabled：微博服务未启用，请如实说明，不能生成授权入口或编造结果。";
        if (!oauth.isAuthorized()) return requireAuthorization(sessionId);
        try {
            oauth.accessToken();
            if (client == null) connect();
            return "ready：微博已授权并已连接。请继续用户刚才的微博请求，使用当前注册的实际业务工具；互动仍需现有操作确认。";
        } catch (WeiboOAuthService.AuthorizationRequiredException failure) {
            close();
            return requireAuthorization(sessionId);
        } catch (IllegalStateException failure) {
            String message = "已有微博授权，但本次连接未成功。请检查网络或账号服务后，在对话中重试本次请求。";
            conversationActions.put(sessionId, new WeiboAction("retry", message));
            return "retry_required：" + message + " 不自动重试，不编造微博结果。";
        }
    }

    private String requireAuthorization(String sessionId) {
        String message = "这次请求需要使用微博，请先授权微博账号。授权完成返回本对话后，我会继续刚才的请求。";
        conversationActions.put(sessionId, new WeiboAction("authorize", message));
        return "authorization_required：" + message + " 页面会在当前回复下提供授权按钮。请暂停微博业务，不要求用户提供密码、Cookie 或 Token。";
    }

    public void clearConversationAction(String sessionId) { conversationActions.remove(sessionId); }

    public WeiboAction consumeConversationAction(String sessionId) { return conversationActions.remove(sessionId); }

    public synchronized void connect() {
        if (!properties.isEnabled()) throw new IllegalStateException("微博 MCP 未启用。");
        oauth.accessToken();
        close();
        var transport = HttpClientStreamableHttpTransport.builder(properties.getUrl().toString())
                .clientBuilder(WeiboHttpClient.builder())
                .endpoint(properties.getUrl().getRawPath())
                .openConnectionOnStartup(false)
                .asyncHttpRequestCustomizer((request, method, uri, body, context) ->
                        Mono.fromCallable(() -> request.setHeader("Authorization", "Bearer " + oauth.accessToken()))
                                .subscribeOn(Schedulers.boundedElastic()))
                .build();
        var sdkClient = McpClient.async(transport).requestTimeout(Duration.ofSeconds(30))
                .initializationTimeout(Duration.ofSeconds(30)).build();
        McpClientWrapper candidate = new McpAsyncClientWrapper("weibo-mcp", sdkClient);
        try {
            candidate.initialize().block(Duration.ofSeconds(40));
            var tools = candidate.listTools().block(Duration.ofSeconds(40));
            if (tools == null || tools.isEmpty()) throw new IllegalStateException("微博没有返回可用工具。");
            // 注册守卫：同名静默覆盖即连接失败（防工具遮蔽），不静默注册。
            Toolkit staging = new Toolkit();
            staging.registerMcpClient(candidate).block(Duration.ofSeconds(40));
            registrationGuard.verifyMcpRegistration(staging, java.util.Set.of(), tools.size(), "weibo-mcp");
            var before = registrationGuard.snapshot(toolkit);
            for (String name : staging.getToolNames()) {
                if (before.contains(name)) throw new IllegalStateException("微博工具与已注册工具冲突：" + name);
            }
            toolkit.registerMcpClient(candidate).block(Duration.ofSeconds(40));
            registrationGuard.verifyMcpRegistration(toolkit, before, tools.size(), "weibo-mcp");
            businessTools = staging.getToolNames().stream()
                    .map(name -> (io.agentscope.core.tool.ToolBase) staging.getTool(name)).toList();
            client = candidate;
            toolCount = tools.size();
            error = null;
            log.info("微博 MCP 已连接，加载 {} 个工具。", toolCount);
        } catch (RuntimeException failure) {
            candidate.close();
            error = "微博授权已保存，但 MCP 连接失败，请检查网络、开发者认证和体验服务后重试连接；授权失效时重新授权。";
            throw new IllegalStateException(error);
        }
    }

    public java.util.List<io.agentscope.core.tool.ToolBase> businessTools() { return businessTools; }
    /** Does not acquire the OAuth/network lock or refresh credentials. */
    public CapabilityAvailability availability(){
        if(!properties.isEnabled())return CapabilityAvailability.of(CapabilityAvailability.State.UNAVAILABLE,"DISABLED","微博能力已在本机停用");
        if(client==null||businessTools.isEmpty())return new CapabilityAvailability(
                CapabilityAvailability.State.PREPARATION_REQUIRED,"CONNECTION_REQUIRED","需先检查授权并按需连接；尚未验证账号权限或体验额度",java.util.Set.of(WeiboConnectionTool.NAME),System.currentTimeMillis());
        return CapabilityAvailability.of(CapabilityAvailability.State.UNKNOWN,"TOOLS_LOADED","微博工具已加载；授权、网络和体验额度未做在线验证");
    }

    public synchronized Status status() {
        return new Status(properties.isEnabled(), oauth.isAuthorized(), client != null, toolCount, error);
    }

    @PreDestroy
    public synchronized void close() {
        if (client != null) {
            toolkit.removeMcpClient("weibo-mcp").block(Duration.ofSeconds(10));
            client = null;
        }
        businessTools = java.util.List.of();
        toolCount = 0;
    }

    public record Status(boolean enabled, boolean authorized, boolean connected, int toolCount, String error) { }
}
