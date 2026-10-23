package org.celanwang.mio.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import io.agentscope.core.tool.*;
import io.agentscope.extensions.judge.jev.JevClient;
import org.celanwang.mio.memory.*;
import org.celanwang.mio.model.dto.ChatRequest;
import org.celanwang.mio.security.PolicyEngine;
import org.celanwang.mio.service.WeiboMcpService;
import org.celanwang.mio.trace.TraceStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import reactor.core.publisher.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Actual foreground orchestration/state/approval/memory chain, with scripted models and a synthetic provider. */
@EnabledIfEnvironmentVariable(named="MIO_TEST_PG_URL",matches="jdbc:postgresql:.*")
class JdbcOrchestrationAcceptanceTest {
    JdbcTemplate admin,jdbc;javax.sql.DataSource source;String schema;
    final ObjectMapper json=new ObjectMapper();final Clock clock=Clock.systemUTC();
    JdbcCoordinationStore store;JdbcAgentStateStore states;JdbcExecutionLeaseStore leases;JdbcTaskQueue queue;JdbcGoalBudgetStore budgets;
    ProfileStore profiles;MioLongTermMemory memory;TraceStore traces;AgentRegistry registry;TaskRuntime runtime;MultiAgentChatService chat;JdbcGoalAcceptance acceptance;
    final AtomicInteger rootCalls=new AtomicInteger(),quoteCalls=new AtomicInteger(),actionCalls=new AtomicInteger(),reads=new AtomicInteger(),writes=new AtomicInteger();
    final Map<String,String> providerState=new LinkedHashMap<>();final Model backgroundModel=mock(Model.class);
    PgEpisodeStore episodeStore;
    final String intent="先查询并分析报价，再准备创建操作；本次写入必须等待我确认。";

