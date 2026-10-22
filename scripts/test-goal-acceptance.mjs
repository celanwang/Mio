import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const source = fs.readFileSync(new URL('../src/main/resources/static/goal-acceptance.js',import.meta.url),'utf8');
class Element {
  constructor(tag) { this.tag=tag;this.children=[];this.textContent='';this.attributes={}; }
  append(...nodes) { this.children.push(...nodes); }
  setAttribute(key,value) { this.attributes[key]=value; }
}
const all = node => [node,...node.children.flatMap(all)];
const text = node => all(node).map(n=>n.textContent).join('\n');
const goal = {id:'g',revision:1,controlRevision:0,state:'ACTIVE'};
const field = {id:'durationSeconds',label:'预计耗时',type:'NUMBER',unit:'秒'};
const candidate = {taskId:'t',objective:'查询报价',contractVersion:'a'.repeat(64),contractId:'ride-quote',scope:'报价有效，不证明创建',fields:[field]};
const view = (overrides={}) => ({goalId:'g',goalRevision:1,revision:1,checkedAt:1000,validUntil:null,state:'SATISFIED',taskRevisions:{t:3},confirmed:null,candidates:[candidate],results:[],blockers:[],...overrides});
function setup(options={}) {
  let now=0;const timers=[];const errors=[];const payloads=[];const root=new Element('root');let panel;
  const context=vm.createContext({performance:{now:()=>now},setTimeout:callback=>{timers.push(callback);return callback;},clearTimeout:()=>{},document:{createElement:tag=>new Element(tag),createTextNode:text=>Object.assign(new Element('#text'),{textContent:text})}});
  vm.runInContext(source,context);
  panel=new context.MioGoalAcceptance({confirm:options.confirm|| (async payload=>payloads.push(payload)),refresh:options.refresh|| (async()=>{}),error:message=>errors.push(message),changed:()=>{root.children=[];panel.render(root,goal);}});
  return {panel,root,errors,payloads,timers,setNow:value=>now=value,render:()=>panel.render(root,goal)};
}
test('older criteria, task revisions and snapshots cannot restore stale success',()=>{
  const {panel}=setup();assert.equal(panel.receive(view(),goal),true);
  assert.equal(panel.receive(view({revision:0,checkedAt:2000}),goal),false);
  assert.equal(panel.receive(view({taskRevisions:{t:2},checkedAt:2000}),goal),false);
  assert.equal(panel.receive(view({taskRevisions:{},checkedAt:2000}),goal),false);
  assert.equal(panel.receive(view({checkedAt:999}),goal),false);
  assert.equal(panel.view.checkedAt,1000);
});
test('expiry subtracts request latency and withdraws the satisfied label before refreshing',async()=>{
  let refreshed=0;const f=setup({refresh:async()=>{refreshed++;}});f.setNow(500);
  f.panel.receive(view({validUntil:2000}),goal,0);f.setNow(1001);f.timers.at(-1)();await Promise.resolve();
  assert.equal(refreshed,1);f.render();assert.match(text(f.root),/证据需重新读取/);assert.doesNotMatch(text(f.root),/验收：已确认的验收范围当前满足/);
});
test('editing survives re-render and sends criteria only after an explicit click',async()=>{
  const f=setup();f.panel.receive(view({state:'NOT_DEFINED'}),goal);f.render();
  const selected=all(f.root).find(n=>n.tag==='input'&&n.type==='checkbox');selected.checked=true;selected.onchange();
  const operator=all(f.root).find(n=>n.tag==='select');operator.value='LTE';operator.onchange();
  const input=all(f.root).find(n=>n.tag==='input'&&n.type==='text');input.value='900';input.oninput();
  assert.equal(f.payloads.length,0);f.root.children=[];f.render();assert.equal(all(f.root).find(n=>n.tag==='input'&&n.type==='text').value,'900');
  await f.panel.submit(false,goal);const payload=JSON.parse(JSON.stringify(f.payloads[0]));
  assert.equal(payload.expectedRevision,1);assert.equal(payload.selections[0].checks[0].expected,'900');
  assert.equal('confirmations' in payload,false);assert.equal('message' in payload,false);assert.equal(f.errors.length,0);
});
test('round-tripping a numeric interval preserves both limits',async()=>{
  const f=setup();const checks=[{fieldId:field.id,operator:'GTE',expected:'600'},{fieldId:field.id,operator:'LTE',expected:'900'}];
  f.panel.receive(view({confirmed:{goalRevision:1,requirements:[{taskId:'t',checks}]}}),goal);f.render();await f.panel.submit(false,goal);
  assert.deepEqual(JSON.parse(JSON.stringify(f.payloads[0].selections[0].checks)),checks);
});
test('a lost confirmation response is read back without retrying the write',async()=>{
  let writes=0,reads=0;let f;
  f=setup({confirm:async()=>{writes++;throw new Error('response lost');},refresh:async()=>{reads++;f.panel.receive(view({revision:2,checkedAt:2000}),goal);}});
  f.panel.receive(view(),goal);f.render();f.panel.draft.rows.get('t').selected=true;await f.panel.submit(false,goal);
  assert.equal(writes,1);assert.equal(reads,1);assert.equal(f.panel.view.revision,2);assert.match(f.errors[0],/response lost/);
});
test('late failure from a different chat cannot alter the current acceptance editor',async()=>{
  let reject;const f=setup({confirm:()=>new Promise((_,failure)=>{reject=failure;})});f.panel.receive(view(),goal);f.render();f.panel.draft.rows.get('t').selected=true;
  const pending=f.panel.submit(false,goal);f.panel.reset();const next={...goal,id:'other'};f.panel.receive(view({goalId:'other'}),next);
  reject(new Error('old failure'));await pending;assert.equal(f.errors.length,0);assert.equal(f.panel.view.goalId,'other');assert.equal(f.panel.busy,false);
});
test('closed goals display evidence but expose no save or clear action',()=>{
  const f=setup();f.panel.receive(view(),goal);f.panel.render(f.root,{...goal,state:'CLOSED'});
  assert.doesNotMatch(text(f.root),/确认这些验收条件|清除验收条件/);assert.match(text(f.root),/已确认的验收范围当前满足/);
});
test('the result comparison includes its registered unit',()=>{
  const f=setup();f.panel.receive(view({results:[{taskId:'t',objective:'查询报价',state:'SATISFIED',scope:'报价有效',evidence:[],checks:[{fieldId:field.id,label:'预计耗时',operator:'LTE',expected:'900',actual:'900',matched:true}]}]}),goal);
  f.render();assert.match(text(f.root),/预计耗时（秒）：当前 900/);
});
test('starting a mutation and an old resource-only snapshot both withdraw success',()=>{
  const f=setup();f.panel.receive(view(),goal);f.panel.invalidate();f.render();assert.match(text(f.root),/证据需重新读取/);
  f.root.children=[];f.panel.receive(view({checkedAt:2000}),goal);f.setNow(30001);f.render();assert.match(text(f.root),/证据需重新读取/);
});
