#!/usr/bin/env python3
"""Run mysqlTest against a disposable native MySQL 8.4 server, never an external DB."""
import os
import json
from pathlib import Path
import secrets
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time


def main():
    repo = Path(__file__).resolve().parents[1]
    mysqld = shutil.which("mysqld")
    mysql = shutil.which("mysql")
    openssl = shutil.which("openssl")
    keytool = shutil.which("keytool")
    if not all((mysqld, mysql, openssl, keytool)):
        raise RuntimeError("Install native MySQL 8.4, OpenSSL and Java 21 first; no Docker is used.")
    version = subprocess.check_output([mysqld, "--version"], text=True)
    if "Ver 8.4." not in version:
        raise RuntimeError("This regression suite requires MySQL 8.4.x.")
    os.umask(0o077)
    # A short socket path also works on macOS (Unix sockets have a length limit).
    with tempfile.TemporaryDirectory(prefix="mongle84-", dir="/tmp") as directory:
        base = Path(directory).resolve()
        data = base / "data"
        process = None
        client_env = os.environ.copy()
        client_env.pop("MYSQL_PWD", None)
        with (base / "fixture.log").open("w") as log:
            def run(args, **kwargs):
                return subprocess.run(args, stdout=log, stderr=log, check=True, timeout=120, **kwargs)

            def sql(query, database=None):
                args = [mysql, f"--defaults-file={base / 'admin.cnf'}", "--no-login-paths", "--batch", "--skip-column-names"]
                if database:
                    args.append(database)
                return subprocess.check_output(args, input=query, text=True, stderr=log, timeout=60, env=client_env)

            def stop_on_signal(signum, frame):
                raise KeyboardInterrupt

            old_handlers = {s: signal.signal(s, stop_on_signal) for s in (signal.SIGINT, signal.SIGTERM)}
            try:
                run([openssl, "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
                     "-subj", "/CN=Mongle disposable test CA", "-keyout", str(base / "ca.key"),
                     "-out", str(base / "ca.pem"), "-addext", "basicConstraints=critical,CA:TRUE"])
                run([openssl, "req", "-newkey", "rsa:2048", "-nodes", "-subj", "/CN=localhost",
                     "-keyout", str(base / "server.key"), "-out", str(base / "server.csr")])
                (base / "server.ext").write_text("subjectAltName=DNS:localhost,IP:127.0.0.1\nextendedKeyUsage=serverAuth\n")
                run([openssl, "x509", "-req", "-days", "1", "-in", str(base / "server.csr"),
                     "-CA", str(base / "ca.pem"), "-CAkey", str(base / "ca.key"), "-CAcreateserial",
                     "-out", str(base / "server.pem"), "-extfile", str(base / "server.ext")])
                password = secrets.token_hex(24)
                env = os.environ.copy()
                env.update(DB_TRUSTSTORE_PASSWORD=secrets.token_hex(24))
                run([keytool, "-importcert", "-noprompt", "-alias", "test-root", "-file", str(base / "ca.pem"),
                     "-keystore", str(base / "truststore.p12"), "-storetype", "PKCS12",
                     "-storepass:env", "DB_TRUSTSTORE_PASSWORD"], env=env)
                basedir = Path(mysqld).resolve().parents[1]
                run([mysqld, "--no-defaults", "--initialize-insecure", f"--basedir={basedir}", f"--datadir={data}"])
                with socket.socket() as listener:
                    listener.bind(("127.0.0.1", 0))
                    port = listener.getsockname()[1]
                (base / "admin.cnf").write_text(f"[client]\nuser=root\nprotocol=socket\nsocket={base / 'mysql.sock'}\n")
                process = subprocess.Popen([
                    mysqld, "--no-defaults", f"--basedir={basedir}", f"--datadir={data}",
                    "--bind-address=127.0.0.1", f"--port={port}", f"--socket={base / 'mysql.sock'}",
                    f"--pid-file={base / 'mysql.pid'}", "--mysqlx=OFF", "--innodb-buffer-pool-size=64M",
                    "--max-connections=64", "--require-secure-transport=ON",
                    f"--ssl-ca={base / 'ca.pem'}", f"--ssl-cert={base / 'server.pem'}", f"--ssl-key={base / 'server.key'}"
                ], stdout=log, stderr=log)
                for _ in range(120):
                    if process.poll() is not None:
                        raise RuntimeError("Disposable MySQL failed to start.")
                    try:
                        sql("SELECT 1;")
                        break
                    except subprocess.CalledProcessError:
                        time.sleep(0.25)
                else:
                    raise RuntimeError("Disposable MySQL startup timed out.")
                admin_password = secrets.token_hex(24)
                sql(f"ALTER USER 'root'@'localhost' IDENTIFIED BY '{admin_password}';")
                with (base / "admin.cnf").open("a") as handle:
                    handle.write(f"password={admin_password}\n")
                sql(f"CREATE USER 'mongle_test'@'127.0.0.1' IDENTIFIED BY '{password}' REQUIRE SSL;")
                migration_password = secrets.token_hex(24)
                sql(f"CREATE USER 'mongle_migrator'@'127.0.0.1' IDENTIFIED BY '{migration_password}' REQUIRE SSL;")
                migration_databases = ("flyway_fresh", "flyway_existing", "flyway_drift", "flyway_failed",
                                       "flyway_checksum", "flyway_privilege", "flyway_jpa", "flyway_v1", "flyway_cli", "flyway_check")
                schema = (repo / "src/main/resources/db/schema-initial.sql").read_text()
                for database in migration_databases:
                    sql(f"CREATE DATABASE {database} CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;")
                    sql(f"GRANT SELECT,INSERT,UPDATE,DELETE ON {database}.* TO 'mongle_test'@'127.0.0.1';")
                    sql(f"GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,REFERENCES ON {database}.* TO 'mongle_migrator'@'127.0.0.1';")
                    if database in ("flyway_existing", "flyway_drift", "flyway_privilege", "flyway_check"):
                        sql(schema, database)
                for database in ("credit_test", "payment_test", "upgrade_test", "credit_migration_test"):
                    sql(f"CREATE DATABASE {database} CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;")
                    sql(f"GRANT SELECT,INSERT,UPDATE,DELETE ON {database}.* TO 'mongle_test'@'127.0.0.1';")
                    if database in ("credit_test", "payment_test"):
                        sql(f"GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,REFERENCES ON {database}.* TO 'mongle_migrator'@'127.0.0.1';")
                    if database not in ("upgrade_test", "credit_migration_test"):
                        sql(schema, database)
                credit_prefix, credit_marker, _ = schema.partition("CREATE TABLE IF NOT EXISTS credit_accounts")
                if not credit_marker:
                    raise RuntimeError("Credit schema boundary changed; review the fixture.")
                sql(credit_prefix, "credit_migration_test")
                sql((repo / "src/main/resources/db/migrations/20261026-credit-ledger.sql").read_text(), "credit_migration_test")
                if sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='credit_migration_test' AND table_name LIKE 'credit_%';").strip() != "5":
                    raise RuntimeError("Credit migration did not create all five tables.")
                # Reproduce the existing 29-table schema and apply only the #66 delta.
                prefix, marker, _ = schema.partition("-- Existing DB: stop app/workers and back up before applying.")
                if not marker:
                    raise RuntimeError("Initial schema boundary changed; review the upgrade fixture.")
                sql(prefix, "upgrade_test")
                sql("INSERT INTO users(email,nickname,created_at,updated_at) VALUES('preserved@test.invalid','keep',NOW(),NOW());", "upgrade_test")
                if sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='upgrade_test';").strip() != "29":
                    raise RuntimeError("Expected 29 tables before the world composition migration.")
                sql((repo / "src/main/resources/db/migrations/20261026-world-composition.sql").read_text(), "upgrade_test")
                sql((repo / "src/main/resources/db/migrations/20261026-world-images.sql").read_text(), "upgrade_test")
                sql((repo / "src/main/resources/db/migrations/20261026-world-images.sql").read_text(), "upgrade_test")
                for catalog in ("columns", "statistics"):
                    fields = ("TABLE_NAME,COLUMN_NAME,ORDINAL_POSITION,COLUMN_TYPE,IS_NULLABLE,COALESCE(COLUMN_DEFAULT,'<NULL>'),EXTRA,COLLATION_NAME"
                              if catalog == "columns" else "TABLE_NAME,INDEX_NAME,NON_UNIQUE,SEQ_IN_INDEX,COLUMN_NAME,INDEX_TYPE")
                    snapshots = [sql(f"SELECT {fields} FROM information_schema.{catalog} WHERE table_schema='{name}' ORDER BY {fields};")
                                 for name in ("credit_test", "upgrade_test")]
                    if snapshots[0] != snapshots[1]:
                        raise RuntimeError(f"Fresh and upgraded schemas differ in {catalog}.")
                if sql("SELECT nickname FROM users WHERE email='preserved@test.invalid';", "upgrade_test").strip() != "keep":
                    raise RuntimeError("Upgrade did not preserve the fixture row.")
                env.update(MONGLE_MYSQL_TEST_PORT=str(port), MONGLE_MYSQL_TEST_PASSWORD=password,
                           MONGLE_MYSQL_MIGRATOR_PASSWORD=migration_password,
                           DB_URL=f"jdbc:mysql://127.0.0.1:{port}/credit_test", DB_USERNAME="mongle_test", DB_PASSWORD=password,
                           DB_TRUSTSTORE_URL=(base / "truststore.p12").as_uri())
                print("MySQL 8.4: fresh schema and 29→33 upgrade match; running JDBC/Flyway regressions.", flush=True)
                result = subprocess.run([str(repo / "gradlew"), "--no-daemon", "mysqlTest", "--rerun-tasks"], cwd=repo, env=env)
                if result.returncode:
                    return result.returncode
                jars = [path for path in (repo / "build/libs").glob("*.jar") if not path.name.endswith("-plain.jar")]
                if len(jars) != 1:
                    raise RuntimeError("Build one bootJar before running native MySQL verification.")
                cli_env = {**env,
                           "MONGLE_MIGRATION_DB_URL": f"jdbc:mysql://127.0.0.1:{port}/flyway_cli",
                           "MONGLE_MIGRATION_EXPECTED_HOST": "127.0.0.1",
                           "MONGLE_MIGRATION_EXPECTED_DATABASE": "flyway_cli",
                           "MONGLE_MIGRATION_DB_USERNAME": "mongle_migrator",
                           "MONGLE_MIGRATION_DB_PASSWORD": migration_password,
                           "MONGLE_MIGRATION_TRUSTSTORE_URL": (base / "truststore.p12").as_uri(),
                           "MONGLE_MIGRATION_TRUSTSTORE_PASSWORD": env["DB_TRUSTSTORE_PASSWORD"]}
                for operation in ("info", "migrate", "migrate"):
                    process_result = subprocess.run(["java", "-Xmx256m", "-jar", str(jars[0]),
                                                     "--db-migration=" + operation],
                                                    cwd=repo, env=cli_env, capture_output=True, text=True, timeout=120)
                    if process_result.returncode:
                        raise RuntimeError("Packaged migration process failed; raw output suppressed.")
                    outcome = json.loads(process_result.stdout)
                    if outcome.get("event") != "migration-completed" or outcome.get("operation") != operation:
                        raise RuntimeError("Packaged migration completion evidence missing.")
                if outcome.get("migrationsExecuted") != 0:
                    raise RuntimeError("Repeated packaged migration was not idempotent.")
                print("Packaged JAR: info/migrate/repeat completed without application startup.", flush=True)
                return 0
            except (Exception, KeyboardInterrupt):
                # Raw SQL/client output may contain generated secrets; never dump fixture.log.
                print("Disposable MySQL verification failed; temporary data will be removed.", file=sys.stderr)
                raise
            finally:
                if process is not None and process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=30)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=10)
                for sig, handler in old_handlers.items():
                    signal.signal(sig, handler)


if __name__ == "__main__":
    sys.exit(main())
