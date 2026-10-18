"""Read-only evaluation over versioned corpora, mock outcomes and existing events.

No model or tool invocation lives in this module. Raw LLM text is data, never executed.
"""
import argparse
from collections import defaultdict
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path
import re
import sys
from zoneinfo import ZoneInfo
from .metrics import confusion, pass_power, ratio, wilson
from .shadow import shadow_report, compare_runs

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / 'eval/datasets'
STRATA = ('source', 'model', 'route', 'temperature', 'dataset_sha256', 'skill_sha256', 'prompt_sha256')


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def skill_digest(skill):
    root = ROOT/'skills' if skill == '*' else ROOT/'skills'/skill
    result = hashlib.sha256()
    for path in sorted(root.rglob('*')):
        if path.is_file() and '__pycache__' not in path.parts and path.suffix != '.pyc' and path.name != '.DS_Store':
            result.update(path.relative_to(root).as_posix().encode('utf-8'))
            result.update(b'\0'); result.update(path.read_bytes()); result.update(b'\0')
    return result.hexdigest()


def strict_loads(text):
    def invalid(value):
        raise ValueError(f'non-finite JSON number {value}')
    def pairs(values):
        result={}
        for key,value in values:
            if key in result:
                raise ValueError(f'duplicate JSON key {key}')
            result[key]=value
        return result
    def finite(value):
        number=float(value)
        if not math.isfinite(number):
            raise ValueError('non-finite JSON float')
        return number
    return json.loads(text,parse_constant=invalid,parse_float=finite,object_pairs_hook=pairs)


def read_json(path):
    return strict_loads(Path(path).read_text())


def records(path):
    if path is None:
        return []
    path = Path(path)
    if path.suffix == '.jsonl':
        result = []
        for n, line in enumerate(path.read_text().splitlines(), 1):
            if line.strip():
                try:
                    result.append(strict_loads(line))
                except ValueError as e:
                    raise ValueError(f'{path}:{n}: {e}') from e
        if not all(isinstance(row,dict) for row in result):
            raise ValueError(f'{path}: each observation must be an object')
        return result
    value = read_json(path)
    if not isinstance(value, list) or not all(isinstance(row,dict) for row in value):
        raise ValueError(f'{path}: expected a JSON array or JSONL')
    return value


def corpus(name):
    value = read_json(DATA / f'{name}.json')
    if not value.get('version') or not isinstance(value.get('cases'), list) or not value['cases']:
        raise ValueError(f'{name}: version and nonempty cases required')
    ids = [c['id'] for c in value['cases']]
    if len(set(ids)) != len(ids):
        raise ValueError(f'{name}: duplicate case IDs')
    return {c['id']: c for c in value['cases']}


def validate_provenance(row, dataset=None):
    if row.get('source') not in ('scripted', 'model', 'human'):
        raise ValueError('source must be scripted, model or human')
    for key in STRATA[1:]:
        if key not in row:
            raise ValueError(f'missing provenance: {key}')
    if not all(isinstance(row[k], str) and row[k] for k in ('model', 'route')):
        raise ValueError('model and route must be nonempty strings')
    if type(row['temperature']) not in (int, float) or not math.isfinite(row['temperature']):
        raise ValueError('temperature must be finite')
    for key in STRATA[4:]:
        if not re.fullmatch('[0-9a-f]{64}', row[key]):
            raise ValueError(f'{key} must be a SHA-256')
    if not isinstance(row.get('timestamp'),str):
        raise ValueError('timestamp must be an ISO-8601 string')
    timestamp = datetime.fromisoformat(row['timestamp'].replace('Z', '+00:00'))
    if timestamp.tzinfo is None:
        raise ValueError('timestamp must include timezone')
    if dataset and row['dataset_sha256'] != digest(DATA / f'{dataset}.json'):
        raise ValueError(f'{dataset}: dataset hash mismatch; rerun against current corpus')


