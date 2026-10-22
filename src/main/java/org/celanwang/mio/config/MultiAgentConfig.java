package org.celanwang.mio.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.Model;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.tool.*;
import io.agentscope.extensions.judge.jev.JevClient;
import org.celanwang.mio.memory.*;
import org.celanwang.mio.orchestration.*;
import org.celanwang.mio.security.PolicyEngine;
import org.celanwang.mio.service.*;
import org.celanwang.mio.tool.*;
import org.celanwang.mio.trace.TraceStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;

/** Controlled rollout. No extra models, MCP connections or schema initialization when disabled. */
@Configuration
@ConditionalOnProperty(prefix="app.multi-agent",name="enabled",havingValue="true")
public class MultiAgentConfig {
    private DomainDefinition definition(String id,String name,String description,String skillsDir,java.util.function.Supplier<List<ToolBase>> tools){
        try{
            Path directory=Path.of(skillsDir,id);String prompt=Files.readString(directory.resolve("SKILL.md"));
            var digest=MessageDigest.getInstance("SHA-256");
            try(var files=Files.walk(directory)){
                for(Path file:files.filter(Files::isRegularFile).filter(p->!p.toString().contains("__pycache__")&&!p.toString().endsWith(".pyc")&&!p.getFileName().toString().equals(".DS_Store")).sorted().toList()){
                    digest.update(directory.relativize(file).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));digest.update((byte)0);
                    digest.update(Files.readAllBytes(file));digest.update((byte)0);
                }
            }
            return new DomainDefinition(new AgentCapability(id,name,description,id,HexFormat.of().formatHex(digest.digest()),
                    List.of("查询与准备可自主进行","写操作受任务范围和权限链约束","业务结果需实际回执核查")),prompt,tools);
        }catch(Exception e){throw new IllegalStateException("无法读取领域技能："+id,e);}
    }
    @Bean DomainDefinition mcdDomain(@Qualifier("toolkit")Toolkit source,@Value("${app.agent.skills-dir:./skills}")String dir){
        return definition("mcd-ordering","餐饮助手","麦当劳菜单、门店、优惠、算价、点餐准备与经确认的订单操作。",dir,
                ()->source.getToolNames().stream().map(name->{var tool=source.getTool(name);if(!(tool instanceof ToolBase base))throw new IllegalStateException("业务工具必须保持 ToolBase 权限边界");return base;}).toList());
    }
    @Bean DomainDefinition rideDomain(TencentRideService service,@Value("${app.agent.skills-dir:./skills}")String dir){
        return definition("tencent-ride","出行助手","腾讯即时出行的地点确认、询价、当前订单查询、经人工确认的叫车与取消。",dir,
                ()->List.of(new TencentRideTool(service),new TencentRideCreateOrderTool(service)));
    }
    @Bean DomainDefinition weiboDomain(WeiboMcpService service,@Value("${app.agent.skills-dir:./skills}")String dir){
        return definition("weibo-assistant","微博助手","微博授权准备、已注册接口范围内的查询与内容处理，指定互动仍需确认。",dir,()->{
            var tools=new ArrayList<ToolBase>();tools.add(new WeiboConnectionTool(service));tools.addAll(service.businessTools());return tools;
        });
    }
    @Bean AgentRegistry agentRegistry(List<DomainDefinition> definitions){return new AgentRegistry(definitions.stream().map(DomainDefinition::capability).toList());}
    @Bean JdbcCoordinationStore coordinationStore(JdbcTemplate jdbc,DataSource source,ObjectMapper json){return new JdbcCoordinationStore(jdbc,source,json);}
    @Bean JdbcAgentStateStore coordinationAgentStateStore(JdbcTemplate jdbc,ObjectMapper json){return new JdbcAgentStateStore(jdbc,json);}
    @Bean JdbcGoalBudgetStore goalBudgetStore(JdbcTemplate jdbc,DataSource source,ObjectMapper json){return new JdbcGoalBudgetStore(jdbc,source,json);}
    @Bean @Order(Ordered.HIGHEST_PRECEDENCE+1)
    ApplicationRunner coordinationSchema(JdbcCoordinationStore store,JdbcAgentStateStore states,JdbcGoalBudgetStore budgets){return args->{store.initialize();states.initialize();budgets.initialize();};}
    @Bean DomainAgentFactory domainAgentFactory(List<DomainDefinition> definitions,@Qualifier("dashScopePlusModel")Model model,
            JdbcAgentStateStore states,AgentSkillRepository skills,PolicyEngine policy,WeiboMcpService weibo,ObjectProvider<JevClient> jev,
            @Value("${app.jev.auto-approve-threshold:0.8}")double threshold,TraceStore traces,TrustStore trust,MioLongTermMemory memory,
            HabitDetector habits,ProfileStore profiles,ObjectMapper json){
        return new DomainAgentFactory(definitions,c->model,states,skills,policy,weibo,jev,threshold,traces,trust,memory,habits,profiles,json);
    }
    @Bean OperationVerifier tencentOrderReceiptVerifier(){return new TencentOrderReceiptVerifier();}
    @Bean TaskRuntime taskRuntime(JdbcCoordinationStore store,AgentRegistry registry,DomainAgentFactory factory,PolicyEngine policy,List<OperationVerifier> verifiers,
                                  @Value("${app.multi-agent.max-tasks:16}")int maxTasks){
        return new TaskRuntime(store,registry,factory,policy,Clock.systemUTC(),maxTasks,verifiers);
    }
    @Bean SupervisorFactory supervisorFactory(Model model,JdbcAgentStateStore states,AgentRegistry registry,TaskRuntime runtime,ObjectMapper json){
        return new SupervisorFactory(model,states,registry,runtime,json);
    }
    @Bean MultiAgentChatService multiAgentChatService(SupervisorFactory factory,TaskRuntime runtime,AgentRegistry registry,
            @Value("${app.multi-agent.max-model-calls:40}")int models,@Value("${app.multi-agent.max-tool-calls:80}")int tools,
            @Value("${app.multi-agent.max-task-calls:12}")int tasks,@Value("${app.multi-agent.max-tokens:80000}")long tokens,
            @Value("${app.multi-agent.max-context-characters:100000}")int context,@Value("${app.multi-agent.timeout-ms:150000}")long millis,
            @Value("${app.multi-agent.goal-max-model-calls:160}")int goalModels,@Value("${app.multi-agent.goal-max-tool-calls:320}")int goalTools,
            @Value("${app.multi-agent.goal-max-task-calls:48}")int goalTasks,@Value("${app.multi-agent.goal-max-tokens:320000}")long goalTokens,
            TraceStore traces,MioLongTermMemory memory,ProfileStore profiles,JdbcGoalBudgetStore budgets){
        return new MultiAgentChatService(factory,runtime,registry,new RunBudget.Limits(models,tools,tasks,tokens,context,millis),
                Clock.systemUTC(),traces,memory,profiles,budgets,new GoalBudgetStore.Limits(goalModels,goalTools,goalTasks,goalTokens));
    }
}
