package org.celanwang.mio.orchestration;

/** A tool's text reply is not proof of a committed business effect. */
public record OperationRecord(String id, String callId, String tool, String inputHash, long startedAt,
                              String phase, String output) {}
