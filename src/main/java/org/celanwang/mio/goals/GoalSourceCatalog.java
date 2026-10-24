package org.celanwang.mio.goals;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.celanwang.mio.orchestration.*;
import java.util.*;

/** Exact references available to a synthesis call, bounded before networking. */
public final class GoalSourceCatalog {
    private GoalSourceCatalog(){}
    public static Map<String,Map<String,Object>> capture(String session,List<Map<String,Object>> steps,TaskRuntime runtime,List<Map<String,Object>> documents,ObjectMapper json){
        var catalog=new LinkedHashMap<String,Map<String,Object>>();int chars=0;
        for(var source:documents)if(source.get("state").equals("CURRENT")&&catalog.size()<8){var copy=new LinkedHashMap<>(source);copy.remove("goal_id");catalog.put((String)source.get("referenceId"),copy);chars+=source.get("quote").toString().length();}
        for(var step:steps){String taskId=(String)step.get("taskId");if(taskId==null||taskId.isBlank())continue;var task=runtime.require(session,taskId);String report=task.reply();
            for(int start=0;start<Math.min(report.length(),2000)&&chars<30000&&catalog.size()<64;start+=1000){int end=Math.min(start+1000,report.length());String quote=report.substring(start,end),id="r_"+JsonFingerprint.hash(Map.of("task",task.id(),"revision",task.revision(),"start",start,"end",end,"quote",quote)).substring(0,24);catalog.put(id,Map.of("type","TASK_REPORT","taskId",task.id(),"stepId",step.get("id"),"taskRevision",task.revision(),"startOffset",start,"endOffset",end,"quote",quote,"scope","未核验报告片段，不能作为业务成功自证"));chars+=quote.length();}
            boolean current=task.goalRevision()==runtime.goal(session).revision()&&task.status()==TaskStatus.COMPLETED&&Set.of(TaskResultAssessment.State.VERIFIED,TaskResultAssessment.State.EXECUTION_ONLY).contains(runtime.summary(task).resultAssessment().state());
            for(var fact:(current?TaskFacts.validAt(task,System.currentTimeMillis()):List.<TaskFact>of()).stream().limit(2).toList()){String quote;try{quote=json.writeValueAsString(fact.value());}catch(Exception invalid){continue;}if(quote.length()>3000||chars+quote.length()>32000||catalog.size()>=64)continue;String id="f_"+JsonFingerprint.hash(Map.of("fact",fact.id(),"version",fact.version())).substring(0,24);catalog.put(id,Map.of("type","TRUSTED_FACT","taskId",task.id(),"stepId",step.get("id"),"factId",fact.id(),"factVersion",fact.version(),"evidenceId",fact.evidenceId(),"observedAt",fact.observedAt(),"expiresAt",fact.expiresAt(),"quote",quote));chars+=quote.length();}
        }
        return catalog;
    }
}
