#!/usr/bin/env python3
"""Install a checksum-pinned, rootless MySQL binary in the GitHub runner's temporary directory."""
import hashlib
import os
from pathlib import Path
import platform
import tarfile
import tempfile
import urllib.request

VERSION = '8.4.11'
ARCHIVE = f'mysql-{VERSION}-linux-glibc2.28-x86_64-minimal.tar.xz'
URL = 'https://cdn.mysql.com/Downloads/MySQL-8.4/' + ARCHIVE
SHA256 = '383f54e124d5f325d67f0c6912a8f96814eedc761a17ea30112e52fa4cc6b143'


def install(environment):
    if platform.system() != 'Linux' or platform.machine() != 'x86_64' or environment.get('GITHUB_ACTIONS') != 'true':
        raise ValueError('This installer is only for the Linux x86_64 GitHub runner')
    root = Path(environment['RUNNER_TEMP']).resolve(strict=True)
    destination = root / 'mongle-mysql84'
    if destination.exists() or destination.is_symlink():
        raise ValueError('Refusing to overwrite an existing MySQL installation')
    with tempfile.TemporaryDirectory(prefix='mysql-download-', dir=root) as directory:
        archive = Path(directory) / ARCHIVE
        value = hashlib.sha256()
        count = 0
        with urllib.request.urlopen(URL, timeout=120) as response, archive.open('xb') as target:
            if not response.geturl().startswith('https://'):
                raise ValueError('HTTPS download required')
            for block in iter(lambda: response.read(1024 * 1024), b''):
                count += len(block)
                if count > 100 * 1024 * 1024:
                    raise ValueError('MySQL download exceeded expected size')
                value.update(block)
                target.write(block)
        if value.hexdigest() != SHA256:
            raise ValueError('MySQL archive checksum mismatch')
        extracted = Path(directory) / 'extracted'
        with tarfile.open(archive, 'r:xz') as source:
            source.extractall(extracted, filter='data')
        top = list(extracted.iterdir())
        if len(top) != 1 or top[0].name != ARCHIVE.removesuffix('.tar.xz') or top[0].is_symlink():
            raise ValueError('Unexpected MySQL archive layout')
        top[0].rename(destination)
    # Ubuntu 24.04's t64 library keeps the same ABI but has a different filename.
    compatibility = destination / 'compat'
    compatibility.mkdir()
    aio = Path('/usr/lib/x86_64-linux-gnu/libaio.so.1t64')
    if not aio.is_file():
        raise ValueError('Install libaio1t64 before installing the MySQL binary')
    (compatibility / 'libaio.so.1').symlink_to(aio)
    with Path(environment['GITHUB_PATH']).open('a') as target:
        target.write(str(destination / 'bin') + '\n')
    with Path(environment['GITHUB_ENV']).open('a') as target:
        target.write('LD_LIBRARY_PATH=' + str(compatibility) + '\n')
    print(f'Installed checksum-verified MySQL {VERSION} for disposable CI tests; no system DB configured.')


if __name__ == '__main__':
    install(os.environ)
