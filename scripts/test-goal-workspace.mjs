import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const source=fs.readFileSync(new URL('../src/main/resources/static/workbench.js',import.meta.url),'utf8');
class Element {
  constructor(tag,text=''){this.tag=tag;this.textContent=text;this.children=[];this.disabled=false;}
  append(...nodes){this.children.push(...nodes);}
  replaceChildren(...nodes){this.children=[...nodes];}
  contains(node){return this===node||this.children.some(child=>child.contains?.(node));}
  blur(){}
}
const all=node=>[node,...node.children.flatMap(all)];
const view=()=>({state:'READY',completed:1,total:1,goal:{state:'ACTIVE'},decisionHash:'a'.repeat(64),outcome:{conclusion:'准备完成',gaps:['报告尚未证明履约'],nextSteps:['审阅证据']},progress:{steps:[]},resources:[],actions:[],deliveries:[],notifications:[],synthesis:null});
function setup(){
  const elements=new Map(),calls=[],timers=[],rendered=[];let current='',handler=async()=>view();
  const get=id=>{if(!elements.has(id))elements.set(id,new Element(id));return elements.get(id);};
  const context=vm.createContext({snapshot:null,session:()=>current,base:()=>'/api/goals/'+current,$:get,el:(tag,text)=>new Element(tag,text),notice:()=>{},setTimeout:callback=>timers.push(callback),document:{activeElement:null,hidden:false},api:async(path,method,body)=>{calls.push({path,method,body});return path==='/api/workspace/goals'?[]:handler(path,method,body);},progress:async(data,resources)=>rendered.push({data,resources}),load:async()=>{},crypto:{randomUUID:()=> 'request'}});
  vm.runInContext(source,context);
  return {context,calls,timers,rendered,get,setSession(value){current=value;context.snapshot={goal:{sessionId:value}};},setHandler(value){handler=value;}};
}
test('one workspace response supplies progress and displays gaps and explicit synthesis',async()=>{
  const f=setup();f.setSession('s');await f.context.workbench();
  const nodes=all(f.get('workbench'));assert.ok(nodes.some(n=>n.textContent.includes('报告尚未证明履约')));
  assert.equal(f.rendered.length,1);assert.equal(f.calls.filter(c=>c.path==='/api/workspace/goals/s').length,1);
  const button=nodes.find(n=>n.textContent.includes('综合当前证据与取舍'));assert.ok(button);await button.onclick();
  const sent=f.calls.find(c=>c.path.endsWith('/synthesis'));assert.equal(sent.body.expectedHash,'a'.repeat(64));assert.equal(sent.method,'POST');
  assert.equal(f.calls.some(c=>c.path.includes('/approve')),false);
});
test('an old session response cannot replace the new session and overlapping refreshes are coalesced',async()=>{
  const f=setup();f.setSession('old');let resolve;f.setHandler(()=>new Promise(done=>{resolve=done;}));
  const first=f.context.workbench();await Promise.resolve();await f.context.workbench();assert.equal(f.calls.filter(c=>c.path.endsWith('/old')).length,1);
  f.setSession('new');resolve(view());await first;assert.equal(f.get('workbench').children.length,0);
  f.setHandler(async()=>view());await f.context.workbench();assert.ok(f.get('workbench').children.length>0);
});
test('a focused action editor survives refresh and a paused goal cannot request synthesis',async()=>{
  const f=setup();f.setSession('s');const editor=new Element('textarea');f.get('workbench').append(editor);f.context.document.activeElement=editor;await f.context.workbench();assert.equal(f.rendered.length,0);
  f.context.document.activeElement=null;f.setHandler(async()=>({...view(),state:'PAUSED',goal:{state:'PAUSED'}}));await f.context.workbench();assert.equal(all(f.get('workbench')).find(n=>n.textContent.includes('综合当前证据与取舍')).disabled,true);
});
