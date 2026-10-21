package org.celanwang.mio.orchestration;

public enum TaskStatus {
    WAITING_DEPENDENCY, RUNNING, NEEDS_INPUT, INPUT_READY, AWAITING_APPROVAL, COMPLETED, FAILED, UNKNOWN, CANCELLED, STALE;
    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == UNKNOWN || this == CANCELLED || this == STALE;
    }
}
