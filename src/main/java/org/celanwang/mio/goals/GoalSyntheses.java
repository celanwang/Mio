package org.celanwang.mio.goals;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;

/** Model interpretation is stored separately from deterministic acceptance and provider evidence. */
public final class GoalSyntheses {
    public record Finding(String text,List<String> stepIds){
        public Finding{check(text,2000);stepIds=stepIds==null?List.of():List.copyOf(stepIds);if(stepIds.isEmpty()||stepIds.size()>12)throw new IllegalArgumentException("综合结论需要对应的步骤来源");}
    }
    public record Report(String summary,List<Finding> findings,List<String> tradeoffs,List<String> gaps,List<String> nextSteps){
        public Report{check(summary,4000);findings=findings==null?List.of():List.copyOf(findings);if(findings.size()>12)throw new IllegalArgumentException("综合结论过多");tradeoffs=lines(tradeoffs);gaps=lines(gaps);nextSteps=lines(nextSteps);}
        public void validate(Set<String> stepIds){for(var finding:findings)if(!stepIds.containsAll(finding.stepIds()))throw new IllegalArgumentException("综合引用了不存在的步骤");}
    }
    private final JdbcTemplate jdbc;private final ObjectMapper json;
    public GoalSyntheses(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    public void save(String goal,String hash,Report report){try{jdbc.update("INSERT INTO goal_syntheses(id,goal_id,source_hash,content,created_at) VALUES (?,?,?,?,?) ON CONFLICT(goal_id,source_hash) DO UPDATE SET content=EXCLUDED.content,created_at=EXCLUDED.created_at",UUID.randomUUID().toString(),goal,hash,json.writeValueAsString(report),System.currentTimeMillis());}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalArgumentException("综合内容无效",e);}}
    public Optional<Report> current(String goal,String hash){return jdbc.query("SELECT content FROM goal_syntheses WHERE goal_id=? AND source_hash=?",(r,n)->{try{return json.readValue(r.getString(1),Report.class);}catch(Exception e){throw new IllegalStateException("综合快照不可读",e);}},goal,hash).stream().findFirst();}
    private static void check(String value,int maximum){if(value==null||value.isBlank()||value.length()>maximum)throw new IllegalArgumentException("综合文字为空或超出长度限制");}
    private static List<String> lines(List<String> values){var copy=values==null?List.<String>of():List.copyOf(values);if(copy.size()>8)throw new IllegalArgumentException("综合条目过多");copy.forEach(s->check(s,1000));return copy;}
}
