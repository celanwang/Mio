package org.celanwang.mio.orchestration;

import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;

/** Test double only. Production requires PostgreSQL clock, locks and transaction rollback. */
final class InMemoryExecutionLeaseStore implements ExecutionLeaseStore {
    private final Clock clock;final Map<String,Token> tokens=new HashMap<>();
    InMemoryExecutionLeaseStore(Clock clock){this.clock=clock;}
    @Override public synchronized Token acquire(String session,long duration){
        String resource=ExecutionLeaseStore.resource(session);var old=tokens.get(resource);if(old!=null&&old.owner()!=null&&old.expiresAt()>clock.millis())throw new Busy();
        var token=new Token(resource,UUID.randomUUID().toString(),old==null?1:old.generation()+1,clock.millis()+duration);tokens.put(resource,token);return token;
    }
    @Override public synchronized boolean valid(Token token){return token!=null&&token.owner()!=null&&token.equals(tokens.get(token.resource()))&&clock.millis()<token.expiresAt();}
    @Override public synchronized <T> T fenced(Token token,Supplier<T> action){require(token);var result=action.get();require(token);return result;}
    @Override public synchronized void release(Token token){if(token.equals(tokens.get(token.resource())))tokens.put(token.resource(),new Token(token.resource(),null,token.generation(),0));}
}
