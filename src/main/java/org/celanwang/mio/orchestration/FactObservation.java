package org.celanwang.mio.orchestration;

/** Durable read evidence and invalidation marker. Null fact means not verified, never a cached success. */
public record FactObservation(String id,String key,String source,String tool,String inputHash,long startedAt,
                              String evidenceId,String output,TaskFact fact) {
    public FactObservation {
        if(id==null||id.isBlank()||id.length()>200||key==null||!key.matches("[a-z][a-z0-9_.]{0,127}")
                ||source==null||source.isBlank()||source.length()>128||tool==null||tool.isBlank()||tool.length()>128||inputHash==null||inputHash.isBlank())throw new IllegalArgumentException("事实观测缺少来源");
        evidenceId=evidenceId==null?"":evidenceId;output=output==null?"":output;
        if(output.length()>16000)throw new IllegalArgumentException("事实证据摘要过长");
        if(fact!=null&&(!fact.id().equals(id)||!fact.key().equals(key)||!fact.evidenceId().equals(evidenceId)))throw new IllegalArgumentException("事实与证据不匹配");
    }
    public FactObservation returned(TaskRecord task,String raw,FactExtractor.Value value,long now){
        String evidence=JsonFingerprint.hash(java.util.Map.of("source",source,"tool",tool,"inputHash",inputHash,"raw",raw));
        TaskFact checked=null;
        if(value!=null&&value.expiresAt()>now){
            String version=JsonFingerprint.hash(java.util.Map.of("id",id,"evidence",evidence,"type",value.type(),"value",value.value(),"expiresAt",value.expiresAt()));
            checked=new TaskFact(id,key,value.type(),value.value(),task.id(),evidence,version,task.goalRevision(),now,value.expiresAt());
        }
        return new FactObservation(id,key,source,tool,inputHash,startedAt,evidence,raw.substring(0,Math.min(raw.length(),16000)),checked);
    }
}
