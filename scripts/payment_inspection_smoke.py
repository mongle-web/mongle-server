"""Packaged read-only operator command checks, only against mysql84-test.py's fixture."""
import hashlib
import json
import subprocess
import uuid


def verify(repo, jar, credit_env, sql):
    if not credit_env["DB_URL"].startswith("jdbc:mysql://127.0.0.1:") or not credit_env["DB_URL"].endswith("/credit_test"):
        raise RuntimeError("Disposable loopback fixture required.")
    env = {**credit_env, "MONGLE_PAYMENT_INSPECT_ENABLED": "true",
           "MONGLE_PAYMENT_INSPECT_EXPECTED_HOST": "127.0.0.1",
           "MONGLE_PAYMENT_INSPECT_EXPECTED_DATABASE": "credit_test",
           "MONGLE_PAYMENT_ENABLED": "false", "MONGLE_PAYAPP_ENABLED": "false",
           "MONGLE_CREDIT_ENABLED": "false"}
    order = str(uuid.uuid4())
    owner = sql("INSERT INTO users(email,nickname,created_at,updated_at) VALUES('inspection-cli@test.invalid','Inspect',NOW(),NOW()); SELECT LAST_INSERT_ID();", "credit_test").strip()
    sql(f"""INSERT INTO payment_orders(public_id,user_id,request_id,input_hash,notification_token,product_id,product_name,price,currency,credits,state,payment_url,request_deadline,version,created_at,updated_at)
        VALUES('{order}',{owner},'{uuid.uuid4()}','private-input-hash','private-notification-token','credit-pack','private-product-name',1100,'KRW',10,'READY','https://payapp.kr/?private=secret',UTC_TIMESTAMP()+INTERVAL 1 DAY,0,NOW(),NOW());""", "credit_test")
    tables = ("users", "payment_orders", "payment_transaction_bindings", "payment_notifications",
              "credit_accounts", "credit_grants", "credit_reservations", "credit_allocations", "credit_ledger", "credit_payment_holds")

    def snapshot():
        # Exact table checksums are fixture-only assertions, never exposed by the command.
        return sql("CHECKSUM TABLE " + ",".join(tables) + ";", "credit_test")

    def command(target=order, expected_exit=0, overrides=None, extra=()):
        before = snapshot()
        result = subprocess.run(["java", "-Xmx128m", "-jar", str(jar),
                                 "--payment-inspect=order", "--order-id=" + target, *extra],
                                cwd=repo, env={**env, **(overrides or {})},
                                capture_output=True, text=True, timeout=60)
        if result.returncode != expected_exit or result.stderr.strip() or snapshot() != before:
            raise RuntimeError("Packaged inspection failed or modified data; raw output suppressed.")
        report = json.loads(result.stdout)
        if len(result.stdout.splitlines()) != 1:
            raise RuntimeError("Inspection must emit one JSON line without Boot logs.")
        secrets = [env["DB_PASSWORD"], env["DB_TRUSTSTORE_PASSWORD"], "private-input-hash",
                   "private-notification-token", "private-product-name", "https://payapp.kr/", "private-provider-transaction"]
        if any(value in result.stdout for value in secrets):
            raise RuntimeError("Packaged inspection exposed private fixture values.")
        return report

    ready = command()
    if ready.get("status") != "UNCONFIRMED" or ready.get("purchase") is not None or ready["account"]["present"]:
        raise RuntimeError("Packaged READY inspection mismatch.")
    source = "payapp:" + order
    source_hash = hashlib.sha256(source.encode()).hexdigest()
    grant = sql(f"""INSERT INTO credit_grants(user_id,source_type,source_key,source_hash,amount,available,reserved,consumed,version,created_at)
        VALUES({owner},'PAYMENT','{source}','{source_hash}',10,10,0,0,0,NOW()); SELECT LAST_INSERT_ID();""", "credit_test").strip()
    sql(f"""INSERT INTO credit_accounts(user_id,available,reserved,version,created_at,updated_at) VALUES({owner},10,0,0,NOW(),NOW());
        INSERT INTO credit_ledger(user_id,entry_key,kind,source_type,grant_id,available_delta,reserved_delta,available_after,reserved_after,created_at)
        VALUES({owner},'grant:{grant}','GRANT','PAYMENT',{grant},10,0,10,0,NOW());
        UPDATE payment_orders SET state='PAID',credit_grant_id={grant} WHERE public_id='{order}';
        INSERT INTO payment_notifications(event_hash,order_id,provider_transaction,state_code,amount,responded_at,created_at)
        SELECT SHA2('{uuid.uuid4()}',256),id,'private-provider-transaction',4,1100,UTC_TIMESTAMP(),NOW() FROM payment_orders WHERE public_id='{order}';""", "credit_test")
    # Even without DML privileges, the exact production entry point must work.
    sql("REVOKE INSERT,UPDATE,DELETE ON credit_test.* FROM 'mongle_app'@'127.0.0.1';")
    try:
        paid = command()
        if paid.get("scope") != "INTERNAL_DATABASE_ONLY" or paid.get("status") != "CONSISTENT" or paid["purchase"]["amount"] != 10 or paid["findings"]:
            raise RuntimeError("Packaged paid inspection mismatch.")
        failures = (
            (dict(target=str(uuid.uuid4()), expected_exit=1), "ORDER_NOT_FOUND"),
            (dict(expected_exit=1, overrides={"MONGLE_PAYMENT_INSPECT_ENABLED": "false"}), "OPERATOR_COMMAND_DISABLED"),
            (dict(expected_exit=1, overrides={"DB_PASSWORD": "wrong-private-password"}), "DB_CONNECTION_FAILED"),
            (dict(target="not-an-order", expected_exit=1), "INVALID_ARGUMENTS"),
            (dict(expected_exit=1, extra=("--test-credit=grant",)), "INVALID_ARGUMENTS"),
            (dict(expected_exit=1, overrides={"MONGLE_PAYMENT_INSPECT_EXPECTED_DATABASE": "wrong_target"}), "INVALID_CONFIGURATION"),
        )
        for args, code in failures:
            if command(**args).get("code") != code:
                raise RuntimeError("Packaged inspection did not reject safely: " + code)
    finally:
        sql("GRANT SELECT,INSERT,UPDATE,DELETE ON credit_test.* TO 'mongle_app'@'127.0.0.1';")
    print("Packaged JAR: inspection READY/PAID/SELECT-only/absent/disabled/JDBC/malformed/mixed/target checks passed without Boot or data writes.", flush=True)
