package org.celanwang.mio.memory;

import org.celanwang.mio.config.DomainAliasRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.*;

/** One bounded, non-authoritative preference view for planning, supervision and domain execution. */
@Component
public final class PersonalContext {
    private final JdbcTemplate jdbc;private final ProfileStore profiles;private final DomainAliasRegistry aliases;
    public PersonalContext(JdbcTemplate jdbc,ProfileStore profiles,DomainAliasRegistry aliases){this.jdbc=jdbc;this.profiles=profiles;this.aliases=aliases;}
    public Map<String,Object> capture(String session,Set<String> domains,String intent){
        try {
            var excluded=new HashSet<>(jdbc.queryForList("SELECT e.profile_id FROM goal_profile_exclusions e JOIN goals g ON g.id=e.goal_id WHERE g.session_id=?",String.class,session));
            var snapshot=profiles.snapshot();var metadata=profiles.details().stream().collect(java.util.stream.Collectors.toMap(r->(String)r.get("id"),r->r));var rows=new ArrayList<Map<String,Object>>();int chars=0;
            for(var page:snapshot.pages()){
                boolean relevant=page.scope().equals("global")||domains.contains(page.scope())||intent!=null&&aliases.aliasesOf(page.scope()).stream().anyMatch(intent::contains);
                if(!relevant||excluded.contains(page.id()))continue;
                String body=page.content().substring(0,Math.min(page.content().length(),1200));if(chars+body.length()>12000||rows.size()>=12)break;chars+=body.length();
                var row=new LinkedHashMap<String,Object>();row.put("id",page.id());row.put("scope",page.scope());row.put("source",page.source());row.put("revision",snapshot.revisions().get(page.id()));row.put("title",page.title());row.put("content",body);row.put("truncated",body.length()<page.content().length());row.put("expiresAt",metadata.getOrDefault(page.id(),Map.of()).get("expires_at"));rows.add(row);
            }
            return Map.of("state","AVAILABLE","preferences",rows,"excludedProfileIds",excluded.stream().sorted().toList(),"scope","参考偏好，不是本次硬条件或执行权限；与当前用户表达冲突时以当前表达为准");
        }catch(RuntimeException unavailable){return Map.of("state","UNAVAILABLE","preferences",List.of(),"scope","个人偏好暂不可用；不得凭空补全");}
    }
    public void exclude(String session,String profile,boolean excluded,String reason){
        if(reason==null||reason.isBlank()||reason.length()>500)throw new IllegalArgumentException("请说明本目标的偏好例外原因");
        var tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
        tx.executeWithoutResult(status->{String goal=jdbc.queryForObject("SELECT id FROM goals WHERE session_id=? FOR UPDATE",String.class,session);if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM profiles WHERE id=? AND deleted_at IS NULL)",Boolean.class,profile)))throw new IllegalArgumentException("画像不存在");if(excluded)jdbc.update("INSERT INTO goal_profile_exclusions VALUES (?,?,?,?) ON CONFLICT(goal_id,profile_id) DO UPDATE SET reason=EXCLUDED.reason,created_at=EXCLUDED.created_at",goal,profile,reason,System.currentTimeMillis());else jdbc.update("DELETE FROM goal_profile_exclusions WHERE goal_id=? AND profile_id=?",goal,profile);jdbc.update("INSERT INTO events(id,ts,session_id,node,type,title,detail) VALUES (?,?,?,'personal','profile_exception','profile_exception',?)","personal:"+UUID.randomUUID(),System.currentTimeMillis(),session,profile+":"+excluded+":"+reason);});
    }
}
