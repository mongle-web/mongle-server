#!/usr/bin/env python3
"""Upload the verified CI files and await one pinned SSM command."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time

import verify_ci


def aws(*arguments):
    result = subprocess.run(['aws', *arguments, '--region', 'ap-northeast-2', '--output', 'json',
                             '--no-cli-pager'], check=True, capture_output=True, text=True, timeout=120)
    return json.loads(result.stdout) if result.stdout.strip() else {}


def config(environment):
    result = {key: environment.get('MONGLE_CD_' + key, '') for key in
              ('BUCKET', 'INSTANCE_ID', 'SSM_DOCUMENT_VERSION', 'SSM_DOCUMENT_SHA256')}
    if not re.fullmatch(r'[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]', result['BUCKET']):
        raise ValueError('Missing/invalid deployment bucket')
    if not re.fullmatch(r'i-[0-9a-f]{17}', result['INSTANCE_ID']):
        raise ValueError('Missing/invalid instance ID')
    if (not re.fullmatch(r'[1-9][0-9]{0,9}', result['SSM_DOCUMENT_VERSION'])
            or not re.fullmatch(r'[0-9a-f]{64}', result['SSM_DOCUMENT_SHA256'])):
        raise ValueError('Numeric SSM document version and SHA256 are required')
    return result


def upload_immutable(bucket, key, path, client=aws):
    try:
        client('s3api', 'put-object', '--bucket', bucket, '--key', key,
               '--body', str(path), '--if-none-match', '*', '--server-side-encryption', 'AES256')
    except subprocess.CalledProcessError as error:
        # Retries may reuse an already uploaded object, but may never replace it.
        if 'PreconditionFailed' not in error.stderr:
            raise
        with tempfile.TemporaryDirectory() as directory:
            existing = Path(directory) / 'existing'
            client('s3api', 'get-object', '--bucket', bucket, '--key', key, str(existing))
            if hashlib.sha256(existing.read_bytes()).digest() != hashlib.sha256(path.read_bytes()).digest():
                raise ValueError('Existing deployment object differs') from error


def main():
    settings = config(os.environ)
    event = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text())
    api = verify_ci.GitHub(os.environ['GH_TOKEN'])
    # Re-check GitHub state after obtaining AWS credentials, immediately before publication.
    identity = verify_ci.inspect(event, api, os.environ)
    identity = verify_ci.verify_artifact(Path('build/deploy-artifact'), identity)
    prefix = f"releases/{identity['commit']}/{identity['run_id']}/{identity['run_attempt']}/"
    for name in (identity['jar'], 'SHA256SUMS', 'build-metadata.json'):
        upload_immutable(settings['BUCKET'], prefix + name, Path('build/deploy-artifact') / name)
    # A push during upload must not install the now-outdated revision.
    verify_ci.inspect(event, api, os.environ)
    parameters = {key: [identity[value]] for key, value in
                  [('CommitSha', 'commit'), ('JarSha256', 'sha256'), ('RunId', 'run_id'),
                   ('RunNumber', 'run_number'), ('RunAttempt', 'run_attempt')]}
    command = aws('ssm', 'send-command', '--document-name', 'Mongle-DeployV1',
                  '--document-version', settings['SSM_DOCUMENT_VERSION'],
                  '--document-hash', settings['SSM_DOCUMENT_SHA256'], '--document-hash-type', 'Sha256',
                  '--instance-ids', settings['INSTANCE_ID'], '--parameters', json.dumps(parameters),
                  '--timeout-seconds', '120', '--comment', 'Mongle CI ' + identity['run_id'])
    command_id = command['Command']['CommandId']
    record = {**identity, 'commandId': command_id, 'instanceId': settings['INSTANCE_ID'], 'status': 'Pending'}
    record_path = Path('build/deployment-result.json')
    record_path.write_text(json.dumps(record, indent=2) + '\n')
    print(f'SSM command {command_id}, commit {identity["commit"]}', flush=True)
    deadline = time.monotonic() + 1050
    try:
        while time.monotonic() < deadline:
            try:
                result = aws('ssm', 'get-command-invocation', '--command-id', command_id,
                             '--instance-id', settings['INSTANCE_ID'])
            except subprocess.CalledProcessError as error:
                if 'InvocationDoesNotExist' in error.stderr:
                    time.sleep(5)
                    continue
                raise
            record['status'] = result['Status']
            record_path.write_text(json.dumps(record, indent=2) + '\n')
            if result['Status'] in ('Pending', 'InProgress', 'Delayed'):
                time.sleep(5)
                continue
            # Only our fixed deploy script emits JSON; do not dump arbitrary remote logs.
            for line in result.get('StandardOutputContent', '').splitlines():
                try:
                    value = json.loads(line)
                except ValueError:
                    continue
                if value.get('event'):
                    print(json.dumps(value, sort_keys=True))
            if result['Status'] != 'Success' or result.get('ResponseCode') != 0:
                raise RuntimeError('SSM deployment did not succeed: ' + result['Status'])
            print('Deployment completed with internal and public health checks.')
            return
        raise TimeoutError('SSM outcome unknown; inspect the recorded command before retrying')
    finally:
        # Never cancel an in-flight command: it might be restoring the previous JAR.
        record_path.write_text(json.dumps(record, indent=2) + '\n')


if __name__ == '__main__':
    main()