    @BeforeEach void initialize() {
        var base=new DriverManagerDataSource(System.getenv("MIO_TEST_PG_URL"),System.getenv().getOrDefault("MIO_TEST_PG_USERNAME","postgres"),System.getenv().getOrDefault("MIO_TEST_PG_PASSWORD",""));
        admin=new JdbcTemplate(base);schema="mio_chain_"+UUID.randomUUID().toString().replace("-","");admin.execute("CREATE SCHEMA "+schema);
        source=new AbstractDataSource() {
            private Connection scope(Connection c)throws SQLException{try(var s=c.createStatement()){s.execute("SET search_path TO "+schema);return c;}catch(SQLException failure){c.close();throw failure;}}
            public Connection getConnection()throws SQLException{return scope(base.getConnection());}public Connection getConnection(String u,String p)throws SQLException{return scope(base.getConnection(u,p));}
        };
        jdbc=new JdbcTemplate(source);jdbc.execute("CREATE TABLE events(id TEXT PRIMARY KEY,ts BIGINT NOT NULL,session_id TEXT,node TEXT,type TEXT,title TEXT,detail TEXT)");
        jdbc.execute("CREATE TABLE messages(id TEXT PRIMARY KEY,session_id TEXT,ts BIGINT NOT NULL,role TEXT NOT NULL,text TEXT NOT NULL)");ProfileStore.initializeSchema(jdbc);
        store=new JdbcCoordinationStore(jdbc,source,json);store.initialize();states=new JdbcAgentStateStore(jdbc,json);states.initialize();leases=new JdbcExecutionLeaseStore(jdbc,source);leases.initialize();
        queue=new JdbcTaskQueue(jdbc,source,json,store,leases);queue.initialize();budgets=new JdbcGoalBudgetStore(jdbc,source,json);budgets.initialize();profiles=new ProfileStore(jdbc);traces=new TraceStore(new PgEventPublisher(jdbc));
        var memoryJobs=new org.celanwang.mio.memory.jobs.JdbcMemoryJobs(jdbc,source,3,120000,1000);memoryJobs.initialize();
        episodeStore=new PgEpisodeStore(jdbc,memoryJobs,false,false);var embeddings=mock(EmbeddingClient.class);
        memory=new MioLongTermMemory(mock(AutomationStore.class),mock(ProfileInjector.class),
                new EpisodeRetriever(false,3,.6,episodeStore,mock(VectorStore.class),embeddings),embeddings,episodeStore);
        var quote=new AgentCapability("quote-domain","报价能力","只准备报价","quote-skill","v1",List.of(),List.of(),List.of(CapabilityOutputContract.report(),CapabilityOutputContract.rideQuote()));
        var action=new AgentCapability("action-domain","执行能力","分析或经批准执行","action-skill","v1",List.of(),CapabilityInputContract.contextual(),List.of(CapabilityOutputContract.report(),CapabilityOutputContract.resourceReference()));
        registry=new AgentRegistry(List.of(quote,action));
        acceptance=new JdbcGoalAcceptance(jdbc,source,store,registry,json,clock);acceptance.initialize();
        var quoteModel=scripted("quote-script",()->switch(quoteCalls.getAndIncrement()) {
            case 0->tool("query-meals",Map.of());case 1->tool("mio_finish_task",Map.of("text","已取得可信报价"));default->throw new AssertionError("unexpected quote call");
        });
        var actionModel=scripted("action-script",()->switch(actionCalls.getAndIncrement()) {
            case 0->tool("mio_finish_task",Map.of("text","已根据绑定报价完成分析"));case 1->tool("create-order",Map.of());
            case 2->tool("mio_finish_task",Map.of("text","请按可信证据验收操作结果"));default->throw new AssertionError("unexpected action call");
        });
        var definitions=List.of(new DomainDefinition(quote,"查询后返回，不执行写入",()->List.of(providerTool("query-meals",true))),
                new DomainDefinition(action,"分析绑定事实，写入等待确认",()->List.of(providerTool("create-order",false))));
        @SuppressWarnings("unchecked") ObjectProvider<JevClient> jev=mock(ObjectProvider.class);
        var policy=new PolicyEngine("shadow");
        var factory=new DomainAgentFactory(definitions,c->c.id().equals("quote-domain")?quoteModel:actionModel,states,null,policy,mock(WeiboMcpService.class),jev,.8,traces,mock(TrustStore.class),memory,mock(HabitDetector.class),profiles,json);
        FactExtractor extractor=new FactExtractor() {
            public String source(){return "synthetic-quote-adapter-v1";}
            public Optional<String> invalidatedKey(String tool,Map<String,Object> input){return tool.equals("query-meals")?Optional.of("ride.quote"):Optional.empty();}
            public Optional<Value> extract(String tool,Map<String,Object> input,String raw,long now){return tool.equals("query-meals")&&raw.equals("synthetic-quote")?Optional.of(new Value("ride_quote",Map.of("durationSeconds","900"),now+60000)):Optional.empty();}
        };
        OperationVerifier verifier=new OperationVerifier() {
            public boolean supports(String tool){return tool.equals("create-order");}
            public Verification verify(String raw){return new Verification(raw.equals("synthetic-created:resource-1")?OperationOutcome.CONFIRMED_APPLIED:OperationOutcome.UNKNOWN,"resource-1","synthetic-create-adapter-v1");}
        };
        runtime=new TaskRuntime(store,registry,factory,policy,clock,16,List.of(verifier),List.of(),List.of(extractor),leases,queue);
        chat=service(scripted("root-script",this::rootStep));
    }
    @AfterEach void cleanup(){if(admin!=null&&schema!=null)admin.execute("DROP SCHEMA "+schema+" CASCADE");}
    Model scripted(String name,Supplier<ContentBlock> next) {
        var model=mock(Model.class);when(model.getModelName()).thenReturn(name);
        when(model.stream(anyList(),any(),any())).thenAnswer(inv->Flux.defer(()->Flux.just(io.agentscope.core.model.ChatResponse.builder().content(List.of(next.get()))
                .usage(ChatUsage.builder().inputTokens(5).outputTokens(3).build()).build())));return model;
    }
    ContentBlock tool(String name,Map<String,Object> input) {
        try{return ToolUseBlock.builder().id(UUID.randomUUID().toString()).name(name).input(input).content(json.writeValueAsString(input)).build();}
        catch(Exception error){throw new IllegalStateException(error);}
    }
    ContentBlock rootStep() {
        return switch(rootCalls.getAndIncrement()) {
            case 0->tool("discover_agents",Map.of());
            case 1->tool("delegate_task",Map.of("key","quote","capabilityId","quote-domain","objective","取得可信报价","scope","PREPARE","outputContract","ride-quote"));
            case 2->{var parent=byKey("quote");var fact=TaskFacts.of(parent).get(0);
                yield tool("delegate_task",Map.of("key","analysis","capabilityId","action-domain","objective","分析报价","scope","PREPARE","dependencies",List.of(parent.id()),"inputContract","ride-quote-context",
                        "inputs",List.of(Map.of("taskId",parent.id(),"factId",fact.id(),"version",fact.version(),"alias","rideQuote"))));}
            case 3->tool("delegate_task",Map.of("key","create","capabilityId","action-domain","objective","准备经人工确认的创建操作","scope","REQUEST_APPROVAL","dependencies",List.of(byKey("analysis").id()),"outputContract","resource-reference"));
            case 4->TextBlock.builder().text("准备已完成，请确认创建操作").build();
            case 5->tool("read_goal_acceptance",Map.of());
            case 6->TextBlock.builder().text(writes.get()==1?"资源创建已核实，后续履约未验证":"操作未执行，输出契约未满足").build();
            default->throw new AssertionError("unexpected supervisor call");
        };
    }
    TaskRecord byKey(String key){return store.tasks("root").stream().filter(t->t.spec().key().equals(key)).findFirst().orElseThrow();}
    ToolBase providerTool(String name,boolean read) {
        return new ToolBase(ToolBase.builder().name(name).description("synthetic business world").inputSchema(Map.of("type","object","properties",Map.of())).readOnly(read)) {
            @Override public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                if(read){reads.incrementAndGet();return Mono.just(ToolResultBlock.text("synthetic-quote"));}
                writes.incrementAndGet();providerState.put("resource-1","CREATED");return Mono.just(ToolResultBlock.text("synthetic-created:resource-1"));
            }
        };
    }
    MultiAgentChatService service(Model model){return new MultiAgentChatService(new SupervisorFactory(model,states,registry,runtime,json,acceptance),runtime,registry,new RunBudget.Limits(50,100,20,100000,100000,20000),clock,traces,memory,profiles,budgets,new GoalBudgetStore.Limits(100,200,50,200000),leases,acceptance);}

    @Test void approvedWorkflowPersistsBothAgentLoopsEvidenceBudgetAndOnlyRealUserMemory(){runWorkflow(true);}
    @Test void rejectedWorkflowCannotCreateResourcesOrPassTheEvidenceContract(){runWorkflow(false);}
    void runWorkflow(boolean approve) {
        var request=new ChatRequest();request.setSessionId("root");request.setMessage(intent);
        var pending=chat.chat(request).block(Duration.ofSeconds(15));
        assertThat(pending.getPendingConfirmations()).hasSize(1);assertThat(reads.get()).isEqualTo(1);assertThat(writes.get()).isZero();assertThat(providerState).isEmpty();
        assertThat(pending.getTasks()).hasSize(3);assertThat(runtime.summary(byKey("quote")).resultAssessment().state()).isEqualTo(TaskResultAssessment.State.VERIFIED);
        assertThat(runtime.summary(byKey("analysis")).resultAssessment().state()).isEqualTo(TaskResultAssessment.State.EXECUTION_ONLY);
        assertThat(pending.getAcceptance().state()).isEqualTo(GoalAcceptanceView.State.NOT_DEFINED);
        var goal=runtime.goal("root");
        var selections=pending.getAcceptance().candidates().stream().map(c->new GoalAcceptance.Selection(c.taskId(),c.contractVersion(),c.contractId().equals("ride-quote")?
                List.of(new GoalAcceptance.Check("durationSeconds",GoalAcceptance.Operator.LTE,"900")):List.of())).toList();
        var afterCriteria=new org.celanwang.mio.controller.TaskController(runtime,chat,new JdbcGoalLifecycle(jdbc,source,store,queue),acceptance)
                .acceptance(new GoalAcceptance.Request("root",goal.revision(),goal.controlRevision(),0L,selections));
        assertThat(afterCriteria.getAcceptance().state()).isEqualTo(GoalAcceptanceView.State.PARTIALLY_SATISFIED);
        assertThat(afterCriteria.getBudget()).isEqualTo(pending.getBudget());assertThat(afterCriteria.getPendingConfirmations()).isEqualTo(pending.getPendingConfirmations());
        assertThat(runtime.goal("root")).isEqualTo(goal);assertThat(writes.get()).isZero();assertThat(rootCalls.get()).isEqualTo(5);
        var confirmation=new ChatRequest();confirmation.setSessionId("root");confirmation.setConfirmations(List.of(new ChatRequest.Confirmation(pending.getPendingConfirmations().get(0).toolCallId(),approve)));
        var response=chat.chat(confirmation).block(Duration.ofSeconds(15));
        assertThat(response.getPendingConfirmations()).isEmpty();assertThat(writes.get()).isEqualTo(approve?1:0);
        assertThat(providerState).isEqualTo(approve?Map.of("resource-1","CREATED"):Map.of());
        var result=runtime.summary(byKey("create"));assertThat(result.status()).isEqualTo(approve?TaskStatus.COMPLETED:TaskStatus.CANCELLED);
        assertThat(result.resultAssessment().state()).isEqualTo(approve?TaskResultAssessment.State.VERIFIED:TaskResultAssessment.State.NOT_FINISHED);
        assertThat(response.getAcceptance().state()).isEqualTo(approve?GoalAcceptanceView.State.SATISFIED:GoalAcceptanceView.State.PARTIALLY_SATISFIED);
        assertThat(rootCalls.get()).isEqualTo(7);assertThat(quoteCalls.get()).isEqualTo(2);assertThat(actionCalls.get()).isEqualTo(approve?3:2);
        int models=approve?12:11;
        assertThat(response.getBudget().models()).isEqualTo(models);assertThat(response.getBudget().knownTokens()).isEqualTo(models*8L);assertThat(response.getBudget().usageComplete()).isTrue();
        assertThat(states.exists(null,"root:supervisor")).isTrue();for(var task:store.tasks("root"))assertThat(states.exists(null,task.childSessionId())).isTrue();
        var remembered=episodeStore.listAll();assertThat(remembered).hasSize(2);assertThat(remembered).extracting(EpisodeStore.Episode::sessionId).containsOnly("root");
        assertThat(remembered).extracting(EpisodeStore.Episode::text).contains(intent).doesNotContain("分析报价","取得可信报价");verifyNoInteractions(backgroundModel);
        var noModel=mock(Model.class);var restored=service(noModel).response("root","");
        assertThat(restored.getBudget()).isEqualTo(response.getBudget());assertThat(restored.getTasks()).hasSize(3);verifyNoInteractions(noModel);
        assertThat(restored.getAcceptance().confirmed()).isEqualTo(response.getAcceptance().confirmed());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM events WHERE type='budget_settled'",Integer.class)).isEqualTo(models);
    }
}
