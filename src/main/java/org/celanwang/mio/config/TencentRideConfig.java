package org.celanwang.mio.config;

import org.celanwang.mio.service.TencentRideService;
import org.celanwang.mio.tool.TencentRideTool;
import org.celanwang.mio.tool.TencentRideCreateOrderTool;
import io.agentscope.core.ReActAgent;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TencentRideConfig {
    @Bean
    public SmartInitializingSingleton registerTencentRideTool(ReActAgent agent, TencentRideService service) {
        // 注册到 Agent 实际使用的 Toolkit 副本；启动时不执行 Python 或外部业务请求。
        return () -> {
            agent.getToolkit().registerAgentTool(new TencentRideTool(service));
            agent.getToolkit().registerAgentTool(new TencentRideCreateOrderTool(service));
        };
    }
}
