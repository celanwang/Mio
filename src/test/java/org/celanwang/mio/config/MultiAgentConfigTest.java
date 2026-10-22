package org.celanwang.mio.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.Model;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.tool.Toolkit;
import org.celanwang.mio.memory.*;
import org.celanwang.mio.orchestration.*;
import org.celanwang.mio.security.PolicyEngine;
import org.celanwang.mio.service.*;
import org.celanwang.mio.trace.TraceStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.time.Clock;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class MultiAgentConfigTest {
    @Test void disabledModeCreatesNoCoordinationBeansOrDatabaseConnections(){
        new ApplicationContextRunner().withUserConfiguration(MultiAgentConfig.class)
                .withPropertyValues("app.multi-agent.enabled=false").run(context->{
                    assertThat(context).doesNotHaveBean(TaskRuntime.class).doesNotHaveBean(JdbcAgentStateStore.class);
                });
    }
    @Test void enabledModeWiresAllThreeDomainsWithoutStartingExternalServices(){
        new ApplicationContextRunner().withUserConfiguration(Dependencies.class,MultiAgentConfig.class)
                .withPropertyValues("app.multi-agent.enabled=true").run(context->{
                    assertThat(context).hasSingleBean(TaskRuntime.class).hasSingleBean(MultiAgentChatService.class);
                    assertThat(context.getBean(AgentRegistry.class).list().stream().map(AgentCapability::id).toList())
                            .containsExactlyInAnyOrder("mcd-ordering","tencent-ride","weibo-assistant");
                    var budget=new RunBudget(new RunBudget.Limits(2,2,2,1000,10000,1000),Clock.systemUTC());
                    try(var root=context.getBean(SupervisorFactory.class).create("fixture",budget)){
                        assertThat(root.getToolkit().getToolNames()).containsExactlyInAnyOrder("discover_agents","delegate_task","list_tasks","continue_task","cancel_task","reconcile_operation","review_plan","run_queued_task","read_goal_acceptance");
                    }
                    verifyNoInteractions(context.getBean(DataSource.class),context.getBean(WeiboMcpService.class),context.getBean(TencentRideService.class));
                });
    }
    @Test void discoveryUsesLocalDomainProbesWithoutOpeningConnections(){
        new ApplicationContextRunner().withUserConfiguration(Dependencies.class,MultiAgentConfig.class).withPropertyValues("app.multi-agent.enabled=true").run(context->{
            var ride=context.getBean(TencentRideService.class);var weibo=context.getBean(WeiboMcpService.class);
            when(ride.availability()).thenReturn(CapabilityAvailability.of(CapabilityAvailability.State.UNAVAILABLE,"CREDENTIAL_REQUIRED","需本机配置"));
            when(weibo.availability()).thenReturn(new CapabilityAvailability(CapabilityAvailability.State.PREPARATION_REQUIRED,"CONNECTION_REQUIRED","先准备",java.util.Set.of("prepare_weibo"),0));
            var registry=context.getBean(AgentRegistry.class);
            assertThat(registry.status("mcd-ordering").code()).isEqualTo("NO_TOOLS");
            assertThat(registry.status("tencent-ride").code()).isEqualTo("CREDENTIAL_REQUIRED");
            assertThat(registry.status("weibo-assistant").state()).isEqualTo(CapabilityAvailability.State.PREPARATION_REQUIRED);
            verifyNoInteractions(context.getBean(DataSource.class));verify(weibo,never()).connect();
        });
    }
    @Configuration(proxyBeanMethods=false)
    static class Dependencies {
        @Bean @Primary Model primaryModel(){return mock(Model.class);}
        @Bean("dashScopePlusModel") Model standardModel(){return mock(Model.class);}
        @Bean("toolkit") Toolkit tools(){return new Toolkit();}
        @Bean ObjectMapper mapper(){return new ObjectMapper();}
        @Bean DataSource source(){return mock(DataSource.class);}
        @Bean JdbcTemplate jdbc(){return mock(JdbcTemplate.class);}
        @Bean AgentSkillRepository skills(){return mock(AgentSkillRepository.class);}
        @Bean PolicyEngine policy(){return new PolicyEngine("shadow");}
        @Bean WeiboMcpService weibo(){return mock(WeiboMcpService.class);}
        @Bean TencentRideService ride(){return mock(TencentRideService.class);}
        @Bean TraceStore traces(){return new TraceStore((s,e)->{});}
        @Bean TrustStore trust(){return mock(TrustStore.class);}
        @Bean MioLongTermMemory memory(){return mock(MioLongTermMemory.class);}
        @Bean HabitDetector habits(){return mock(HabitDetector.class);}
        @Bean ProfileStore profiles(){return mock(ProfileStore.class);}
    }
}
