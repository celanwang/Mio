import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
class Element {
  constructor(tag,text=''){this.tag=tag;this.textContent=text;this.children=[];this.value='';}
  append(...nodes){this.children.push(...nodes);if(this.tag==='select'&&!this.value&&nodes[0]?.value)this.value=nodes[0].value;}
  replaceChildren(...nodes){this.children=[...nodes];this.value=nodes[0]?.value||'';}
  contains(node){return this===node||this.children.some(child=>child.contains?.(node));}
  blur(){}
}
const all=node=>[node,...node.children.flatMap(all)];
function setup(file,handler){const root=new Element('root'),calls=[];let id=0,current='session';const context=vm.createContext({el:(tag,text)=>new Element(tag,text),input:(value,placeholder)=>Object.assign(new Element('input'),{value,placeholder}),session:()=>current,base:()=>'/api/goals/'+current,workspaceBase:()=>'/api/workspace/goals/'+current,api:async(path,method='GET',body)=>{calls.push({path,method,body});return handler(path,method,body);},notice:()=>{},load:async()=>{},workbench:async()=>{},crypto:{randomUUID:()=> 'request'+(++id)},document:{body:{insertBefore:node=>root.append(node)},querySelector:()=>null}});vm.runInContext(fs.readFileSync(new URL('../src/main/resources/static/'+file,import.meta.url),'utf8'),context);return {context,root,calls,setSession:value=>{current=value;},run:code=>vm.runInContext(code,context)};}
test('selecting a preparation never approves or executes before separate user actions',async()=>{
  let chosen=false,requested=false,approved=false;const f=setup('outcomes.js',(path,method)=>{
    if(path.endsWith('/program'))return {goal:{sessionId:'session',revision:2},program:{revision:3,steps:[{id:'candidate',objective:'prepared option'}],policy:{until:Date.now()+60000}},evidenceHash:'a'.repeat(64)};
    if(path.endsWith('/selections')&&method==='POST'){chosen=true;return {id:'selection'};}
    if(path.endsWith('/execute')){requested=true;return {};}
    if(path.endsWith('/approve')){approved=true;return {};}
    if(path.includes('/api/tasks?'))return {pendingConfirmations:requested&&!approved?[{taskId:'task',toolCallId:'opaque-call',name:'create-order',description:'actual operation',input:{amount:10}}]:[]};
    if(path.endsWith('/feedback')||path.endsWith('/triggers'))return [];
    return chosen?[{id:'selection',step_id:'candidate',instruction:'explicit action',state:requested?'EXECUTION_REQUESTED':'SELECTED',execution:requested?{id:'task',status:approved?'COMPLETED':'AWAITING_APPROVAL',reply:'pending',operations:[]}:null}]:[];
  });
  await f.run('selectionLoad.onclick()');f.run("selectionInstruction.value='explicit action'");await f.run('selectionChoose.onclick()');
  assert.equal(chosen,true);assert.equal(requested,false);assert.equal(approved,false);
  await all(f.root).find(n=>n.textContent==='按本次要求创建执行任务').onclick();assert.equal(requested,true);assert.equal(approved,false);
  await all(f.root).find(n=>n.textContent==='批准此具体调用').onclick();assert.equal(approved,true);
  const approval=f.calls.find(c=>c.path.endsWith('/approve'));assert.deepEqual(JSON.parse(JSON.stringify(approval.body)),{confirmations:[{toolCallId:'opaque-call',approved:true}]});
});
test('profile correction submits the displayed version and never creates tool permission',async()=>{
  const f=setup('personal.js',path=>path.endsWith('/details')?[{id:'quiet',title:'环境',scope:'global',content:'安静',source:'inferred',revision:7,expires_at:null}]:path.endsWith('/preferences')?{excludedProfileIds:[]}:[]);
  await f.run('personalRead.onclick()');all(f.root).find(n=>n.textContent==='查看并纠正').onclick();f.run("personalText.value='本周喜欢热闹';personalReason.value='本人纠正'");await f.run('personalSave.onclick()');
  const mutation=f.calls.find(c=>c.method==='PUT');assert.equal(mutation.body.expectedRevision,7);assert.equal(mutation.body.reason,'本人纠正');assert.equal(mutation.path,'/api/profile');assert.equal(f.calls.some(c=>c.path.includes('/approve')),false);
});
test('resource notifications confirm bounded scope without requesting a business task',async()=>{
  const f=setup('outcomes.js',path=>path.endsWith('/program')?{goal:{revision:2},program:{revision:3,policy:{until:Date.now()+60000}}}:[]);
  f.run("triggerKind.value='RESOURCE_CHANGED';triggerAction.value='NOTIFY'");await f.run('triggerCreate.onclick()');const post=f.calls.find(c=>c.method==='POST');
  assert.equal(post.path,'/api/workspace/goals/session/triggers');assert.equal(post.body.expectedRevision,3);assert.equal(post.body.action,'NOTIFY');assert.equal(post.body.cooldownSeconds,300);assert.equal(post.body.maxActivations,5);assert.equal(f.calls.some(c=>c.path.includes('/execute')),false);
});

test('switching goals while a plan is loading cannot install a trigger on another goal',async()=>{
  let resolve;const f=setup('outcomes.js',path=>path.endsWith('/program')?new Promise(done=>{resolve=done;}):[]);
  const pending=f.run('triggerCreate.onclick()');await Promise.resolve();f.setSession('new-session');resolve({goal:{revision:2},program:{revision:3,policy:{until:Date.now()+60000}}});await pending;
  assert.equal(f.calls.some(c=>c.method==='POST'),false);
});
test('an old preference exception button cannot mutate the newly opened goal',async()=>{
  const f=setup('personal.js',path=>path.endsWith('/details')?[{id:'quiet',title:'环境',scope:'global',content:'安静',source:'inferred',revision:7}]:{excludedProfileIds:[]});
  await f.run('personalRead.onclick()');const button=all(f.root).find(n=>n.textContent==='本目标暂不使用');f.run("personalReason.value='本次例外'");f.setSession('new-session');await button.onclick();
  assert.equal(f.calls.some(c=>c.method==='POST'),false);
});
