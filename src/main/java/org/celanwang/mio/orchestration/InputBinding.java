package org.celanwang.mio.orchestration;

/** The planner selects an existing fact and pins its version; it cannot supply a fact value. */
public record InputBinding(String taskId,String factId,String version,String alias) {
    public InputBinding {
        if(taskId==null||taskId.isBlank()||factId==null||factId.isBlank()||version==null||version.isBlank()
                ||taskId.length()>200||factId.length()>300||version.length()>100
                ||alias==null||!alias.matches("[a-z][a-zA-Z0-9_]{0,63}"))throw new IllegalArgumentException("事实输入绑定无效");
    }
}
