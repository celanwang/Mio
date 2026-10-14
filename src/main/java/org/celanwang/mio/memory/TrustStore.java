package org.celanwang.mio.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 信任规则统计（trusts 表）：记录人工对工具调用的批准/拒绝次数，UPSERT 累加。
 * 失败方向 fail-closed：读写失败记 error 并按空表处理，绝不据此放行任何操作。
 */
@Component
public class TrustStore {

    private static final Logger log = LoggerFactory.getLogger(TrustStore.class);

    private final JdbcTemplate jdbc;

    public TrustStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 记录一次人工确认结果；只有人工确认才计入统计，Jev 自动放行不算用户授权。 */
    public void record(String toolName, Map<String, Object> input, boolean approved) {
        try {
            jdbc.update("""
                    INSERT INTO trusts(tool_name, param_signature, approvals, rejections, last_decision_at)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (tool_name, param_signature) DO UPDATE SET
                        approvals        = trusts.approvals + EXCLUDED.approvals,
                        rejections       = trusts.rejections + EXCLUDED.rejections,
                        last_decision_at = EXCLUDED.last_decision_at
                    """, toolName, signature(toolName, input),
                    approved ? 1 : 0, approved ? 0 : 1, System.currentTimeMillis());
        } catch (Exception e) {
            log.error("信任统计记录失败（fail-closed，不影响本次结果）：{}", e.getMessage());
        }
    }

    /** 该工具是否有任何人工确认记录（首见轨标记用）；查询失败按无记录处理，倒向首见提示。 */
    public boolean hasRecords(String toolName) {
        try {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(1) FROM trusts WHERE tool_name = ?", Integer.class, toolName);
            return count != null && count > 0;
        } catch (Exception e) {
            log.error("信任统计查询失败（fail-closed，按首见处理）：{}", e.getMessage());
            return false;
        }
    }

    public List<TrustRule> rules() {
        try {
            return jdbc.query("""
                    SELECT tool_name, param_signature, approvals, rejections, last_decision_at
                    FROM trusts ORDER BY tool_name, param_signature
                    """, (rs, rowNum) -> new TrustRule(rs.getString("tool_name"),
                    rs.getString("param_signature"), rs.getInt("approvals"),
                    rs.getInt("rejections"), rs.getLong("last_decision_at")));
        } catch (Exception e) {
            log.error("信任统计查询失败（fail-closed，按空表处理）：{}", e.getMessage());
            return List.of();
        }
    }

    /** 重置某工具的全部信任统计。 */
    public void reset(String toolName) {
        try {
            jdbc.update("DELETE FROM trusts WHERE tool_name = ?", toolName);
        } catch (Exception e) {
            log.error("信任统计重置失败（fail-closed）：{}", e.getMessage());
        }
    }

    /** 参数骨架：工具名 + 排序后的参数 key 列表（不含值）。 */
    static String signature(String toolName, Map<String, Object> input) {
        String keys = input == null ? "" : String.join(",", new TreeSet<>(input.keySet()));
        return toolName + "[" + keys + "]";
    }
}
