package org.celanwang.mio.orchestration;

import java.math.BigDecimal;
import java.util.*;

/** Explicit user-confirmed criteria. This record never grants tool authority or changes intent. */
public record GoalAcceptance(int schemaVersion,String goalId,long goalRevision,long revision,long confirmedAt,List<Requirement> requirements) {
    public enum Operator { EQ,LTE,GTE }
    public record Check(String fieldId,Operator operator,String expected) {
        public Check {
            if(fieldId==null||!fieldId.matches("[a-z][a-zA-Z0-9_]{0,63}")||operator==null||expected==null||expected.isBlank()||expected.length()>500)
                throw new IllegalArgumentException("验收字段条件无效");
        }
        public void validate(CapabilityOutputContract.Field field) {
            if(field.type()==CapabilityOutputContract.ValueType.NUMBER)number(expected);
            else if(operator!=Operator.EQ)throw new IllegalArgumentException("文本字段只支持相等比较");
        }
        public boolean matches(CapabilityOutputContract.Field field,String actual) {
            if(actual==null)return false;
            if(field.type()==CapabilityOutputContract.ValueType.TEXT)return actual.equals(expected);
            int compared=number(actual).compareTo(number(expected));
            return switch(operator){case EQ->compared==0;case LTE->compared<=0;case GTE->compared>=0;};
        }
    }
    public record Selection(String taskId,String contractVersion,List<Check> checks) {
        public Selection {
            validateTask(taskId);if(contractVersion==null||!contractVersion.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("缺少输出契约版本");
            checks=validChecks(checks);
        }
    }
    public record Request(String sessionId,Long expectedGoalRevision,Long expectedControlRevision,Long expectedRevision,List<Selection> selections) {
        public Request {
            if(sessionId==null||sessionId.isBlank()||sessionId.length()>200||expectedGoalRevision==null||expectedControlRevision==null||expectedRevision==null||expectedGoalRevision<1||expectedControlRevision<0||expectedRevision<0)
                throw new IllegalArgumentException("验收确认缺少有效的目标及版本");
            if(selections==null)throw new IllegalArgumentException("请明确提供验收选择；清除条件需显式传入空列表");
            selections=List.copyOf(selections);
            if(selections.size()>8||selections.stream().map(Selection::taskId).distinct().count()!=selections.size())throw new IllegalArgumentException("最多选择八个不同任务作为验收条件");
        }
    }
    public record Requirement(String taskId,String capabilityVersion,String contractId,String contractVersion,List<Check> checks) {
        public Requirement {
            validateTask(taskId);
            if(capabilityVersion==null||capabilityVersion.isBlank()||contractId==null||!contractId.matches("[a-z][a-z0-9-]{0,63}")
                    ||contractVersion==null||!contractVersion.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("验收要求缺少可信契约版本");
            checks=validChecks(checks);
        }
    }
    public GoalAcceptance {
        if(schemaVersion!=1||goalId==null||goalId.isBlank()||goalRevision<1||revision<1||confirmedAt<0)throw new IllegalArgumentException("目标验收记录版本无效");
        requirements=List.copyOf(requirements);
        if(requirements.size()>8||requirements.stream().map(Requirement::taskId).distinct().count()!=requirements.size())throw new IllegalArgumentException("目标验收任务无效");
    }
    static BigDecimal number(String text) {
        if(text==null||text.length()>80||!text.matches("-?(0|[1-9][0-9]{0,29})(\\.[0-9]{1,12})?"))throw new IllegalArgumentException("数值条件必须是有限十进制数，最多三十位整数和十二位小数");
        return new BigDecimal(text);
    }
    private static void validateTask(String id){if(id==null||id.isBlank()||id.length()>200)throw new IllegalArgumentException("验收任务编号无效");}
    private static List<Check> validChecks(List<Check> checks) {
        var copy=checks==null?List.<Check>of():List.copyOf(checks);
        if(copy.size()>8||copy.stream().map(c->c.fieldId()+":"+c.operator()).distinct().count()!=copy.size())throw new IllegalArgumentException("验收字段条件过多或重复");
        return copy;
    }
}
