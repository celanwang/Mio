package org.celanwang.mio.goals;

import org.celanwang.mio.orchestration.*;
import java.util.*;

/** Human-readable delivery over the exact same evidence snapshot used by the workspace. */
public final class GoalDeliveryWriter {
    private GoalDeliveryWriter() {}
    @SuppressWarnings("unchecked")
    public static String render(Map<String,Object> view,GoalProgram program) {
        var goal=(GoalRecord)view.get("goal");var outcome=(GoalOutcome)view.get("outcome");
        var text=new StringBuilder("# 目标准备交付报告\n\n").append(goal.trustedIntent()).append("\n\n").append(outcome.conclusion()).append("\n\n状态：").append(view.get("state")).append("\n来源快照：").append(view.get("sourceHash")).append("\n\n报告确认仅针对准备结果，不批准下单、支付、发布或证明履约。\n");
        if(view.get("synthesis") instanceof GoalSyntheses.Report report) {
            text.append("\n## 综合判断\n\n").append(report.summary()).append("\n\n以下是模型对当前资料的解释，步骤来源可追溯，不替代事实核验。\n");
            for(var finding:report.findings())text.append("\n- ").append(finding.text()).append("（步骤：").append(String.join("、",finding.stepIds())).append("）\n");
            lines(text,"方案取舍",report.tradeoffs());lines(text,"综合发现的待核查问题",report.gaps());lines(text,"建议操作（仍遵循现有授权）",report.nextSteps());
        }
        lines(text,"尚未证明或尚未完成的内容",outcome.gaps());lines(text,"下一步",outcome.nextSteps());
        for(var comparison:(List<Map<String,Object>>)view.getOrDefault("comparisons",List.of()))if(Boolean.TRUE.equals(comparison.get("current"))){
            var result=(GoalComparison.Result)comparison.get("result");text.append("\n## 当前方案比较\n\n").append(result.recommendation()).append("\n").append(result.scope()).append("\n");
            for(var criterion:result.criteria())text.append("\n- 指标 ").append(criterion.fieldId()).append("（").append(criterion.unit()).append("）：").append(criterion.direction()).append("，权重 ").append(criterion.weight()).append(criterion.operator()==null?"":"，硬条件 "+criterion.operator()+" "+criterion.expected());
            for(var candidate:result.candidates()){text.append("\n- ").append(candidate.stepId()).append(candidate.eligible()?"：偏好分 "+candidate.score():"：不可选，"+String.join("；",candidate.reasons()));for(var v:candidate.values())text.append("\n  - ").append(v.fieldId()).append(" = ").append(v.value()).append(' ').append(v.unit()).append("；证据 ").append(v.evidenceId()).append("；事实版本 ").append(v.factVersion());}
            break; // Most recent current comparison is the user's latest explicit preference selection.
        }
        var progress=(Map<String,Object>)view.get("progress");
        for(var step:(List<Map<String,Object>>)progress.get("steps")) {
            text.append("\n\n## ").append(step.get("id")).append(" · ").append(step.get("state")).append("\n\n").append(step.get("reply")).append('\n');
            if(step.get("assessment") instanceof TaskResultAssessment assessment){text.append("\n证据范围：").append(assessment.scope()).append("；核查状态：").append(assessment.state()).append('\n');for(var evidence:assessment.evidence())text.append("\n- 证据 ").append(evidence.evidenceId()).append("；事实 ").append(evidence.factId()).append("；版本 ").append(evidence.version()).append("；有效至 ").append(evidence.expiresAt()).append('\n');if(!assessment.missingFacts().isEmpty())text.append("\n缺失事实：").append(String.join("、",assessment.missingFacts())).append('\n');}
        }
        if(program!=null){text.append("\n## 用户确认的目标条件与范围\n");program.fields().forEach((k,v)->text.append("\n- ").append(k).append("：").append(v.value()));for(var step:program.steps())for(var check:step.checks())text.append("\n- ").append(step.id()).append(" / ").append(check.fieldId()).append(' ').append(check.operator()).append(' ').append(check.expected());text.append("\n\n计划版本：").append(program.revision()).append("；并行上限：").append(program.policy().parallelism()).append("；执行期限：").append(program.policy().until()).append('\n');}
        text.append("\n## 业务资源与来源\n");for(var resource:(List<Map<String,Object>>)view.get("resources"))text.append("\n- ").append(resource.get("provider")).append(" / ").append(resource.get("business_id")).append("：").append(resource.get("state")).append("；来源 ").append(resource.get("source")).append("；依据 ").append(resource.get("evidence")).append('\n');
        return text.toString();
    }
    private static void lines(StringBuilder text,String title,List<String> lines){text.append("\n## ").append(title).append("\n");if(lines.isEmpty())text.append("\n当前快照未记录额外条目。\n");else for(String line:lines)text.append("\n- ").append(line).append('\n');}
}
