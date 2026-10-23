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
        return definition(id,name,description,skillsDir,tools,List.of(CapabilityOutputContract.report()));
    }
    private DomainDefinition definition(String id,String name,String description,String skillsDir,java.util.function.Supplier<List<ToolBase>> tools,List<CapabilityOutputContract> outputs){
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
                    List.of("查询与准备可自主进行","写操作受任务范围和权限链约束","业务结果需实际回执核查"),CapabilityInputContract.contextual(),
                    outputs),prompt,tools);
        }catch(Exception e){throw new IllegalStateException("无法读取领域技能："+id,e);}
    }
    @Bean DomainDefinition mcdDomain(@Qualifier("toolkit")Toolkit source,@Value("${app.agent.skills-dir:./skills}")String dir){
        return definition("mcd-ordering","餐饮助手","麦当劳菜单、门店、优惠、算价、点餐准备与经确认的订单操作。",dir,
                ()->source.getToolNames().stream().map(name->{var tool=source.getTool(name);if(!(tool instanceof ToolBase base))throw new IllegalStateException("业务工具必须保持 ToolBase 权限边界");return base;}).toList());
    }
    @Bean DomainDefinition rideDomain(TencentRideService service,@Value("${app.agent.skills-dir:./skills}")String dir){
        var domain=definition("tencent-ride","出行助手","腾讯即时出行的地点确认、询价、当前订单查询、经人工确认的叫车与取消。",dir,
                ()->List.of(new TencentRideTool(service),new TencentRideCreateOrderTool(service)),
                List.of(CapabilityOutputContract.report(),CapabilityOutputContract.rideQuote(),CapabilityOutputContract.resourceReference()));
        return new DomainDefinition(domain.capability(),domain.prompt(),domain.tools(),service::availability);
    }
    @Bean DomainDefinition weiboDomain(WeiboMcpService service,@Value("${app.agent.skills-dir:./skills}")String dir){
        var domain=definition("weibo-assistant","微博助手","微博授权准备、已注册接口范围内的查询与内容处理，指定互动仍需确认。",dir,()->{
            var tools=new ArrayList<ToolBase>();tools.add(new WeiboConnectionTool(service));tools.addAll(service.businessTools());return tools;
        });
        return new DomainDefinition(domain.capability(),domain.prompt(),domain.tools(),service::availability);
    }
    @Bean org.celanwang.mio.goals.CapabilityPackages capabilityPackages(org.celanwang.mio.goals.JdbcDocuments documents,ObjectMapper json,org.springframework.core.env.Environment env,@Value("${app.agent.packages-dir:./agent-packages}")String directory,@Value("${app.agent.skills-dir:./skills}")String skills){return new org.celanwang.mio.goals.CapabilityPackages(Path.of(directory),Path.of(skills),documents,json,env::getProperty);}
    @Bean AgentRegistry agentRegistry(List<DomainDefinition> builtins,org.celanwang.mio.goals.CapabilityPackages packages){
        var definitions=packages.combine(builtins);
        var probes=new LinkedHashMap<String,java.util.function.Supplier<CapabilityAvailability>>();definitions.forEach(d->probes.put(d.capability().id(),d::checkAvailability));
        return new AgentRegistry(definitions.stream().map(DomainDefinition::capability).toList(),probes);
    }
    @Bean org.celanwang.mio.goals.JdbcDocuments workspaceDocuments(JdbcTemplate jdbc,DataSource source,JdbcCoordinationStore store){return new org.celanwang.mio.goals.JdbcDocuments(jdbc,source,store);}
    @Bean @Order(Ordered.HIGHEST_PRECEDENCE+3) ApplicationRunner workspaceDocumentSchema(org.celanwang.mio.goals.JdbcDocuments documents){return args->documents.initialize();}
    @Bean DomainDefinition researchDomain(org.celanwang.mio.goals.JdbcDocuments documents,ObjectMapper json,@Value("${app.agent.skills-dir:./skills}")String dir,@Value("${app.research.search-key:}")String key){return definition("research-assistant","信息研究助手","查询本地文档和配置的公开网页搜索，比较资料并交付带来源报告。",dir,()->List.of(new org.celanwang.mio.goals.DocumentReadTool(documents,true),new org.celanwang.mio.goals.DocumentReadTool(documents,false),new org.celanwang.mio.goals.WebSearchTool(key,json)));}
    @Bean DomainDefinition documentDomain(org.celanwang.mio.goals.JdbcDocuments documents,@Value("${app.agent.skills-dir:./skills}")String dir){return definition("document-assistant","文档助手","阅读、摘要、比较和改写用户导入文档，在任务回复交付文稿。",dir,()->List.of(new org.celanwang.mio.goals.DocumentReadTool(documents,true),new org.celanwang.mio.goals.DocumentReadTool(documents,false)));}
    @Bean JdbcCoordinationStore coordinationStore(JdbcTemplate jdbc,DataSource source,ObjectMapper json){return new JdbcCoordinationStore(jdbc,source,json);}
    @Bean JdbcAgentStateStore coordinationAgentStateStore(JdbcTemplate jdbc,ObjectMapper json){return new JdbcAgentStateStore(jdbc,json);}
    @Bean JdbcExecutionLeaseStore executionLeaseStore(JdbcTemplate jdbc,DataSource source){return new JdbcExecutionLeaseStore(jdbc,source);}
    @Bean JdbcTaskQueue taskQueue(JdbcTemplate jdbc,DataSource source,ObjectMapper json,JdbcCoordinationStore store,JdbcExecutionLeaseStore leases){return new JdbcTaskQueue(jdbc,source,json,store,leases);}
    @Bean GoalLifecycle goalLifecycle(JdbcTemplate jdbc,DataSource source,JdbcCoordinationStore store,JdbcTaskQueue queue){return new JdbcGoalLifecycle(jdbc,source,store,queue);}
    @Bean JdbcGoalBudgetStore goalBudgetStore(JdbcTemplate jdbc,DataSource source,ObjectMapper json){return new JdbcGoalBudgetStore(jdbc,source,json);}
    @Bean GoalUsageRecovery goalUsageRecovery(JdbcGoalBudgetStore budgets){return new GoalUsageRecovery(budgets);}
    @Bean org.celanwang.mio.goals.JdbcGoalPrograms goalPrograms(JdbcTemplate jdbc,DataSource source,ObjectMapper json,JdbcCoordinationStore store,AgentRegistry registry){return new org.celanwang.mio.goals.JdbcGoalPrograms(jdbc,source,json,store,registry);}
    @Bean @Order(Ordered.HIGHEST_PRECEDENCE+2)
    ApplicationRunner goalProgramSchema(org.celanwang.mio.goals.JdbcGoalPrograms programs){return args->programs.initialize();}
    @Bean org.celanwang.mio.goals.JdbcBusinessResources businessResources(JdbcTemplate jdbc,DataSource source,JdbcCoordinationStore store,ObjectMapper json){return new org.celanwang.mio.goals.JdbcBusinessResources(jdbc,source,store,json);}
    @Bean org.celanwang.mio.goals.JdbcGoalWakeups goalWakeups(JdbcTemplate jdbc,DataSource source,org.celanwang.mio.goals.JdbcGoalPrograms programs,JdbcCoordinationStore store,org.celanwang.mio.goals.JdbcBusinessResources resources){var wakeups=new org.celanwang.mio.goals.JdbcGoalWakeups(jdbc,source,programs,store);store.onTaskChanged(task->{wakeups.signal(task);resources.changed(task);});return wakeups;}
    @Bean @Order(Ordered.HIGHEST_PRECEDENCE+3) ApplicationRunner goalWakeupSchema(org.celanwang.mio.goals.JdbcGoalWakeups wakeups){return args->wakeups.initialize();}
    @Bean @Order(Ordered.HIGHEST_PRECEDENCE+3) ApplicationRunner businessResourceSchema(org.celanwang.mio.goals.JdbcBusinessResources resources){return args->resources.initialize();}
    @Bean org.celanwang.mio.goals.GoalProgramExecutor goalProgramExecutor(org.celanwang.mio.goals.JdbcGoalPrograms programs,org.celanwang.mio.goals.JdbcGoalWakeups wakeups,TaskRuntime runtime,JdbcExecutionLeaseStore leases,JdbcGoalBudgetStore budgets,AgentRegistry registry,org.springframework.core.env.Environment env){
        return new org.celanwang.mio.goals.GoalProgramExecutor(programs,wakeups,runtime,leases,budgets,registry,
            new RunBudget.Limits(env.getProperty("app.multi-agent.max-model-calls",Integer.class,40),env.getProperty("app.multi-agent.max-tool-calls",Integer.class,80),env.getProperty("app.multi-agent.max-task-calls",Integer.class,12),env.getProperty("app.multi-agent.max-tokens",Long.class,80000L),env.getProperty("app.multi-agent.max-context-characters",Integer.class,100000),Math.min(120000,env.getProperty("app.multi-agent.timeout-ms",Long.class,150000L))),
            new GoalBudgetStore.Limits(env.getProperty("app.multi-agent.goal-max-model-calls",Integer.class,160),env.getProperty("app.multi-agent.goal-max-tool-calls",Integer.class,320),env.getProperty("app.multi-agent.goal-max-task-calls",Integer.class,48),env.getProperty("app.multi-agent.goal-max-tokens",Long.class,320000L)));
    }
    @Bean org.celanwang.mio.goals.GoalProgramDispatcher goalProgramDispatcher(org.celanwang.mio.goals.JdbcGoalWakeups wakeups,org.celanwang.mio.goals.GoalProgramExecutor executor){return new org.celanwang.mio.goals.GoalProgramDispatcher(wakeups,executor);}
    @Bean org.celanwang.mio.goals.GoalWorkspace goalWorkspace(JdbcTemplate jdbc,DataSource source,org.celanwang.mio.goals.JdbcGoalPrograms programs,org.celanwang.mio.goals.GoalProgramExecutor executor,TaskRuntime runtime,org.celanwang.mio.goals.JdbcBusinessResources resources,JdbcExecutionLeaseStore leases,ObjectMapper json){var workspace=new org.celanwang.mio.goals.GoalWorkspace(jdbc,source,programs,executor,runtime,resources,leases,json);executor.onProgress(workspace::recordProgress);return workspace;}
    @Bean @Order(Ordered.HIGHEST_PRECEDENCE+4) ApplicationRunner goalWorkspaceSchema(org.celanwang.mio.goals.GoalWorkspace workspace){return args->workspace.initialize();}
    @Bean org.celanwang.mio.goals.GoalPlanGenerator goalPlanGenerator(Model model,AgentRegistry registry,TaskRuntime runtime,org.celanwang.mio.goals.JdbcGoalPrograms programs,JdbcExecutionLeaseStore leases,JdbcGoalBudgetStore budgets,ObjectMapper json,org.springframework.core.env.Environment env){return new org.celanwang.mio.goals.GoalPlanGenerator(model,registry,runtime,programs,leases,budgets,json).withLimits(new GoalBudgetStore.Limits(env.getProperty("app.multi-agent.goal-max-model-calls",Integer.class,160),env.getProperty("app.multi-agent.goal-max-tool-calls",Integer.class,320),env.getProperty("app.multi-agent.goal-max-task-calls",Integer.class,48),env.getProperty("app.multi-agent.goal-max-tokens",Long.class,320000L)));}
    @Bean JdbcGoalAcceptance goalAcceptance(JdbcTemplate jdbc,DataSource source,JdbcCoordinationStore store,AgentRegistry registry,ObjectMapper json){return new JdbcGoalAcceptance(jdbc,source,store,registry,json,Clock.systemUTC());}
    @Bean @Order(Ordered.HIGHEST_PRECEDENCE+1)
    ApplicationRunner coordinationSchema(JdbcCoordinationStore store,JdbcAgentStateStore states,JdbcGoalBudgetStore budgets,JdbcExecutionLeaseStore leases,JdbcTaskQueue queue,JdbcGoalAcceptance acceptance){return args->{store.initialize();states.initialize();budgets.initialize();leases.initialize();queue.initialize();acceptance.initialize();};}
    @Bean DomainAgentFactory domainAgentFactory(List<DomainDefinition> builtins,org.celanwang.mio.goals.CapabilityPackages packages,@Qualifier("dashScopePlusModel")Model model,
            JdbcAgentStateStore states,AgentSkillRepository skills,PolicyEngine policy,WeiboMcpService weibo,ObjectProvider<JevClient> jev,
            @Value("${app.jev.auto-approve-threshold:0.8}")double threshold,TraceStore traces,TrustStore trust,MioLongTermMemory memory,
            HabitDetector habits,ProfileStore profiles,ObjectMapper json){
        return new DomainAgentFactory(packages.combine(builtins),c->model,states,skills,policy,weibo,jev,threshold,traces,trust,memory,habits,profiles,json);
    }
    @Bean FactExtractor tencentQuoteFactExtractor(){return new TencentQuoteFactExtractor();}
    @Bean OperationVerifier tencentOrderReceiptVerifier(){return new TencentOrderReceiptVerifier();}
    @Bean OperationReconciler tencentOperationReconciler(TencentRideService service){return new TencentOperationReconciler(service);}
    @Bean TaskRuntime taskRuntime(JdbcCoordinationStore store,AgentRegistry registry,DomainAgentFactory factory,PolicyEngine policy,List<OperationVerifier> verifiers,
                                  @Value("${app.multi-agent.max-tasks:16}")int maxTasks,List<OperationReconciler> reconcilers,List<FactExtractor> factExtractors,JdbcExecutionLeaseStore leases,JdbcTaskQueue queue){
        return new TaskRuntime(store,registry,factory,policy,Clock.systemUTC(),maxTasks,verifiers,reconcilers,factExtractors,leases,queue);
    }
    @Bean SupervisorFactory supervisorFactory(Model model,JdbcAgentStateStore states,AgentRegistry registry,TaskRuntime runtime,ObjectMapper json,JdbcGoalAcceptance acceptance,org.celanwang.mio.goals.JdbcGoalPrograms programs){
        return new SupervisorFactory(model,states,registry,runtime,json,acceptance).withPrograms(programs);
    }
    @Bean MultiAgentChatService multiAgentChatService(SupervisorFactory factory,TaskRuntime runtime,AgentRegistry registry,
            @Value("${app.multi-agent.max-model-calls:40}")int models,@Value("${app.multi-agent.max-tool-calls:80}")int tools,
            @Value("${app.multi-agent.max-task-calls:12}")int tasks,@Value("${app.multi-agent.max-tokens:80000}")long tokens,
            @Value("${app.multi-agent.max-context-characters:100000}")int context,@Value("${app.multi-agent.timeout-ms:150000}")long millis,
            @Value("${app.multi-agent.goal-max-model-calls:160}")int goalModels,@Value("${app.multi-agent.goal-max-tool-calls:320}")int goalTools,
            @Value("${app.multi-agent.goal-max-task-calls:48}")int goalTasks,@Value("${app.multi-agent.goal-max-tokens:320000}")long goalTokens,
            TraceStore traces,MioLongTermMemory memory,ProfileStore profiles,JdbcGoalBudgetStore budgets,JdbcExecutionLeaseStore leases,JdbcGoalAcceptance acceptance){
        return new MultiAgentChatService(factory,runtime,registry,new RunBudget.Limits(models,tools,tasks,tokens,context,millis),
                Clock.systemUTC(),traces,memory,profiles,budgets,new GoalBudgetStore.Limits(goalModels,goalTools,goalTasks,goalTokens),leases,acceptance);
    }
}