def unique_rows(rows, id_fields):
    seen = set()
    for row in rows:
        key = tuple(row[k] for k in id_fields)
        if key in seen:
            raise ValueError(f'duplicate observation {key}')
        seen.add(key)


def canonical(value):
    return json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(',',':'),allow_nan=False)


def task_report(rows, min_samples=5):
    gold = corpus('tasks')
    unique_rows(rows, ('task_id', 'sample_id', *STRATA))
    groups = defaultdict(list)
    dates = defaultdict(set)
    outcomes = []
    for row in rows:
        validate_provenance(row, 'tasks')
        if not isinstance(row['sample_id'],str) or not row['sample_id']:
            raise ValueError('sample_id must be a nonempty string')
        task = gold.get(row['task_id'])
        if task is None:
            raise ValueError(f'unknown task {row["task_id"]}')
        if row['skill_sha256'] != skill_digest(task['skill']):
            raise ValueError('skill hash mismatch')
        if not isinstance(row.get('final_state'), dict) or not isinstance(row.get('actions'), list):
            raise ValueError('task requires final_state object and ordered actions list')
        errors = []
        if canonical(row['final_state']) != canonical(task['final_state']):
            errors.append('final_state')
        if canonical(row['actions']) != canonical(task['actions']):
            errors.append('actions')
        if row.get('error'):
            errors.append('execution_error')
        passed = not errors
        key = (row['task_id'], *(row[k] for k in STRATA))
        groups[key].append(passed)
        dates[key].add(datetime.fromisoformat(row['timestamp'].replace('Z','+00:00')).astimezone(ZoneInfo('Asia/Shanghai')).date().isoformat())
        outcomes.append(dict(task_id=row['task_id'], sample_id=row['sample_id'], passed=passed, errors=errors))
    summary = []
    for key, values in sorted(groups.items(), key=lambda kv: str(kv[0])):
        n, c = len(values), sum(values)
        summary.append(dict(task_id=key[0], **dict(zip(STRATA, key[1:])), trials=n, successes=c,
                            sample_dates=sorted(dates[key]), pass_rate=c/n, pass_rate_ci95=wilson(c,n), pass_power_3=pass_power(c,n,3),
                            sufficient_samples=n >= min_samples,
                            qualifies_as_model_evidence=key[1] == 'model' and n >= min_samples))
    missing = sorted(set(gold)-{r['task_id'] for r in rows})
    return dict(groups=summary, outcomes=outcomes, missing_tasks=missing,
                metric='pass^3 = C(successes,3)/C(trials,3); Wilson CI is for single-run pass rate only')


def joined(name, rows):
    gold = corpus(name)
    unique_rows(rows, ('id',))
    for row in rows:
        if row.get('id') not in gold:
            raise ValueError(f'{name}: unknown ID {row.get("id")}')
        validate_provenance(row, name)
        if row['skill_sha256'] != skill_digest('*'):
            raise ValueError(f'{name}: component samples must fingerprint the full skills directory')
    if rows and len({tuple(r[k] for k in STRATA) for r in rows}) > 1:
        raise ValueError(f'{name}: mixed provenance; evaluate each model/route/version separately')
    return gold, [(gold[r['id']], r) for r in rows]


def review_report(rows, threshold=.8, split='holdout'):
    gold, pairs = joined('reviews', rows)
    relevant = [(g,r) for g,r in pairs if g['split'] == split]
    scored = confusion([(g['expected_allow'],r['probability']) for g,r in relevant], threshold)
    by_risk = {risk: confusion([(g['expected_allow'], r['probability']) for g,r in relevant if g['risk'] == risk],threshold)
               for risk in ('irreversible','reversible')}
    return dict(threshold=threshold, split=split, count=len(relevant), **scored, by_risk=by_risk,
                missing_ids=sorted(g['id'] for g in gold.values() if g['split'] == split and g['id'] not in {r['id'] for _,r in relevant}),
                note='Jev consistency score is not execution authorization; irreversible tools always need a human')


