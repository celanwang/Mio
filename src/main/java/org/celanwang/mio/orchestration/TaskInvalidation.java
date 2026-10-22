package org.celanwang.mio.orchestration;

/** Runtime-derived cause, separate from historical execution and provider outcomes. */
public record TaskInvalidation(Code code,String sourceTaskId,String rootTaskId,String factId,String alias,long detectedAt) {
    public enum Code { DEPENDENCY_MISSING,DEPENDENCY_SCOPE,DEPENDENCY_FAILED,DEPENDENCY_NOT_COMPLETE,DEPENDENCY_INVALID,
        DEPENDENCY_CYCLE,FACT_MISSING,FACT_CHANGED,FACT_EXPIRED,FACT_TIME_INVALID }
    public TaskInvalidation {
        if(code==null||sourceTaskId==null||rootTaskId==null||factId==null||alias==null)throw new IllegalArgumentException("失效原因缺少来源");
    }
    public String message(){return switch(code){
        case FACT_EXPIRED->"绑定的事实已过期";
        case FACT_CHANGED->"绑定的事实版本已改变";
        case FACT_MISSING->"绑定的事实缺失或证据不再有效";
        case FACT_TIME_INVALID->"事实观测时间无效";
        case DEPENDENCY_FAILED->"依赖任务失败、取消或结果待核查";
        case DEPENDENCY_NOT_COMPLETE->"依赖任务尚未完成";
        case DEPENDENCY_INVALID->"上游结果已经失效";
        case DEPENDENCY_SCOPE->"依赖不属于相同会话与目标版本";
        case DEPENDENCY_CYCLE->"依赖存在循环";
        case DEPENDENCY_MISSING->"依赖任务不存在";
    };}
}
