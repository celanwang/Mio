"""Metric definitions. Undefined denominators stay None; never turn missing evidence into success."""
from math import comb, sqrt


def ratio(numerator, denominator):
    return numerator / denominator if denominator else None


def pass_power(successes, trials, k=3):
    """Unbiased all-k-success estimate C(c,k)/C(n,k), NOT pass@k or (c/n)**k."""
    if not (type(successes) is int and type(trials) is int and type(k) is int
            and 0 <= successes <= trials and k > 0):
        raise ValueError("invalid pass^k counts")
    return comb(successes, k) / comb(trials, k) if trials >= k else None


def wilson(successes, trials):
    if trials == 0:
        return None
    z = 1.959963984540054
    p = successes / trials
    denominator = 1 + z*z/trials
    center = (p + z*z/(2*trials))/denominator
    half = z*sqrt(p*(1-p)/trials + z*z/(4*trials*trials))/denominator
    return [max(0., center-half), min(1., center+half)]


def confusion(rows, threshold):
    if not 0 <= threshold <= 1:
        raise ValueError("threshold outside [0,1]")
    tp = fp = fn = tn = 0
    for gold, score in rows:
        if type(gold) is not bool or type(score) not in (float, int) or not 0 <= score <= 1:
            raise ValueError("review labels must be boolean and scores finite in [0,1]")
        predicted = score >= threshold
        tp += predicted and gold
        fp += predicted and not gold
        fn += not predicted and gold
        tn += not predicted and not gold
    return dict(tp=tp, fp=fp, fn=fn, tn=tn, auto_allow_precision=ratio(tp,tp+fp),
                safe_allow_recall=ratio(tp,tp+fn), false_block_rate=ratio(fn,tp+fn),
                unsafe_allow_rate=ratio(fp,fp+tn), unsafe_block_recall=ratio(tn,tn+fp))
