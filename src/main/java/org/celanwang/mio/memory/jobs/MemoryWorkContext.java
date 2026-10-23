package org.celanwang.mio.memory.jobs;

/** Lexically scoped background billing context; always removed before executor threads are reused. */
public final class MemoryWorkContext implements AutoCloseable {
    private static final ThreadLocal<MemoryWorkContext> CURRENT=new ThreadLocal<>();
    private final MemoryWorkContext previous;private final MemoryCallBudget budget;private final MemoryJob.Claim claim;
    private MemoryWorkContext(MemoryCallBudget budget,MemoryJob.Claim claim){this.previous=CURRENT.get();this.budget=budget;this.claim=claim;CURRENT.set(this);}
    public static MemoryWorkContext open(MemoryCallBudget budget,MemoryJob.Claim claim){return new MemoryWorkContext(java.util.Objects.requireNonNull(budget),claim);}
    public static MemoryCallBudget.Call begin(MemoryCallBudget.Kind kind,int chars){var current=CURRENT.get();if(current==null)throw new IllegalStateException("后台模型调用缺少用量归属");return current.budget.begin(kind,chars,current.claim);}
    public static boolean active(){return CURRENT.get()!=null;}
    @Override public void close(){if(previous==null)CURRENT.remove();else CURRENT.set(previous);}
    private MemoryWorkContext(){throw new AssertionError();}
}
