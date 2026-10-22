package org.celanwang.mio.orchestration;

import io.agentscope.core.state.*;
import java.util.*;
import java.util.function.Supplier;

/** A per-run, per-SDK-session view. A stale agent cannot overwrite a successor's checkpoint. */
final class FencedAgentStateStore implements AgentStateStore {
    private final AgentStateStore delegate;private final RunBudget budget;private final String session;
    FencedAgentStateStore(AgentStateStore delegate,RunBudget budget,String session){this.delegate=delegate;this.budget=budget;this.session=session;}
    static AgentStateStore scope(AgentStateStore store,RunBudget budget,String session){return budget.lease()==null?store:new FencedAgentStateStore(store,budget,session);}
    private <T> T access(String user,String session,Supplier<T> action){
        if(user!=null&&!user.isEmpty()||!this.session.equals(session))throw new IllegalArgumentException("检查点超出本次执行范围");
        return budget.fenced(action);
    }
    @Override public boolean supportsVersioning(){return delegate.supportsVersioning();}
    @Override public <T extends State> VersionedState<T> getVersioned(String u,String s,String k,Class<T> t){return access(u,s,()->delegate.getVersioned(u,s,k,t));}
    @Override public long saveIfVersion(String u,String s,String k,State v,long expected){
        if(expected<0)throw new IllegalStateException("本次检查点写入必须带版本");
        return access(u,s,()->delegate.saveIfVersion(u,s,k,v,expected));
    }
    @Override public void save(String u,String s,String k,State v){access(u,s,()->{delegate.save(u,s,k,v);return null;});}
    @Override public void save(String u,String s,String k,List<? extends State> v){access(u,s,()->{delegate.save(u,s,k,v);return null;});}
    @Override public <T extends State> Optional<T> get(String u,String s,String k,Class<T> t){return access(u,s,()->delegate.get(u,s,k,t));}
    @Override public <T extends State> List<T> getList(String u,String s,String k,Class<T> t){return access(u,s,()->delegate.getList(u,s,k,t));}
    @Override public boolean exists(String u,String s){return access(u,s,()->delegate.exists(u,s));}
    @Override public Set<String> listSessionIds(String u){return access(u,session,()->delegate.exists(u,session)?Set.of(session):Set.of());}
    @Override public void delete(String u,String s){throw new IllegalStateException("执行期间不能删除检查点");}
    @Override public void delete(String u,String s,String k){throw new IllegalStateException("执行期间不能删除检查点");}
}
