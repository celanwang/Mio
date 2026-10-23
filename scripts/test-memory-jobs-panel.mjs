import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
const scope=vm.createContext({});vm.runInContext(fs.readFileSync(new URL('../src/main/resources/static/memory-jobs-panel.js',import.meta.url),'utf8'),scope);
const escape=value=>String(value??'').replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('>','&gt;').replaceAll('"','&quot;');
const fixture=()=>({jobs:{counts:[{kind:'DISTILL',state:'RETRY',count:2}],recent:[{kind:'DISTILL',state:'RETRY',attempts:1,errorCode:'DAILY_BUDGET',resultSaved:false}]},billing:{day:'2026-10-23',limits:{calls:200,inputChars:400000,knownTokens:200000},usage:[{calls:3,input_chars:100,known_tokens:10,pending:1,unknown:1}]},worker:{enabled:true,active:1,parallelism:2,availableKinds:['DISTILL']},cacheTasks:{queued:2,rejected:1}});
test('unavailable or malformed diagnostics never render as zero usage',()=>{
  assert.match(scope.mioMemoryJobsHtml(null,escape),/不能按零积压或零用量/);const data=fixture();delete data.billing.usage[0].known_tokens;assert.match(scope.mioMemoryJobsHtml(data,escape),/状态暂不可用/);
});
test('backlog and unknown usage are visible with no mutation control',()=>{
  const html=scope.mioMemoryJobsHtml(fixture(),escape);assert.match(html,/画像提炼/);assert.match(html,/等待下一额度周期/);assert.match(html,/待结算 1 · 用量未知 1/);assert.doesNotMatch(html,/<button|onclick|private-model-result/);
});
test('dynamic text is escaped and disabled workers keep pending work visible',()=>{
  const data=fixture();data.billing.day='<img src=x onerror=alert(1)>';data.worker.enabled=false;
  const html=scope.mioMemoryJobsHtml(data,escape);assert.match(html,/&lt;img/);assert.doesNotMatch(html,/<img/);assert.match(html,/后台执行已停用，已登记任务保留/);
});
