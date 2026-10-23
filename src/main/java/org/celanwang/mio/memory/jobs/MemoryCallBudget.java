package org.celanwang.mio.memory.jobs;

/** Reservations happen before provider I/O. Missing or abandoned usage is never treated as zero cost. */
public interface MemoryCallBudget {
    enum Kind { MODEL,EMBEDDING }
    interface Call {void settle(Long tokens,boolean complete);}
    Call begin(Kind kind,int inputChars,MemoryJob.Claim claim);
    final class Exhausted extends IllegalStateException {public Exhausted(){super("记忆后台当日调用预算已耗尽");}}
    final class InputTooLarge extends IllegalArgumentException {public InputTooLarge(){super("单次后台输入超过整日输入额度");}}
}