def calibrate_review(rows):
    # Never choose a threshold using the holdout set; never write production configuration.
    candidates = []
    for threshold in (0.5,0.6,0.7,0.75,0.8,0.85,0.9,0.95,1.0):
        report = review_report(rows, threshold, 'calibration')
        if report['count'] and not report['missing_ids'] and report['fp'] == 0 and report['tp'] > 0:
            candidates.append(report)
    return dict(recommended_threshold=min(candidates, key=lambda r:r['fn'])['threshold'] if candidates else None,
                candidates=candidates, production_changed=False,
                note='Calibration only. Independent holdout and explicit promotion review are still required.')


def memory_report(rows):
    gold, pairs = joined('memory', rows)
    hits = expected = obsolete_hits = returned = correct = 0
    abilities = defaultdict(lambda: [0,0])
    for g,r in pairs:
        evidence = r['evidence_ids']
        if not isinstance(evidence, list) or len(set(evidence)) != len(evidence):
            raise ValueError('evidence_ids must be unique')
        known = {h['id'] for h in g['history']}
        if not set(evidence) <= known:
            raise ValueError('memory evidence contains invented IDs')
        wanted = set(g['evidence_ids'])
        hits += len(wanted & set(evidence)); expected += len(wanted)
        obsolete_hits += len(set(g['obsolete_ids']) & set(evidence)); returned += len(evidence)
        answer_ok = r['answer'] == g['answer']  # exact closed-answer rubric, no hidden judge
        correct += answer_ok
        abilities[g['ability']][0] += answer_ok; abilities[g['ability']][1] += 1
    return dict(count=len(pairs), evidence_recall=ratio(hits,expected), evidence_hits=hits, evidence_expected=expected,
                answer_accuracy=ratio(correct,len(pairs)), obsolete_recall_rate=ratio(obsolete_hits,returned),
                obsolete_hits=obsolete_hits, returned_evidence=returned,
                by_ability={k:dict(correct=v[0],count=v[1],accuracy=ratio(*v)) for k,v in abilities.items()},
                missing_ids=sorted(set(gold)-{r['id'] for _,r in pairs}))


def habit_report(rows):
    gold, pairs = joined('habits', rows)
    tp=fp=tn=fn=0
    for g,r in pairs:
        if type(r['proposed']) is not bool:
            raise ValueError('proposed must be boolean')
        tp += r['proposed'] and g['expected_proposal']
        fp += r['proposed'] and not g['expected_proposal']
        tn += not r['proposed'] and not g['expected_proposal']
        fn += not r['proposed'] and g['expected_proposal']
    return dict(count=len(pairs), tp=tp,fp=fp,tn=tn,fn=fn,false_positive_rate=ratio(fp,fp+tn),
                proposal_precision=ratio(tp,tp+fp),missing_ids=sorted(set(gold)-{r['id'] for _,r in pairs}))


def judge_report(rows):
    gold, pairs = joined('judge', rows)
    agreed=stable=labeled=0
    for g,r in pairs:
        if r['winner_ab'] not in ('A','B','tie') or r['winner_ba'] not in ('A','B','tie'):
            raise ValueError('judge winner must be A/B/tie, normalized to original answer identity')
        consistent = r['winner_ab'] == r['winner_ba']
        stable += consistent
        if g['human_winner'] is not None:
            labeled += 1
            agreed += consistent and r['winner_ab'] == g['human_winner']
    return dict(count=len(pairs), human_agreement=ratio(agreed,labeled), labeled_count=labeled,
                pending_human_label_ids=sorted(g['id'] for g in gold.values() if g['human_winner'] is None), order_stability=ratio(stable,len(pairs)),
                missing_ids=sorted(set(gold)-{r['id'] for _,r in pairs}),
                note='Both orderings use original A/B identities. Disagreement counts as a calibration failure.')


