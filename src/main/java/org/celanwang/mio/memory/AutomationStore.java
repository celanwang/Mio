package org.celanwang.mio.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * 自动化规则存储（automations 表）：习惯提议 → 用户批准 → 生效。
 * 失败方向 fail-closed：读写失败记 error 并按无规则处理，绝不凭空放行自动化。
 */
@Component
public class AutomationStore {

    private static final Logger log = LoggerFactory.getLogger(AutomationStore.class);

    /** 本轮唯一支持的触发方式：会话开始时由 Agent 执行（不引入定时调度器）。 */
    public static final String TRIGGER_SESSION_START = "session-start";

    private final JdbcTemplate jdbc;

    public AutomationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 幂等提议：同 toolName 已有任何状态的规则则不再生成。返回新建的规则，未新建返回 null。 */
    public synchronized AutomationRule propose(String toolName,String title,String reason){
        if(!org.celanwang.mio.config.ToolPolicies.AUTOMATABLE.contains(toolName))return null;
        try{return transaction().execute(status->{
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext('mio-automation'),hashtext(?))",Object.class,toolName);
            if(jdbc.queryForObject("SELECT count(*) FROM automations WHERE tool_name=?",Integer.class,toolName)>0)return null;
            var rule=new AutomationRule(UUID.randomUUID().toString(),title,toolName,TRIGGER_SESSION_START,AutomationRule.Status.PROPOSED,reason,System.currentTimeMillis(),null);
            jdbc.update("INSERT INTO automations(id,title,tool_name,trigger_kind,status,reason,created_at) VALUES (?,?,?,?,?,?,?)",rule.id(),rule.title(),rule.toolName(),rule.trigger(),rule.status().name(),rule.reason(),rule.createdAt());audit("automation_proposed",rule.id()+":"+toolName);return rule;
        });}catch(RuntimeException unavailable){log.error("自动化提议存储暂不可用，未登记提议");return null;}
    }

    /** 批准：PROPOSED → ACTIVE。 */
    public AutomationRule approve(String id) {
        return decide(id, AutomationRule.Status.ACTIVE);
    }

    /** 拒绝：PROPOSED → REJECTED，不再重复提议。 */
    public AutomationRule reject(String id) {
        return decide(id, AutomationRule.Status.REJECTED);
    }

    /** 吊销：ACTIVE → REJECTED（保留记录，防止习惯检测立即再次提议）。 */
    public AutomationRule revoke(String id) {
        return transition(id,AutomationRule.Status.ACTIVE,AutomationRule.Status.REJECTED);
    }

    public synchronized int revokeAllActive() {
        return transaction().execute(status->{int changed=jdbc.update("UPDATE automations SET status='REJECTED',decided_at=? WHERE status='ACTIVE'",System.currentTimeMillis());if(changed>0)audit("automation_all_revoked", "count="+changed);return changed;});
    }

    public List<AutomationRule> rules() {
        return query("SELECT id, title, tool_name, trigger_kind, status, reason, created_at, decided_at"
                + " FROM automations ORDER BY created_at");
    }

    public List<AutomationRule> pending() {
        return queryByStatus(AutomationRule.Status.PROPOSED);
    }

    public List<AutomationRule> active() {
        return queryByStatus(AutomationRule.Status.ACTIVE);
    }

    private List<AutomationRule> queryByStatus(AutomationRule.Status status) {
        return query("SELECT id, title, tool_name, trigger_kind, status, reason, created_at, decided_at"
                + " FROM automations WHERE status = '" + status.name() + "' ORDER BY created_at");
    }

    private List<AutomationRule> query(String sql) {
        try {
            return jdbc.query(sql, this::mapRow);
        } catch (Exception e) {
            log.error("自动化规则查询失败（fail-closed，按无规则处理）：{}", e.getMessage());
            return List.of();
        }
    }

    private AutomationRule find(String id) {
        try {
            List<AutomationRule> found = jdbc.query(
                    "SELECT id, title, tool_name, trigger_kind, status, reason, created_at, decided_at"
                            + " FROM automations WHERE id = ?", this::mapRow, id);
            return found.isEmpty() ? null : found.get(0);
        } catch (Exception e) {
            log.error("自动化规则查询失败（fail-closed，按不存在处理）：{}", e.getMessage());
            return null;
        }
    }

    private AutomationRule decide(String id,AutomationRule.Status status){return transition(id,AutomationRule.Status.PROPOSED,status);}
    private AutomationRule transition(String id,AutomationRule.Status expected,AutomationRule.Status target){
        return transaction().execute(status->{
            var rows=jdbc.query("UPDATE automations SET status=?,decided_at=? WHERE id=? AND status=? RETURNING id,title,tool_name,trigger_kind,status,reason,created_at,decided_at",this::mapRow,target.name(),System.currentTimeMillis(),id,expected.name());
            if(rows.isEmpty())return null;var rule=rows.get(0);audit("automation_state_changed",id+":"+expected+"->"+target);return rule;
        });
    }
    private org.springframework.transaction.support.TransactionTemplate transaction(){return new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(java.util.Objects.requireNonNull(jdbc.getDataSource())));}
    private void audit(String type,String detail){jdbc.update("INSERT INTO events(id,ts,session_id,node,type,title,detail) VALUES (?,?, 'automations','human',?,?,?)","automation:"+UUID.randomUUID(),System.currentTimeMillis(),type,type,detail);}

    private AutomationRule mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        long decidedAt = rs.getLong("decided_at");
        // wasNull 必须在读 decided_at 之后、读其他列之前调用。
        Long decidedAtOrNull = rs.wasNull() ? null : decidedAt;
        return new AutomationRule(rs.getString("id"), rs.getString("title"), rs.getString("tool_name"),
                rs.getString("trigger_kind"), AutomationRule.Status.valueOf(rs.getString("status")),
                rs.getString("reason"), rs.getLong("created_at"), decidedAtOrNull);
    }
}
