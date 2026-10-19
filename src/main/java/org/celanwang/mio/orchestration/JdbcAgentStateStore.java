package org.celanwang.mio.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.state.*;
import io.agentscope.core.util.JsonUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;

/** Durable SDK checkpoints; storage errors propagate instead of recreating an empty permission context. */
public final class JdbcAgentStateStore implements AgentStateStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public JdbcAgentStateStore(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    public void initialize(){
        jdbc.execute("CREATE TABLE IF NOT EXISTS agent_states (id TEXT PRIMARY KEY, user_id TEXT NOT NULL, session_id TEXT NOT NULL, state_key TEXT NOT NULL, content TEXT NOT NULL, UNIQUE(user_id, session_id, state_key))");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_agent_states_session ON agent_states(session_id)");
    }
    private String user(String id){return id==null?"":id;}
    private String key(String user,String session,String key){return UUID.nameUUIDFromBytes((user(user)+"\0"+session+"\0"+key).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private List<String> raw(String user,String session,String key){
        return jdbc.query("SELECT content FROM agent_states WHERE user_id = ? AND session_id = ? AND state_key = ?",
                (rs,n)->rs.getString(1),user(user),session,key);
    }
    private void write(String user,String session,String key,String kind,List<String> values){
        jdbc.update("INSERT INTO agent_states(id,user_id,session_id,state_key,content) VALUES (?,?,?,?,?) ON CONFLICT (user_id,session_id,state_key) DO UPDATE SET content = EXCLUDED.content",
                key(user,session,key),user(user),session,key,encode(Map.of("kind",kind,"values",values)));
    }
    @Override public void save(String user,String session,String key,State state){write(user,session,key,"state",List.of(JsonUtils.getJsonCodec().toJson(state)));}
    @Override public void save(String user,String session,String key,List<? extends State> states){write(user,session,key,"list",states.stream().map(s->JsonUtils.getJsonCodec().toJson(s)).toList());}
    private <T extends State> List<T> read(String user,String session,String key,Class<T> type,String kind){
        var rows=raw(user,session,key);if(rows.isEmpty())return List.of();
        try{
            var node=json.readTree(rows.get(0));
            if(!kind.equals(node.path("kind").asText()))throw new IllegalStateException("会话状态类型不匹配");
            if(!node.path("values").isArray()||("state".equals(kind)&&node.path("values").size()!=1))
                throw new IllegalStateException("会话状态封装损坏");
            List<T> values=new ArrayList<>();
            for(var value:node.path("values")){
                if(!value.isTextual())throw new IllegalStateException("会话状态内容损坏");
                T decoded=JsonUtils.getJsonCodec().fromJson(value.asText(),type);
                if(decoded==null)throw new IllegalStateException("会话状态为空");
                values.add(decoded);
            }
            return values;
        }catch(Exception e){throw new IllegalStateException("会话状态读取失败，不能安全恢复",e);}
    }
    @Override public <T extends State> Optional<T> get(String user,String session,String key,Class<T> type){return read(user,session,key,type,"state").stream().findFirst();}
    @Override public <T extends State> List<T> getList(String user,String session,String key,Class<T> type){return read(user,session,key,type,"list");}
    @Override public boolean exists(String user,String session){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM agent_states WHERE user_id = ? AND session_id = ?)",Boolean.class,user(user),session));}
    @Override public void delete(String user,String session){jdbc.update("DELETE FROM agent_states WHERE user_id = ? AND session_id = ?",user(user),session);}
    @Override public void delete(String user,String session,String key){jdbc.update("DELETE FROM agent_states WHERE user_id = ? AND session_id = ? AND state_key = ?",user(user),session,key);}
    @Override public Set<String> listSessionIds(String user){return new LinkedHashSet<>(jdbc.queryForList("SELECT DISTINCT session_id FROM agent_states WHERE user_id = ?",String.class,user(user)));}
}
