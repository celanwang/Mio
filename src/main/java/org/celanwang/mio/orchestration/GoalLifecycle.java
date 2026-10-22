package org.celanwang.mio.orchestration;

/** Explicit user control, never exposed as a model tool. No budget reset or business-result override. */
public interface GoalLifecycle {
    GoalRecord transition(String session,long expectedControlRevision,GoalRecord.State target);
}
