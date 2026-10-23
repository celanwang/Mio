package org.celanwang.mio.goals;

import org.celanwang.mio.orchestration.*;
import java.util.*;

/** Bounded planning evidence. Task prose remains untrusted, separate from actual user intent. */
public final class GoalDecisionContext {
    private GoalDecisionContext() {}
    public static Map<String,Object> capture(GoalProgram program,List<TaskRecord> tasks,AgentRegistry registry) {
        if(program==null)return Map.of("steps",List.of(),"fields",Map.of());
        var indexed=new HashMap<String,TaskRecord>();tasks.forEach(t->indexed.put(t.spec().key(),t));
        var steps=new ArrayList<Map<String,Object>>();long now=System.currentTimeMillis();
        for(var step:program.steps()) {
            var row=new LinkedHashMap<String,Object>();row.put("definition",step);row.put("taskKey",program.taskKey(step));
            var task=indexed.get(program.taskKey(step));
            if(task==null)row.put("state","WAITING");else{
                row.put("taskId",task.id());row.put("taskRevision",task.revision());row.put("state",task.status());
                row.put("report",clip(task.reply(),1200));row.put("reportTruncated",task.reply().length()>1200);
                row.put("assessment",TaskResultAssessment.current(task,registry,now));
                row.put("facts",TaskFacts.validAt(task,now).stream().limit(8).toList());row.put("unresolvedEffects",task.unresolvedEffects());
            }
            steps.add(row);
        }
        return Map.of("programRevision",program.revision(),"goalRevision",program.goalRevision(),"fields",program.fields(),"policy",program.policy(),"steps",steps,"trust","UNTRUSTED_REPORTS_WITH_SEPARATE_STRUCTURED_ASSESSMENTS");
    }
    public static String clip(String value,int maximum){return value.length()>maximum?value.substring(0,maximum):value;}
}
