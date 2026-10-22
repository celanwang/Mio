package org.celanwang.mio.orchestration;

import java.util.function.Supplier;

/** One bounded foreground owner per conversation. Tokens are server-owned, never model parameters. */
public interface ExecutionLeaseStore {
    record Token(String resource,String owner,long generation,long expiresAt) {}
    static String resource(String session){return JsonFingerprint.hash(java.util.Map.of("kind","foreground-session","session",session));}
    Token acquire(String session,long durationMillis);
    boolean valid(Token token);
    default void require(Token token){if(!valid(token))throw new LostLease();}
    <T> T fenced(Token token,Supplier<T> action);
    void release(Token token);
    final class LostLease extends IllegalStateException {public LostLease(){super("本轮执行租约已失效，已停止后续执行");}}
    final class Busy extends IllegalStateException {public Busy(){super("当前对话已有执行中的请求");}}
}
