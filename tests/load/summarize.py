import collections
import json
from pathlib import Path
import sys


def summarize(directory):
    directory = Path(directory)
    summary = json.loads((directory / 'summary.json').read_text(encoding='utf-8'))
    statuses = collections.Counter()
    errors = collections.Counter()
    for line in (directory / 'points.json').open(encoding='utf-8'):
        point = json.loads(line)
        if point.get('type') != 'Point':
            continue
        tags = point['data'].get('tags', {})
        key = (tags.get('endpoint'), tags.get('status'), tags.get('code'))
        if point['metric'] == 'status_codes':
            statuses[key] += point['data']['value']
        if point['metric'] == 'error_codes':
            errors[key] += point['data']['value']
    metrics = summary['metrics']
    endpoints = {}
    for name, metric in metrics.items():
        if name.startswith('latency_'):
            endpoint = name[len('latency_'):]
            count = metrics['requests_' + endpoint]['values']
            endpoints[endpoint] = dict(metric['values'], **count,
                                       error_rate=metrics['failures_' + endpoint]['values']['rate'])
    forbidden = []
    access_counts = collections.Counter()
    for log in (directory / 'access').glob('*'):
        for line in log.read_text(encoding='utf-8').splitlines():
            parts = line.split()
            if len(parts) >= 3:
                access_counts[parts[1]] += 1
                if any(word in parts[1] for word in ['/agent/', '/internal/', '/api/admin/agent/']):
                    forbidden.append(line)
    samples = [json.loads(line) for line in (directory / 'resources.jsonl').read_text().splitlines() if line.strip()]
    resources = {}
    for sample in samples:
        for process in sample['processes']:
            name = process['ProcessName']
            entry = resources.setdefault(name, {'peak_working_set_bytes': 0, 'peak_private_bytes': 0, 'cpu_seconds': None})
            entry['peak_working_set_bytes'] = max(entry['peak_working_set_bytes'], process['WorkingSet64'])
            entry['peak_private_bytes'] = max(entry['peak_private_bytes'], process['PrivateMemorySize64'])
    for previous, current in zip(samples, samples[1:]):
        before = {p['Id']: p for p in previous['processes']}
        for process in current['processes']:
            old = before.get(process['Id'])
            if old and old['CPU'] is not None and process['CPU'] is not None:
                entry = resources[process['ProcessName']]
                entry['cpu_seconds'] = (entry['cpu_seconds'] or 0) + max(0, process['CPU'] - old['CPU'])
    elapsed = (samples[-1]['time'] - samples[0]['time']) / 1000 if len(samples) > 1 else 0
    for entry in resources.values():
        entry['sampled_seconds'] = elapsed
        entry['average_cpu_cores'] = entry['cpu_seconds'] / elapsed if elapsed and entry['cpu_seconds'] is not None else None
    write_count = sum(v['count'] for k, v in endpoints.items() if k in ['comment_write', 'likes', 'favorites'])
    if sum(statuses.values()) != metrics['http_reqs']['values']['count']:
        raise ValueError('Status distribution does not account for every request')
    result = {
        'resources': resources,
        'actual_write_fraction': write_count / metrics['http_reqs']['values']['count'],
        'run': json.loads((directory / 'run.json').read_text()),
        'total': {key: metrics[key]['values'] for key in ['http_reqs', 'http_req_duration', 'business_errors']},
        'endpoints': endpoints,
        'status_distribution': [{'endpoint': k[0], 'status': k[1], 'count': v} for k, v in sorted(statuses.items())],
        'error_distribution': [{'endpoint': k[0], 'status': k[1], 'code': k[2], 'count': v} for k, v in sorted(errors.items())],
        'forbidden_access_count': len(forbidden),
        'access_request_count': sum(access_counts.values()),
    }
    (directory / 'sanitized-summary.json').write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding='utf-8')
    print(json.dumps(result, indent=2, ensure_ascii=False))
    if forbidden:
        raise SystemExit('Forbidden access paths found')


if __name__ == '__main__':
    summarize(sys.argv[1])
