package org.celanwang.mio.orchestration;

/** A real human answer to one versioned question. It grants no authority to other tasks. */
public record TaskAnswer(String questionId,String question,String text,long answeredAt,long goalRevision) {
    public TaskAnswer(String questionId,String question,String text,long answeredAt){this(questionId,question,text,answeredAt,0);}
    public TaskAnswer {
        if(questionId==null||!questionId.matches("[a-f0-9]{64}")||question==null||question.length()>2000
                ||text==null||text.isBlank()||text.length()>10000||goalRevision<0)throw new IllegalArgumentException("任务回答无效");
    }
}
