package org.celanwang.mio.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.InMemoryAgentStateStore;
import org.celanwang.mio.controller.TaskController;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Clock;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GoalAcceptanceApiTest {
    final JdbcGoalAcceptance acceptance=mock(JdbcGoalAcceptance.class);
    final MultiAgentChatService chat=mock(MultiAgentChatService.class);
    final TaskRuntime runtime=mock(TaskRuntime.class);
    final String request="{\"sessionId\":\"s\",\"expectedGoalRevision\":1,\"expectedControlRevision\":0,\"expectedRevision\":0,\"selections\":[]}";
    org.springframework.test.web.servlet.MockMvc mvc(){return MockMvcBuilders.standaloneSetup(new TaskController(runtime,chat,mock(GoalLifecycle.class),acceptance)).build();}
    @Test void explicitConfirmationNeverInvokesBusinessRuntimeAndErrorsAreVisible() throws Exception {
        mvc().perform(put("/api/tasks/goal/acceptance").contentType("application/json").content(request)).andExpect(status().isOk());
        verify(acceptance).confirm(argThat(r->r.selections().isEmpty()&&r.expectedRevision()==0));verifyNoInteractions(runtime);
        when(acceptance.confirm(any())).thenThrow(new IllegalStateException("版本冲突"));
        mvc().perform(put("/api/tasks/goal/acceptance").contentType("application/json").content(request)).andExpect(status().isConflict());
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("private DB details")).when(acceptance).confirm(any());
        var response=mvc().perform(put("/api/tasks/goal/acceptance").contentType("application/json").content(request)).andExpect(status().isServiceUnavailable()).andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain("private DB details");
    }
    @Test void missingExplicitSelectionOrVersionFailsBeforeConfirmation() throws Exception {
        mvc().perform(put("/api/tasks/goal/acceptance").contentType("application/json").content(request.replace(",\"selections\":[]",""))).andExpect(status().isBadRequest());
        mvc().perform(put("/api/tasks/goal/acceptance").contentType("application/json").content(request.replace("\"expectedControlRevision\":0,",""))).andExpect(status().isBadRequest());
        verifyNoInteractions(acceptance,runtime,chat);
    }
    @Test void supervisorOnlyReceivesReadAccessToUserAcceptance() {
        var model=mock(Model.class);when(model.getModelName()).thenReturn("offline");
        var budget=new RunBudget(new RunBudget.Limits(5,10,5,1000,10000,5000),Clock.systemUTC());
        try(var agent=new SupervisorFactory(model,new InMemoryAgentStateStore(),new AgentRegistry(List.of()),runtime,new ObjectMapper(),acceptance).create("s",budget)) {
            assertThat(agent.getToolkit().getToolNames()).contains("read_goal_acceptance").noneMatch(name->name.contains("confirm")||name.contains("set_goal")||name.contains("clear_goal"));
        }
        verifyNoInteractions(acceptance,runtime);verify(model,never()).stream(anyList(),any(),any());
    }
}
