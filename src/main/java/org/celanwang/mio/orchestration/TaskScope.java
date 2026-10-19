package org.celanwang.mio.orchestration;

/** PREPARE cannot submit writes, even if a model or Jev asks to do so. */
public enum TaskScope { PREPARE, REQUEST_APPROVAL }
