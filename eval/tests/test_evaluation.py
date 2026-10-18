import copy
from datetime import datetime, timezone
import json
import tempfile
from pathlib import Path
import unittest
from eval.metrics import confusion, pass_power, ratio, wilson
from eval.runner import (DATA, ROOT, STRATA, corpus, digest, skill_digest, task_report, review_report, calibrate_review,
                         memory_report, habit_report, judge_report, records)
from eval.shadow import shadow_report, compare_runs


def observation(dataset, **fields):
    return dict(source='scripted', model='test-model',route='standard',temperature=0,
                timestamp=datetime.now(timezone.utc).isoformat(), dataset_sha256=digest(DATA/f'{dataset}.json'),
                skill_sha256=skill_digest('mcd-ordering' if dataset in ('tasks','injections') else '*'),prompt_sha256='a'*64, **fields)


def task_rows(n=5):
    task=corpus('tasks')['mcd-menu-only']
    return [observation('tasks',task_id=task['id'],sample_id=str(i),final_state=task['final_state'],actions=task['actions']) for i in range(n)]


class MetricsTest(unittest.TestCase):
    def test_pass_power_is_all_success_probability_not_pass_at_k(self):
        self.assertEqual(pass_power(4,5),.4)
        self.assertEqual(pass_power(5,5),1)
        self.assertEqual(pass_power(2,5),0)
        self.assertIsNone(pass_power(2,2))
        self.assertNotEqual(pass_power(4,5),(4/5)**3)
    def test_invalid_counts(self):
        for args in [(6,5),(-1,5),(True,5),(1,2,0)]:
            with self.assertRaises(ValueError): pass_power(*args)
    def test_undefined_is_not_zero_or_perfect(self):
        self.assertIsNone(ratio(0,0)); self.assertIsNone(wilson(0,0))
        self.assertIsNone(confusion([],.8)['auto_allow_precision'])
    def test_confusion_tracks_both_failure_directions(self):
        report=confusion([(True,.9),(False,.9),(True,.7),(False,.7)],.8)
        self.assertEqual([report[k] for k in ('tp','fp','fn','tn')],[1,1,1,1])
        self.assertEqual(report['unsafe_block_recall'],.5)
        self.assertEqual(report['false_block_rate'],.5)
    def test_invalid_scores_and_booleans(self):
        for gold,score in [(1,.8),(True,True),(True,float('nan')),(True,1.01)]:
            with self.assertRaises(ValueError): confusion([(gold,score)],.8)
    def test_confidence_interval_is_not_certainty(self):
        lo,hi=wilson(5,5)
        self.assertLess(lo,.6); self.assertAlmostEqual(hi,1)


class TaskTest(unittest.TestCase):
    def test_scripted_baseline_never_qualifies(self):
        report=task_report(task_rows())
        group=report['groups'][0]
        self.assertEqual(group['pass_power_3'],1)
        self.assertFalse(group['qualifies_as_model_evidence'])
        self.assertTrue(report['missing_tasks'])
    def test_exact_final_state_and_action_order(self):
        rows=task_rows(); rows[0]['final_state']={'unauthorized_order':'x'}; rows[1]['actions']=[]
        report=task_report(rows)
        self.assertEqual(report['groups'][0]['successes'],3)
        self.assertEqual(report['groups'][0]['pass_power_3'],.1)
    def test_small_sample_and_model_route_stratification(self):
        rows=task_rows(4); rows[0]['route']='simple'
        groups=task_report(rows)['groups']
        self.assertEqual(len(groups),2)
        self.assertTrue(all(not g['sufficient_samples'] for g in groups))
    def test_duplicate_unknown_and_stale_provenance_rejected(self):
        variants=[task_rows()+task_rows()[:1], task_rows()]
        variants[1][0]['task_id']='unknown'
        for rows in variants:
            with self.assertRaises(ValueError): task_report(rows)
        for key in ('dataset_sha256','skill_sha256'):
            rows=task_rows(); rows[0][key]='b'*64
            with self.assertRaises(ValueError): task_report(rows)
    def test_error_even_with_correct_state_is_failure(self):
        rows=task_rows(); rows[0]['error']='model timeout'
        self.assertEqual(task_report(rows)['groups'][0]['successes'],4)
    def test_untrusted_text_never_executes(self):
        rows=task_rows(); rows[0]['reply']='__import__("os").system("exit 1")'
        self.assertEqual(task_report(rows)['groups'][0]['successes'],5)


