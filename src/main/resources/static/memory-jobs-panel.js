/* Read-only, text-escaped rendering. Missing diagnostics must never look like zero backlog or zero usage. */
globalThis.mioMemoryJobsHtml = function(data, escape) {
  const heading='<h2>记忆后台任务</h2>', unavailable=heading+'<div class="empty" role="status">后台状态暂不可用，不能按零积压或零用量处理。</div>';
  if(!data || !Array.isArray(data.jobs?.counts) || !Array.isArray(data.jobs?.recent) || !Array.isArray(data.billing?.usage) || !data.worker || !data.cacheTasks || !data.billing.limits)return unavailable;
  const usage=data.billing.usage[0] || {calls:0,input_chars:0,known_tokens:0,pending:0,unknown:0};
  const limits=data.billing.limits, numbers=[usage.calls,usage.input_chars,usage.known_tokens,usage.pending,usage.unknown,limits.calls,limits.inputChars,limits.knownTokens,data.worker.active,data.worker.parallelism,data.cacheTasks.queued,data.cacheTasks.rejected];
  if(numbers.some(n=>!Number.isFinite(n)||n<0))return unavailable;
  const kinds={DISTILL:'画像提炼',INDEX:'历史向量'}, states={READY:'等待执行',RUNNING:'正在执行',RETRY:'等待重试或额度',DONE:'已完成',DEAD:'需要检查'};
  const reasons={DAILY_BUDGET:'等待下一额度周期',PRESERVED_NEWER_OR_MANUAL:'已保留人工修改或更新的画像',INVALID_RESULT:'结果无法验证',INPUT_TOO_LARGE:'单次输入超过整日额度',ATTEMPTS_EXHAUSTED:'已达恢复次数上限',STORAGE_UNAVAILABLE:'存储暂不可用',PROCESSING_FAILED:'本次处理未完成'};
  let html=heading+'<p>'+ (data.worker.enabled?'持久任务消费者已启用':'后台执行已停用，已登记任务保留')+' · 正在执行 '+data.worker.active+'/'+data.worker.parallelism+'</p>';
  if(!data.worker.availableKinds?.length)html+='<p>当前没有可执行的记忆服务，请核对开关与本机配置；任务不会被当作已完成。</p>';
  html+='<p>额度日 '+escape(data.billing.day)+'（UTC） · 已预占调用 '+usage.calls+'/'+limits.calls+' · 输入字符 '+usage.input_chars+'/'+limits.inputChars+' · 已报告 token '+usage.known_tokens+'/'+limits.knownTokens+'</p>';
  html+='<p>待结算 '+usage.pending+' · 用量未知 '+usage.unknown+'；已报告 token 不是费用硬上限，用量缺口不能视为零消耗。</p>';
  html+='<p>可重建缓存：排队 '+data.cacheTasks.queued+'，未接收 '+data.cacheTasks.rejected+'。原文派生任务保存在数据库中。</p>';
  if(data.jobs.counts.length){html+='<table><tr><th>类型</th><th>状态</th><th>数量</th></tr>';
    for(const row of data.jobs.counts){if(!Number.isSafeInteger(row.count)||row.count<0)return unavailable;html+='<tr><td>'+escape(kinds[row.kind]||'待核对')+'</td><td>'+escape(states[row.state]||'待核对')+'</td><td>'+row.count+'</td></tr>';}html+='</table>';
  }else html+='<div class="empty">尚无已登记的记忆任务</div>';
  if(data.jobs.recent.length){html+='<details><summary>最近任务（只读）</summary><table><tr><th>类型</th><th>状态</th><th>尝试</th><th>说明</th></tr>';
    for(const job of data.jobs.recent)html+='<tr><td>'+escape(kinds[job.kind]||'待核对')+'</td><td>'+escape(states[job.state]||'待核对')+'</td><td>'+escape(String(job.attempts))+'</td><td>'+escape(reasons[job.errorCode]||(job.resultSaved?'结果检查点已保存':''))+'</td></tr>';
    html+='</table></details>';
  }
  return html;
};
