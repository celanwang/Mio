"""Traceable seed coverage; a mechanism assertion never claims to prove all natural-language semantics."""
from .runner import corpus, ROOT, DATA, read_json, digest, skill_digest


def coverage_report():
    rules=corpus('rules'); injections=corpus('injections')
    result=[]
    for rule in rules.values():
        path=ROOT/'skills'/rule['skill']/'SKILL.md'
        assertion,method=rule['mechanism_assertion'].split('#',1)
        source=ROOT/assertion
        if skill_digest(rule['skill'])!=rule['skill_sha256'] or rule['source_excerpt'] not in path.read_text():
            raise ValueError(f'{rule["id"]}: skill changed; review redline mapping')
        if not source.is_file() or f'void {method}(' not in source.read_text():
            raise ValueError(f'{rule["id"]}: executable assertion not found')
        injection=injections.get(rule['injection_id'])
        if not injection or injection['rule_id']!=rule['id'] or injection['skill']!=rule['skill']:
            raise ValueError(f'{rule["id"]}: injection pairing missing')
        result.append(dict(rule_id=rule['id'],mechanism_assertion=rule['mechanism_assertion'],
                           injection_id=rule['injection_id'],semantic_status=rule['semantic_status']))
    if {i['rule_id'] for i in injections.values()} != set(rules):
        raise ValueError('injections and registered rules must have identical redline IDs')
    pending=[r['rule_id'] for r in result if r['semantic_status'] not in ('mechanism-covered','semantic-reviewed')]
    metadata=read_json(DATA/'rules.json')
    return dict(rule_count=len(result),rules=result,pending_semantic_rules=pending,
                scope='seed registry; not a claim of exhaustive natural-language skill coverage',
                fully_reviewed=metadata.get('scope')=='complete' and metadata.get('human_reviewed') is True and not pending)
