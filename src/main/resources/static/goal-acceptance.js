/* Acceptance is transient server evidence. Drafts never approve business tools. */
globalThis.MioGoalAcceptance = class {
  constructor({confirm, refresh, changed, error}) {
    this.confirm = confirm; this.refresh = refresh; this.changed = changed; this.error = error;
    this.view = null; this.draft = null; this.busy = false; this.expanded = false; this.expired = false;
  }
  receive(view, goal, requestedAt = performance.now()) {
    if (!view || !goal) { this.reset(); return true; }
    const states = ['NOT_DEFINED','STALE','PENDING','PARTIALLY_SATISFIED','NOT_SATISFIED','BLOCKED','SATISFIED'];
    if (view.goalId !== goal.id || view.goalRevision !== goal.revision || !states.includes(view.state)
        || !Number.isSafeInteger(view.revision) || view.revision < 0 || !Number.isSafeInteger(view.checkedAt)
        || (view.validUntil != null && !Number.isSafeInteger(view.validUntil))
        || !Array.isArray(view.candidates) || !Array.isArray(view.results) || !Array.isArray(view.blockers)
        || !view.taskRevisions || Object.values(view.taskRevisions).some(v => !Number.isSafeInteger(v) || v < 0)) throw new Error('目标验收信息异常，请重新读取。');
    if (this.view?.goalId === view.goalId && this.view.goalRevision === view.goalRevision) {
      if (view.revision < this.view.revision || view.checkedAt < this.view.checkedAt
          || Object.entries(this.view.taskRevisions).some(([id, revision]) => !Object.hasOwn(view.taskRevisions,id) || view.taskRevisions[id] < revision)) return false;
    }
    clearTimeout(this.timer); this.view = view; this.expired = false;
    const lifetime = Math.min(view.state === 'SATISFIED' ? 30000 : Infinity, view.validUntil == null ? Infinity : view.validUntil - view.checkedAt);
    this.deadline = Number.isFinite(lifetime) ? performance.now() + Math.max(0, lifetime - Math.max(0, performance.now() - requestedAt)) : Infinity;
    if (Number.isFinite(this.deadline)) {
      const remaining = Math.max(0, this.deadline - performance.now());
      this.expired = remaining === 0;
      if (!this.expired) this.timer = setTimeout(() => { this.expired = true; this.changed(); if(document.visibilityState!=='hidden')Promise.resolve(this.refresh()).catch(() => {}); }, Math.min(remaining, 2147483647));
    }
    return true;
  }
  reset() { clearTimeout(this.timer); this.view = null; this.draft = null; this.expired = false; this.expanded = false; this.pending = null; this.busy = false; }
  invalidate() { clearTimeout(this.timer); this.expired = true; }
  ensureDraft(goal) {
    const key = `${goal.id}:${goal.revision}:${this.view.revision}`;
    if (this.draft?.key === key) return;
    this.draft = {key, rows: new Map()};
    const saved = this.view.confirmed?.goalRevision === goal.revision ? this.view.confirmed.requirements : [];
    for (const candidate of this.view.candidates) {
      const existing = saved?.find(r => r.taskId === candidate.taskId);
      this.draft.rows.set(candidate.taskId, {selected: !!existing, checks: (existing?.checks || []).map(c => ({...c}))});
    }
  }
  render(container, goal) {
    const node = (tag, text, className = '') => { const e = document.createElement(tag); e.className = className; if (text != null) e.textContent = text; return e; };
    const section = node('section', null, 'acceptance'); section.setAttribute('aria-label', '目标验收'); container.append(section);
    if (!this.view || this.view.goalId !== goal.id || this.view.goalRevision !== goal.revision) { section.append(node('p','验收：尚未读取当前条件。','goal-note')); return; }
    const labels = {NOT_DEFINED:'尚未确认验收条件',STALE:'目标已改变，请重新确认条件',PENDING:'仍有任务等待处理',PARTIALLY_SATISFIED:'部分条件已满足',NOT_SATISFIED:'条件尚未满足',BLOCKED:'存在待核查操作',SATISFIED:'已确认的验收范围当前满足'};
    const expired = this.expired || performance.now() >= this.deadline;
    section.append(node('strong', expired ? '验收：证据需重新读取' : '验收：' + labels[this.view.state]));
    section.append(node('p','只核验你明确选择的条件；不代表未覆盖的要求已完成，也不会批准业务操作。','goal-note'));
    const refresh = node('button','重新读取','quiet'); refresh.type = 'button'; refresh.disabled = this.busy;
    refresh.onclick = () => Promise.resolve(this.refresh()).catch(() => this.error('未能读取验收状态，请稍后重试。')); section.append(refresh);
    for (const result of this.view.results) {
      const status = {SATISFIED:'满足',NOT_FINISHED:'尚未完成',MISSING_EVIDENCE:'缺少证据',EXPIRED_EVIDENCE:'证据已过期',INVALIDATED:'依赖已失效',CONTRACT_CHANGED:'契约已改变',GOAL_CHANGED:'目标已改变',CONSTRAINT_NOT_MET:'字段条件未满足',INVALID_VALUE:'字段值无法核验'};
      const details = node('details'); details.append(node('summary', result.objective + ' · ' + (expired ? '需重新读取' : status[result.state] || '待核对')));
      details.append(node('p',result.scope,'goal-note'));
      for (const check of result.checks) {
        const unit = this.view.candidates.find(c => c.taskId === result.taskId)?.fields.find(f => f.id === check.fieldId)?.unit;
        details.append(node('p',`${check.label}${unit ? '（'+unit+'）' : ''}：当前 ${check.actual ?? '未提供'}，要求 ${this.operatorLabel(check.operator)} ${check.expected} · ${expired ? '需重新读取' : check.matched ? '满足' : '未满足'}`,'goal-note'));
      }
      section.append(details);
    }
    for (const blocker of this.view.blockers) section.append(node('p',blocker.reason,'goal-note'));
    if (goal.state === 'CLOSED') return;
    this.ensureDraft(goal);
    const editor = node('details', null, 'acceptance-editor'); editor.open = this.expanded;
    editor.ontoggle = () => { this.expanded = editor.open; }; editor.append(node('summary', '设置或修改验收条件'));
    if (!this.view.candidates.length) editor.append(node('p','当前任务没有可核验的输出条件。可继续准备任务，工作报告不能替代业务证据。','goal-note'));
    for (const candidate of this.view.candidates) {
      let row = this.draft.rows.get(candidate.taskId);
      if (!row) { row = {selected: false, checks: []}; this.draft.rows.set(candidate.taskId, row); }
      const group = node('fieldset'); group.disabled = this.busy;
      const label = node('label'); const selected = node('input'); selected.type = 'checkbox'; selected.checked = row.selected;
      selected.onchange = () => { row.selected = selected.checked; }; label.append(selected, document.createTextNode(candidate.objective));
      group.append(label,node('p',candidate.scope,'goal-note'));
      for (const field of candidate.fields) {
        if (!row.checks.some(c => c.fieldId === field.id)) row.checks.push({fieldId:field.id,operator:'',expected:''});
        for (const existing of row.checks.filter(c => c.fieldId === field.id)) {
        const fieldLabel = node('label',null,'acceptance-field'); fieldLabel.append(node('span',field.label + (field.unit ? `（${field.unit}）` : '')));
        const operator = node('select'); operator.setAttribute('aria-label',field.label+'的比较方式');
        for (const value of field.type === 'NUMBER' ? ['','LTE','GTE','EQ'] : ['','EQ']) { const option = node('option',value ? this.operatorLabel(value) : '不限定字段值'); option.value = value; operator.append(option); }
        operator.value = existing.operator || '';
        const value = node('input'); value.type = 'text'; value.maxLength = 500; value.value = existing?.expected || ''; value.disabled = !operator.value;
        if (field.type === 'NUMBER') value.inputMode = 'decimal'; value.setAttribute('aria-label',field.label+'的期望值');
        const update = () => { value.disabled = !operator.value; existing.operator = operator.value; existing.expected = value.value; };
        operator.onchange = update; value.oninput = update; fieldLabel.append(operator,value); group.append(fieldLabel);
        }
        if (field.type === 'NUMBER') { const add = node('button','为'+field.label+'添加另一条限制','quiet'); add.type = 'button';
          add.disabled = row.checks.length >= 8; add.onclick = () => { if(row.checks.length>=8)return;row.checks.push({fieldId:field.id,operator:'',expected:''}); this.changed(); }; group.append(add); }
      }
      editor.append(group);
    }
    const save = node('button',this.busy ? '正在确认…' : '确认这些验收条件','quiet'); save.type = 'button'; save.disabled = this.busy || !this.view.candidates.length;
    save.onclick = () => this.submit(false,goal); editor.append(save);
    if (this.view.confirmed?.requirements.length) { const clear = node('button','清除验收条件','quiet'); clear.type = 'button'; clear.disabled = this.busy; clear.onclick = () => this.submit(true,goal); editor.append(clear); }
    section.append(editor);
  }
  operatorLabel(operator) { return {EQ:'等于',LTE:'不超过',GTE:'不低于'}[operator] || '待核对'; }
  async submit(clear, goal) {
    if (this.busy) return;
    const selections = clear ? [] : this.view.candidates.filter(c => this.draft.rows.get(c.taskId)?.selected).map(c => ({taskId:c.taskId,contractVersion:c.contractVersion,checks:this.draft.rows.get(c.taskId).checks.filter(check => check.operator).map(check => ({...check}))}));
    if (!clear && !selections.length) { this.error('请至少选择一个需要验收的任务。'); return; }
    if (selections.length > 8 || selections.some(s => s.checks.length > 8 || new Set(s.checks.map(c => c.fieldId+':'+c.operator)).size !== s.checks.length || s.checks.some(c => !c.expected.trim()))) { this.error('最多选择八个任务；每项最多八条不同限制，并填写期望值。'); return; }
    const pending = {}; this.pending = pending; this.busy = true; this.invalidate(); this.changed();
    try { await this.confirm({expectedGoalRevision:goal.revision,expectedControlRevision:goal.controlRevision,expectedRevision:this.view.revision,selections}); }
    catch (error) { if (this.pending === pending) { this.error(error.message || '未能确认保存结果，请重新读取后核对。'); try { await this.refresh(); } catch (_) {} } }
    finally { if (this.pending === pending) { this.pending = null; this.busy = false; this.changed(); } }
  }
};
