"""Consume existing events exports. Match only structured call IDs, never guess from tool names."""
from collections import Counter, defaultdict
from datetime import datetime
from zoneinfo import ZoneInfo
import json
from .metrics import ratio


def shadow_report(rows, day=None):
    counts = Counter()
    policies, results = {}, {}
    approvals = {}
    seen = set()
    daily = defaultdict(Counter)
    for row in rows:
        # Accept pg events rows, or exported TraceStore events enriched with sessionId.
        session = row.get('session_id', row.get('sessionId'))
        ts = row.get('ts', row.get('timestamp'))
        if not isinstance(session,str) or not session or type(ts) is not int:
            raise ValueError('shadow events require session_id and integer ts/timestamp milliseconds')
        local_day = datetime.fromtimestamp(ts/1000, ZoneInfo('Asia/Shanghai')).date().isoformat()
        if day and local_day != day:
            continue
        # PG id is the idempotency key; without it, duplicate observations cannot safely be collapsed.
        identity = row.get('id')
        if identity:
            if identity in seen:
                counts['duplicate_events'] += 1
                continue
            seen.add(identity)
        kind = row.get('type')
        daily[local_day]['events'] += 1
        if kind not in ('policy_decision','tool_result','confirm'):
            continue
        raw = row.get('detail')
        try:
            detail = json.loads(raw) if isinstance(raw,str) else raw
        except (ValueError, TypeError):
            detail = None
        if not isinstance(detail,dict) or detail.get('schemaVersion') != 1:
            counts['legacy_or_unstructured'] += 1
            continue
        if kind == 'confirm':
            for decision in detail.get('decisions',[]):
                key = (session, decision['toolCallId'])
                if type(decision['approved']) is not bool:
                    raise ValueError('approval must be boolean')
                if key in approvals and approvals[key] != decision['approved']:
                    raise ValueError('conflicting human decisions')
                approvals[key] = decision['approved']
            continue
        key = (session,detail['callId'])
        collection = policies if kind == 'policy_decision' else results
        if key in collection:
            if collection[key] != detail:
                raise ValueError(f'conflicting {kind} for {key}')
            counts['duplicate_snapshots'] += 1
        collection[key] = detail
    comparable=divergences=executed_after_refusal=0
    pairs=[]
    for key, policy in policies.items():
        effect, state = policy.get('effect'), policy.get('state')
        comparable_pair = effect in ('ALLOW','ASK','DENY') and state in ('ASKING','ALLOWED','SUBMITTED','FINISHED')
        divergent = None
        if comparable_pair:
            comparable += 1
            divergent = ((effect == 'ALLOW' and state == 'ASKING') or
                         (effect in ('ASK','DENY') and state in ('ALLOWED','SUBMITTED','FINISHED')))
            divergences += divergent
        else:
            counts['uncomparable_policy_snapshots'] += 1
        result = results.get(key)
        executed = bool(result and result.get('state') == 'SUCCESS')
        refusal_violation = executed and approvals.get(key) is False
        executed_after_refusal += refusal_violation
        pairs.append(dict(session_id=key[0],call_id=key[1],tool=policy['tool'],effect=effect,snapshot_state=state,
                          snapshot_divergent=divergent, result_state=result.get('state') if result else None,
                          human_approved=approvals.get(key),executed_after_refusal=refusal_violation))
    return dict(policy_snapshots=len(policies),comparable_snapshots=comparable, snapshot_divergences=divergences,
                snapshot_divergence_rate=ratio(divergences,comparable),executed_after_refusal=executed_after_refusal,
                unmatched_results=len(set(results)-set(policies)),unresolved_calls=len(set(policies)-set(results)),
                diagnostics=dict(counts),daily={d:dict(c) for d,c in sorted(daily.items())},pairs=pairs,
                note='Snapshot disagreement is diagnostic, not a false-allow metric. ASK may later execute after human/Jev approval; '
                     'command-sensitive tools can ALLOW despite static ASK. Legacy text is uncomparable. Truncated outputs are never final-state evidence.')


def compare_runs(before, after):
    # Compare only semantically comparable versions. Skill/prompt hashes may differ: those are the changes under test.
    keys=('task_id','sample_id','source','model','route','temperature','dataset_sha256')
    def index(rows):
        result={}
        for row in rows:
            key=tuple(row[k] for k in keys)
            if key in result:
                raise ValueError('duplicate regression key')
            result[key]=row
        return result
    left,right=index(before),index(after)
    changed=[]
    for key in sorted(set(left)&set(right),key=str):
        # Deliberately omit volatile IDs/timestamps/events; assertions consume full mock state and actions.
        fields=[f for f in ('final_state','actions','reply','error') if left[key].get(f) != right[key].get(f)]
        if fields:
            changed.append(dict(key=dict(zip(keys,key)),changed_fields=fields,needs_judge=fields==['reply']))
    return dict(comparable_pairs=len(set(left)&set(right)),changed=changed,
                removed=len(set(left)-set(right)),added=len(set(right)-set(left)),
                note='Different dataset/model/route/temperature are not paired. Only reply-only changes can request a calibrated judge; state/action differences use assertions.')