class ComponentTest(unittest.TestCase):
    def test_threshold_selection_uses_calibration_only(self):
        rows=[observation('reviews',id=g['id'],probability=.9 if g['expected_allow'] else .1)
              for g in corpus('reviews').values()]
        before=calibrate_review(rows)['recommended_threshold']
        for row in rows:
            if 'holdout' in row['id']: row['probability']=1-row['probability']
        self.assertEqual(before,calibrate_review(rows)['recommended_threshold'])
        self.assertGreater(review_report(rows)['fp'],0)
        self.assertFalse(calibrate_review(rows)['production_changed'])
    def test_memory_deduplicates_evidence_and_tracks_stale_hits(self):
        gold=corpus('memory')['memory-update']
        row=observation('memory',id=gold['id'],evidence_ids=['m1','m2'],answer='上海')
        report=memory_report([row]); self.assertEqual(report['evidence_recall'],1)
        self.assertEqual(report['obsolete_recall_rate'],.5)
        row['evidence_ids']=['m2','m2']
        with self.assertRaises(ValueError): memory_report([row])
        row['evidence_ids']=['invented']
        with self.assertRaises(ValueError): memory_report([row])
    def test_abstention_is_an_explicit_null_answer(self):
        row=observation('memory',id='memory-abstention',evidence_ids=[],answer=None)
        self.assertEqual(memory_report([row])['answer_accuracy'],1)
        row['answer']='1234'
        self.assertEqual(memory_report([row])['answer_accuracy'],0)
    def test_habit_false_positive_denominator_is_negative_cases(self):
        rows=[observation('habits',id=g['id'],proposed=True) for g in corpus('habits').values()]
        self.assertEqual(habit_report(rows)['false_positive_rate'],1)
    def test_judge_without_human_labels_cannot_be_calibrated(self):
        rows=[observation('judge',id=g['id'],winner_ab='A',winner_ba='A') for g in corpus('judge').values()]
        result=judge_report(rows)
        self.assertIsNone(result['human_agreement']); self.assertEqual(result['labeled_count'],0)
        self.assertTrue(result['pending_human_label_ids'])
    def test_missing_and_mixed_component_samples_do_not_silently_pass(self):
        self.assertTrue(review_report([])['missing_ids'])
        rows=[observation('reviews',id=g['id'],probability=.9) for g in corpus('reviews').values()]
        rows[0]['model']='different'
        with self.assertRaises(ValueError): review_report(rows)
    def test_duplicate_json_keys_and_float_overflow_are_rejected(self):
        from eval.runner import strict_loads
        for text in ('{"source":"model","source":"scripted"}','{"score":1e999}'):
            with self.assertRaises(ValueError):strict_loads(text)
    def test_invalid_json_numbers_are_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/'test.jsonl'; path.write_text('{"probability": NaN}\n')
            with self.assertRaises(ValueError): records(path)


def event(kind, detail, ts=1792339200000, id=None):
    row=dict(session_id='s1',ts=ts,type=kind,detail=json.dumps(detail) if isinstance(detail,dict) else detail)
    if id: row['id']=id
    return row


class ShadowTest(unittest.TestCase):
    def test_pairing_uses_session_and_call_id(self):
        policy=dict(schemaVersion=1,callId='c1',tool='create-order',effect='ASK',state='ASKING')
        result=dict(schemaVersion=1,callId='c1',tool='create-order',state='SUCCESS',output='truncated')
        rows=[event('policy_decision',policy),event('tool_result',result),
              event('confirm',dict(schemaVersion=1,decisions=[dict(toolCallId='c1',approved=True)]))]
        report=shadow_report(rows)
        self.assertEqual(report['snapshot_divergence_rate'],0)
        self.assertEqual(report['executed_after_refusal'],0)
        rows[-1]=event('confirm',dict(schemaVersion=1,decisions=[dict(toolCallId='c1',approved=False)]))
        self.assertEqual(shadow_report(rows)['executed_after_refusal'],1)
    def test_legacy_is_uncomparable_and_duplicates_are_counted(self):
        row=event('policy_decision','default-ask：无规则覆盖，实际状态 ASKING',id='e1')
        report=shadow_report([row,row]); self.assertIsNone(report['snapshot_divergence_rate'])
        self.assertEqual(report['diagnostics']['legacy_or_unstructured'],1)
        self.assertEqual(report['diagnostics']['duplicate_events'],1)
    def test_day_filter_uses_shanghai(self):
        row=event('policy_decision','legacy',ts=1792339200000)
        self.assertEqual(shadow_report([row],day='2026-10-19')['diagnostics']['legacy_or_unstructured'],1)
        self.assertFalse(shadow_report([row],day='2026-10-18')['daily'])
    def test_lowercase_framework_enums_and_conflicting_ids(self):
        p=event('policy_decision',dict(schemaVersion=1,callId='c1',tool='x',effect='ASK',state='asking'),id='e1')
        self.assertEqual(shadow_report([p])['comparable_snapshots'],1)
        conflict=copy.deepcopy(p);conflict['ts']+=1
        with self.assertRaises(ValueError):shadow_report([p,conflict])
    def test_different_sessions_are_not_joined(self):
        p=event('policy_decision',dict(schemaVersion=1,callId='c1',tool='x',effect='ASK',state='ASKING'))
        r=event('tool_result',dict(schemaVersion=1,callId='c1',tool='x',state='SUCCESS'));r['session_id']='s2'
        self.assertEqual(shadow_report([p,r])['unmatched_results'],1)
    def test_only_reply_changes_need_judge(self):
        before=task_rows();after=copy.deepcopy(before)
        after[0]['reply']='new'; after[1]['actions']=[]
        changes=compare_runs(before,after)['changed']
        self.assertTrue(changes[0]['needs_judge']); self.assertFalse(changes[1]['needs_judge'])
        after[0]['temperature']=1
        self.assertEqual(compare_runs(before,after)['comparable_pairs'],4)


if __name__=='__main__': unittest.main()
