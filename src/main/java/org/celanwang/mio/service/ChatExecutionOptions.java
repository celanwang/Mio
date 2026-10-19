package org.celanwang.mio.service;

/** Delegated instructions are never promoted to user memory or used as the authorization source. */
public record ChatExecutionOptions(String trustedIntent, boolean recordMemory, Runnable beforeReview) {
    public static final ChatExecutionOptions USER_CONVERSATION=new ChatExecutionOptions(null,true,()->{});
    public ChatExecutionOptions { java.util.Objects.requireNonNull(beforeReview); }
}
