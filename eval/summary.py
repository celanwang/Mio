"""Human-readable report from the same JSON artifacts used by the gates."""
from .runner import read_json


def write_summary(directory):
    from pathlib import Path
    root=Path(directory)
    report=read_json(root/'report.json')
    injection=read_json(root/'injection-report.json')
    promotion=read_json(root/'promotion.json')
    lines=['# Mio 离线评测报告','',f'生成时间：{report["generated_at"]}','',
           '**这是一份固定剧本设施基线，不是模型可靠性或上线资格结论。**','',
           '| 任务 | 成功/运行 | pass³（设施） | 模型资格证据 |','| --- | --- | --- | --- |']
    for g in report['tasks']['groups']:
        lines.append(f'| {g["task_id"]} | {g["successes"]}/{g["trials"]} | {g["pass_power_3"]:.3f} | {"是" if g["qualifies_as_model_evidence"] else "否"} |')
    observations=sum(a['count'] for g in injection['groups'] for a in g['arms'])
    blocked=[c['name'] for c in promotion['checks'] if not c['passed']]
    lines+=['',f'注入对照：{len(injection["groups"])} 组，{observations} 条观察；固定攻击模型只验证权限链与四因子接线。','',
            f'晋升评审：`{promotion["status"]}`；生产配置未改动。','',
            '未满足的证据或门禁：','']+['- '+name for name in blocked]+['',
            '原始数据、指标与诊断见同目录 JSON/JSONL；真实模型、记忆、审核、人评与线上影子证据需要独立采集。','']
    (root/'summary.md').write_text('\n'.join(lines))
