#!/usr/bin/env python3
"""Verify GitHub platform evidence, then the CI artifact's separate provenance."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import urllib.request

REPOSITORY = 'mongle-web/mongle-server'
WORKFLOW = '.github/workflows/server-ci.yml'


class GitHub:
    def __init__(self, token):
        self.token = token

    def get(self, path):
        request = urllib.request.Request('https://api.github.com/repos/' + REPOSITORY + '/' + path,
                                        headers={'Authorization': 'Bearer ' + self.token,
                                                 'Accept': 'application/vnd.github+json',
                                                 'X-GitHub-Api-Version': '2022-11-28'})
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.load(response)

    def all(self, path, key):
        result, page = [], 1
        while True:
            data = self.get(f'{path}?per_page=100&page={page}')[key]
            result.extend(data)
            if len(data) < 100:
                return result
            page += 1


def inspect(event, api, environment):
    if (environment.get('GITHUB_REPOSITORY') != REPOSITORY
            or environment.get('GITHUB_EVENT_NAME') != 'workflow_run'
            or environment.get('GITHUB_REF') != 'refs/heads/develop'):
        raise ValueError('Only the trusted develop workflow_run context may deploy')
    reported = event['workflow_run']
    identity = reported['id']
    if not isinstance(identity, int) or identity <= 0:
        raise ValueError('Invalid workflow run ID')
    run = api.get(f'actions/runs/{identity}')
    workflow = api.get('actions/workflows/server-ci.yml')
    for actual in (reported, run):
        if (actual.get('event') != 'push' or actual.get('head_branch') != 'develop'
                or actual.get('status') != 'completed' or actual.get('conclusion') != 'success'
                or actual.get('head_repository', {}).get('full_name') != REPOSITORY
                or actual.get('workflow_id') != workflow['id']
                or not re.fullmatch(r'[0-9a-f]{40}', actual.get('head_sha', ''))):
            raise ValueError('CI must be a successful same-repository develop push')
    if (run.get('path') != WORKFLOW or run['head_sha'] != reported['head_sha']
            or run['run_attempt'] != reported['run_attempt']):
        raise ValueError('Workflow path/revision/attempt does not match the completed event')
    latest = api.get('git/ref/heads/develop')['object']['sha']
    if latest != run['head_sha']:
        raise ValueError('A newer develop commit exists; stale deployment refused')
    jobs = api.all(f"actions/runs/{identity}/attempts/{run['run_attempt']}/jobs", 'jobs')
    if len([job for job in jobs if job['name'] == 'Test and build'
            and job['status'] == 'completed' and job['conclusion'] == 'success']) != 1:
        raise ValueError('Required test/build job did not succeed in this attempt')
    name = f"server-jar-{run['head_sha']}-{identity}-{run['run_attempt']}"
    artifacts = api.all(f'actions/runs/{identity}/artifacts', 'artifacts')
    candidates = [a for a in artifacts if a['name'] == name and not a['expired']]
    if len(candidates) != 1:
        raise ValueError('Expected exactly one live artifact from the successful CI attempt')
    if (candidates[0].get('workflow_run', {}).get('id') != identity
            or candidates[0]['workflow_run'].get('head_sha') != run['head_sha']):
        raise ValueError('Artifact run/revision mismatch')
    return {'commit': run['head_sha'], 'run_id': str(identity),
            'run_number': str(run['run_number']), 'run_attempt': str(run['run_attempt']),
            'artifact_id': str(candidates[0]['id'])}


def verify_artifact(directory, identity):
    directory = Path(directory)
    filename = f"mongle-{identity['commit']}.jar"
    names = {filename, 'SHA256SUMS', 'build-metadata.json'}
    if directory.is_symlink() or {p.name for p in directory.iterdir()} != names:
        raise ValueError('Artifact must contain exactly JAR, checksum and metadata')
    if any(p.is_symlink() or not p.is_file() for p in directory.iterdir()):
        raise ValueError('Artifact may only contain regular files')
    metadata = json.loads((directory / 'build-metadata.json').read_text())
    required = {'schemaVersion': 1, 'repository': REPOSITORY, 'commitSha': identity['commit'],
                'headSha': identity['commit'], 'eventName': 'push', 'ref': 'refs/heads/develop',
                'runId': identity['run_id'], 'runAttempt': identity['run_attempt'],
                'jar': filename, 'deploymentEligible': True}
    if any(type(metadata.get(k)) is not type(v) or metadata[k] != v for k, v in required.items()):
        raise ValueError('Artifact provenance mismatch')
    digest = hashlib.sha256()
    with (directory / filename).open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(block)
    checksum = digest.hexdigest()
    if (metadata.get('sha256') != checksum
            or (directory / 'SHA256SUMS').read_text() != f'{checksum}  {filename}\n'):
        raise ValueError('Artifact checksum mismatch')
    return {**identity, 'sha256': checksum, 'jar': filename}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--artifact-directory')
    args = parser.parse_args()
    event = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text())
    identity = inspect(event, GitHub(os.environ['GH_TOKEN']), os.environ)
    if args.artifact_directory:
        identity = verify_artifact(args.artifact_directory, identity)
        Path('build/deployment.json').write_text(json.dumps(identity, indent=2) + '\n')
    with Path(os.environ['GITHUB_OUTPUT']).open('a') as output:
        for key, value in identity.items():
            output.write(f'{key}={value}\n')
    print(json.dumps(identity, sort_keys=True))


if __name__ == '__main__':
    main()
