package org.celanwang.mio.memory.jobs;

/** Durable work identity. Payload contains no raw chat text; result checkpoints stay server-side. */
public record MemoryJob(String id,String messageId,Kind kind,long sequence,State state,int attempts,long generation,
                        String owner,long leaseUntil,long nextAttemptAt,String result,String errorCode,long createdAt,long updatedAt) {
    public enum Kind { DISTILL,INDEX }
    public enum State { READY,RUNNING,RETRY,DONE,DEAD }
    public record Claim(String id,String messageId,Kind kind,long sequence,int attempt,long generation,String owner,long leaseUntil,String result) {}
    public record View(String id,Kind kind,State state,int attempts,long nextAttemptAt,String errorCode,long createdAt,long updatedAt,boolean resultSaved) {}
    public View view(){return new View(id,kind,state,attempts,nextAttemptAt,errorCode,createdAt,updatedAt,result!=null);}
    public Claim claim(){return new Claim(id,messageId,kind,sequence,attempts,generation,owner,leaseUntil,result);}
    public static final class LostClaim extends IllegalStateException {public LostClaim(){super("记忆任务执行代次已失效");}}
}