def make_report(task_rows, review_rows=(), memory_rows=(), habit_rows=(), judge_rows=()):
    return dict(schema_version=1, generated_at=datetime.now(timezone.utc).isoformat(),
                tasks=task_report(task_rows), reviews=review_report(list(review_rows)), memory=memory_report(list(memory_rows)),
                habits=habit_report(list(habit_rows)), judge=judge_report(list(judge_rows)),
                gold_labels_reviewed={name:all(g.get('label_status')=='human-reviewed' for g in corpus(name).values())
                    for name in ('reviews','memory','habits')},
                evaluation_targets=sorted({r.get('evaluation_target','unspecified') for r in task_rows}),
                evidence_sources={name:sorted({r['source'] for r in rows}) for name,rows in
                    [('tasks',task_rows),('reviews',review_rows),('memory',memory_rows),('habits',habit_rows),('judge',judge_rows)]})


def dump(value, path=None):
    text = json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False)+'\n'
    if path:
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        Path(path).write_text(text)
    else:
        print(text, end='')


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    subs = parser.add_subparsers(dest='command',required=True)
    report = subs.add_parser('report')
    for name in ('runs','reviews','memory','habits','judge'):
        report.add_argument('--'+name)
    report.add_argument('--output',required=True)
    calibrate = subs.add_parser('calibrate'); calibrate.add_argument('--reviews',required=True); calibrate.add_argument('--output')
    shadow = subs.add_parser('shadow'); shadow.add_argument('--events',required=True); shadow.add_argument('--day'); shadow.add_argument('--output')
    compare = subs.add_parser('compare'); compare.add_argument('--before',required=True); compare.add_argument('--after',required=True); compare.add_argument('--output')
    matrix = subs.add_parser('matrix'); matrix.add_argument('--samples',type=int,default=5); matrix.add_argument('--output')
    injection = subs.add_parser('injections'); injection.add_argument('--runs',required=True); injection.add_argument('--output')
    promotion = subs.add_parser('promotion'); promotion.add_argument('--report',required=True); promotion.add_argument('--injections',required=True); promotion.add_argument('--shadow',required=True); promotion.add_argument('--output',required=True)
    coverage = subs.add_parser('coverage'); coverage.add_argument('--output')
    events = subs.add_parser('events'); events.add_argument('--runs',required=True); events.add_argument('--output',required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == 'report':
            value = make_report(records(args.runs), records(args.reviews), records(args.memory), records(args.habits), records(args.judge))
            dump(value,args.output)
        elif args.command == 'coverage':
            from .coverage import coverage_report
            dump(coverage_report(),args.output)
        elif args.command == 'events':
            exported=[]
            for run in records(args.runs):
                for event in run.get('events',[]):
                    exported.append(dict(event,session_id=run['session_id']))
            dump(exported,args.output)
        elif args.command == 'matrix':
            from .safety import matrix_plan
            dump(matrix_plan(args.samples),args.output)
        elif args.command == 'injections':
            from .safety import injection_report
            dump(injection_report(records(args.runs)),args.output)
        elif args.command == 'promotion':
            from .promotion import promotion_review
            value=promotion_review(read_json(args.report),read_json(args.injections),read_json(args.shadow))
            dump(value,args.output)
            return 0 if value['status']=='ready_for_human_review' else 1
        elif args.command == 'shadow':
            dump(shadow_report(records(args.events),args.day),args.output)
        elif args.command == 'compare':
            dump(compare_runs(records(args.before),records(args.after)),args.output)
        else:
            dump(calibrate_review(records(args.reviews)),args.output)
    except (ValueError,KeyError,TypeError,OSError) as exc:
        print(f'evaluation input error: {exc}',file=sys.stderr)
        return 2
    return 0


if __name__ == '__main__':
    sys.exit(main())
