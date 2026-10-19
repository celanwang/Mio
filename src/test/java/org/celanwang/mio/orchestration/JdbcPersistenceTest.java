package org.celanwang.mio.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.*;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.util.JsonUtils;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.*;
import javax.sql.DataSource;
import java.sql.Connection;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** SQL boundary tests and real SDK codec round trips; not a substitute for PostgreSQL execution. */
class JdbcPersistenceTest {
    @Test void actualSdkCheckpointAndMessagePolymorphismRoundTripThroughTheStoredEnvelope(){
        var jdbc=mock(JdbcTemplate.class);var rows=new HashMap<String,String>();
        when(jdbc.update(startsWith("INSERT INTO agent_states"),any(Object[].class))).thenAnswer(inv->{
            Object[] args=(Object[])inv.getRawArguments()[1];rows.put(args[1]+"|"+args[2]+"|"+args[3],(String)args[4]);return 1;
        });
        when(jdbc.query(startsWith("SELECT content FROM agent_states"),org.mockito.ArgumentMatchers.<RowMapper<String>>any(),any(Object[].class)))
                .thenAnswer(inv->{Object[] args=(Object[])inv.getRawArguments()[2];String value=rows.get(args[0]+"|"+args[1]+"|"+args[2]);return value==null?List.of():List.of(value);});
        var store=new JdbcAgentStateStore(jdbc,new ObjectMapper());
        var call=ToolUseBlock.builder().id("pending-call").name("create-order").input(Map.of("items",List.of(Map.of("n",1)))).content("{\"items\":[{\"n\":1}]}").build();
        var state=AgentState.builder().sessionId("child").context(List.of(new UserMessage("原始信息"),AssistantMessage.builder().content(List.of(call)).build())).build();
        store.save(null,"child","agent_state",state);
        var restored=store.get(null,"child","agent_state",AgentState.class).orElseThrow();
        assertThat(restored).isNotSameAs(state);
        assertThat(JsonUtils.getJsonCodec().toJson(restored)).isEqualTo(JsonUtils.getJsonCodec().toJson(state));
        assertThat(restored.getContext().get(1).getContentBlocks(ToolUseBlock.class).get(0).getId()).isEqualTo("pending-call");
        assertThat(store.get(null,"other-child","agent_state",AgentState.class)).isEmpty();
        assertThatThrownBy(()->store.getList(null,"child","agent_state",AgentState.class)).isInstanceOf(IllegalStateException.class);
        store.save(null,"child","messages",List.of(new UserMessage("one"),new UserMessage("two")));
        assertThat(store.getList(null,"child","messages",UserMessage.class)).extracting(Msg::getTextContent).containsExactly("one","two");
    }
    @Test void damagedOrMissingEnvelopeFieldsNeverProduceAnEmptyRecoveredSession(){
        var jdbc=mock(JdbcTemplate.class);var store=new JdbcAgentStateStore(jdbc,new ObjectMapper());
        for(String raw:List.of("bad-json","{\"kind\":\"state\"}","{\"kind\":\"state\",\"values\":[]}","{\"kind\":\"state\",\"values\":[\"null\"]}")){
            when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<String>>any(),any(Object[].class))).thenReturn(List.of(raw));
            assertThatThrownBy(()->store.get(null,"child","agent_state",AgentState.class)).isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void revisionConflictRollsBackWithoutWritingAnAuditEvent() throws Exception {
        var jdbc=mock(JdbcTemplate.class);var source=mock(DataSource.class);var connection=mock(Connection.class);
        when(source.getConnection()).thenReturn(connection);when(connection.getAutoCommit()).thenReturn(true);
        var store=new JdbcCoordinationStore(jdbc,source,new ObjectMapper());
        var goal=new GoalRecord("g","root",1,"intent","intent",0,0);
        when(jdbc.update(startsWith("UPDATE goals"),any(Object[].class))).thenReturn(0);
        assertThatThrownBy(()->store.saveGoal(goal,goal.appendUser("new",1))).isInstanceOf(IllegalStateException.class);
        verify(connection).rollback();verify(connection,never()).commit();
        verify(jdbc,never()).update(startsWith("INSERT INTO events"),any(Object[].class));
    }
    @Test void auditFailureRollsBackTheBusinessStateTransaction() throws Exception {
        var jdbc=mock(JdbcTemplate.class);var source=mock(DataSource.class);var connection=mock(Connection.class);
        when(source.getConnection()).thenReturn(connection);when(connection.getAutoCommit()).thenReturn(true);
        when(jdbc.update(startsWith("INSERT INTO events"),any(Object[].class))).thenThrow(new org.springframework.dao.DataIntegrityViolationException("audit failed"));
        var store=new JdbcCoordinationStore(jdbc,source,new ObjectMapper());
        assertThatThrownBy(()->store.createGoal(new GoalRecord("g","s",1,"intent","intent",0,0)))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        verify(jdbc).update(startsWith("INSERT INTO goals"),any(Object[].class));
        verify(connection).rollback();verify(connection,never()).commit();
    }
}
