#!/usr/bin/env python3
"""Render reviewable, least-privilege AWS files; does not call AWS or modify EC2."""
import argparse
import json
from pathlib import Path
import re

ACCOUNT = '800417964658'
REGION = 'ap-northeast-2'
SUBJECT = 'repo:mongle-web@330728028/mongle-server@1392341966:ref:refs/heads/develop'


def render(bucket, instance):
    if (not re.fullmatch(r'[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]', bucket)
            or not re.fullmatch(r'i-[0-9a-f]{17}', instance)):
        raise ValueError('Invalid bucket or instance ID')
    objects = f'arn:aws:s3:::{bucket}/releases/*'
    document = f'arn:aws:ssm:{REGION}:{ACCOUNT}:document/Mongle-DeployV1'
    provider = f'arn:aws:iam::{ACCOUNT}:oidc-provider/token.actions.githubusercontent.com'
    trust = {'Version': '2012-10-17', 'Statement': [{
        'Effect': 'Allow', 'Principal': {'Federated': provider},
        'Action': 'sts:AssumeRoleWithWebIdentity', 'Condition': {'StringEquals': {
            'token.actions.githubusercontent.com:aud': 'sts.amazonaws.com',
            'token.actions.githubusercontent.com:sub': SUBJECT}}}]}
    github_policy = {'Version': '2012-10-17', 'Statement': [
        {'Sid': 'PublishDeploymentObjects', 'Effect': 'Allow',
         'Action': ['s3:PutObject', 's3:GetObject'], 'Resource': objects},
        {'Sid': 'RunFixedDeployment', 'Effect': 'Allow', 'Action': 'ssm:SendCommand',
         'Resource': [document, f'arn:aws:ec2:{REGION}:{ACCOUNT}:instance/{instance}']},
        {'Sid': 'ReadCommandResult', 'Effect': 'Allow',
         'Action': 'ssm:GetCommandInvocation', 'Resource': '*'}]}
    ec2_policy = {'Version': '2012-10-17', 'Statement': [{
        'Sid': 'ReadDeploymentObjects', 'Effect': 'Allow', 'Action': 's3:GetObject', 'Resource': objects}]}
    parameters = {}
    for name in ('CommitSha', 'JarSha256', 'RunId', 'RunNumber', 'RunAttempt'):
        pattern = '[0-9a-f]{40}' if name == 'CommitSha' else (
            '[0-9a-f]{64}' if name == 'JarSha256' else '[1-9][0-9]{0,19}')
        parameters[name] = {'type': 'String', 'interpolationType': 'ENV_VAR', 'allowedPattern': '^' + pattern + '$'}
    parameters['MigrationProtocol'] = {'type': 'String', 'interpolationType': 'ENV_VAR',
                                       'allowedPattern': '^flyway-v1$'}
    ssm = {'schemaVersion': '2.2', 'description': 'Deploy one verified Mongle JAR using the root-installed deployer',
           'parameters': parameters, 'mainSteps': [{
               'action': 'aws:runShellScript', 'name': 'deployMongle',
               'precondition': {'StringEquals': ['platformType', 'Linux']},
               'inputs': {'timeoutSeconds': '900', 'runCommand': [
                   'set -eu', '/usr/bin/python3 -I /usr/local/libexec/mongle/deploy-flyway.py']}}]}
    bucket_policy = {'Version': '2012-10-17', 'Statement': [{
        'Sid': 'DenyInsecureTransport', 'Effect': 'Deny', 'Principal': '*', 'Action': 's3:*',
        'Resource': [f'arn:aws:s3:::{bucket}', f'arn:aws:s3:::{bucket}/*'],
        'Condition': {'Bool': {'aws:SecureTransport': 'false'}}}]}
    config = {'bucket': bucket, 'region': REGION, 'prefix': 'releases/', 'aws': '/snap/bin/aws',
              'publicHealth': 'https://api.mongle.art/actuator/health', 'databaseMigration': True}
    return {'github-trust.json': trust, 'github-policy.json': github_policy,
            'ec2-policy.json': ec2_policy, 'ssm-deploy.json': ssm,
            'bucket-policy.json': bucket_policy, 'deploy.json': config}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--bucket', required=True)
    parser.add_argument('--instance-id', required=True)
    parser.add_argument('--output', default='build/cd-infra')
    args = parser.parse_args()
    result = render(args.bucket, args.instance_id)
    destination = Path(args.output)
    destination.mkdir(parents=True, exist_ok=True)
    for name, value in result.items():
        (destination / name).write_text(json.dumps(value, indent=2) + '\n')
    print(f'Rendered {len(result)} configuration files in {destination}; no AWS changes made.')


if __name__ == '__main__':
    main()
