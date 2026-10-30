"""Disposable TLS fixture only: verify the shipped CLI in independent JVMs."""
import hashlib
import json
import subprocess
import uuid
from concurrent.futures import ThreadPoolExecutor


def verify(repo, jar, credit_env, sql):
    if not credit_env["DB_URL"].startswith("jdbc:mysql://127.0.0.1:") or not credit_env["DB_URL"].endswith("/credit_test"):
        raise RuntimeError("Disposable loopback fixture required.")
    env = {**credit_env, "MONGLE_PAYMENT_RECLAIM_ENABLED": "true",
           "MONGLE_PAYMENT_RECLAIM_EXPECTED_HOST": "127.0.0.1",
           "MONGLE_PAYMENT_RECLAIM_EXPECTED_DATABASE": "credit_test",
           "MONGLE_PAYMENT_ENABLED": "false", "MONGLE_PAYAPP_ENABLED": "false", "MONGLE_CREDIT_ENABLED": "false"}
    owner = sql("INSERT INTO users(email,nickname,created_at,updated_at) VALUES('reclamation-cli@test.invalid','Reclaim',NOW(),NOW()); SELECT LAST_INSERT_ID();", "credit_test").strip()
    sql(f"INSERT INTO credit_accounts(user_id,available,reserved,version,created_at,updated_at) VALUES({owner},0,0,0,NOW(),NOW());", "credit_test")

    def cancelled():
        order = str(uuid.uuid4())
        tx = str(uuid.uuid4().int % (10**30) + 1)
        key = "payapp:" + order
        digest = hashlib.sha256(key.encode()).hexdigest()
        grant = sql(f"""INSERT INTO credit_grants(user_id,source_type,source_key,source_hash,amount,available,reserved,consumed,version,created_at)
            VALUES({owner},'PAYMENT','{key}','{digest}',10,10,0,0,0,NOW()); SELECT LAST_INSERT_ID();""", "credit_test").strip()
        sql(f"""UPDATE credit_accounts SET available=available+10,version=version+1 WHERE user_id={owner};
            INSERT INTO credit_ledger(user_id,entry_key,kind,source_type,grant_id,available_delta,reserved_delta,available_after,reserved_after,created_at)
            SELECT {owner},'GRANT:{grant}','GRANT','PAYMENT',{grant},10,0,available,reserved,NOW() FROM credit_accounts WHERE user_id={owner};
            INSERT INTO payment_orders(public_id,user_id,request_id,input_hash,notification_token,product_id,product_name,price,currency,credits,state,provider_transaction,credit_grant_id,request_deadline,version,created_at,updated_at)
            VALUES('{order}',{owner},'{uuid.uuid4()}','private-reclaim-input','private-reclaim-token','credit-pack','private-name',1100,'KRW',10,'PAYMENT_REVIEW','{tx}',{grant},UTC_TIMESTAMP()+INTERVAL 1 DAY,0,NOW(),NOW());
            INSERT INTO payment_transaction_bindings(transaction_id,order_id) SELECT '{tx}',id FROM payment_orders WHERE public_id='{order}';
            INSERT INTO payment_notifications(event_hash,order_id,provider_transaction,state_code,amount,responded_at,created_at)
            SELECT SHA2('{uuid.uuid4()}',256),id,'{tx}',4,1100,UTC_TIMESTAMP()-INTERVAL 30 SECOND,NOW() FROM payment_orders WHERE public_id='{order}';
            INSERT INTO payment_notifications(event_hash,order_id,provider_transaction,state_code,amount,responded_at,created_at)
            SELECT SHA2('{uuid.uuid4()}',256),id,'{tx}',9,1100,UTC_TIMESTAMP(),NOW() FROM payment_orders WHERE public_id='{order}';
            INSERT INTO credit_payment_holds(grant_id,user_id,created_at) VALUES({grant},{owner},NOW());
            INSERT INTO credit_ledger(user_id,entry_key,kind,source_type,grant_id,available_delta,reserved_delta,available_after,reserved_after,created_at)
            SELECT {owner},'PAYMENT_HOLD:{grant}','PAYMENT_HOLD','PAYMENT',{grant},0,0,available,reserved,NOW() FROM credit_accounts WHERE user_id={owner};""", "credit_test")
        return order, grant

    tables = "payment_orders,payment_transaction_bindings,payment_notifications,credit_accounts,credit_grants,credit_reservations,credit_allocations,credit_ledger,credit_payment_holds,payment_credit_reclamations"
    def snapshot():
        return sql("CHECKSUM TABLE " + tables + ";", "credit_test")

    def command(order, mode, key, token=None, expected_exit=0, overrides=None, no_changes=True, extra=()):
        args = ["java", "-Xmx128m", "-jar", str(jar), "--payment-reclaim=" + mode,
                "--order-id=" + order, "--request-key=" + key, "--operator-reference=case-packaged-119"]
        if token is not None:
            args.append("--expected-snapshot=" + token)
        before = snapshot() if no_changes else None
        result = subprocess.run([*args, *extra], cwd=repo, env={**env, **(overrides or {})}, capture_output=True, text=True, timeout=90)
        if result.returncode != expected_exit or result.stderr.strip() or len(result.stdout.splitlines()) != 1:
            raise RuntimeError("Packaged reclamation failed; raw output suppressed.")
        if no_changes and snapshot() != before:
            raise RuntimeError("Read-only, refused or replayed command modified business data.")
        if any(secret in result.stdout for secret in (env["DB_PASSWORD"], env["DB_TRUSTSTORE_PASSWORD"], "private-reclaim-input", "private-reclaim-token", "case-packaged-119")):
            raise RuntimeError("Reclamation output exposed private fixture values.")
        return json.loads(result.stdout)

    order, grant = cancelled()
    sql("REVOKE INSERT,UPDATE,DELETE ON credit_test.* FROM 'mongle_app'@'127.0.0.1';")
    try:
        plan = command(order, "preview", "reclaim-packaged")
        if plan.get("status") != "WOULD_RECLAIM" or plan["expectedAvailableAfter"] != 0:
            raise RuntimeError("Packaged reclamation preview mismatch.")
    finally:
        sql("GRANT SELECT,INSERT,UPDATE,DELETE ON credit_test.* TO 'mongle_app'@'127.0.0.1';")
    applied = command(order, "apply", "reclaim-packaged", plan["snapshot"], no_changes=False)
    if applied.get("status") != "RECLAIMED" or applied["inspection"]["status"] != "CONSISTENT" or applied["inspection"]["account"]["spendingBlocked"]:
        raise RuntimeError("Packaged reclamation apply mismatch.")
    if command(order, "apply", "reclaim-packaged", plan["snapshot"])["status"] != "ALREADY_RECLAIMED":
        raise RuntimeError("Packaged reclamation replay mismatch.")
    for args, code in (
        (dict(mode="apply", token=plan["snapshot"], key="different-key", expected_exit=1), "REQUEST_CONFLICT"),
        (dict(mode="preview", key="disabled", overrides={"MONGLE_PAYMENT_RECLAIM_ENABLED": "false"}, expected_exit=1), "OPERATOR_COMMAND_DISABLED"),
        (dict(mode="preview", key="bad-db", overrides={"DB_PASSWORD": "private-wrong-password"}, expected_exit=1), "DB_CONNECTION_FAILED"),
        (dict(mode="apply", key="no-token", expected_exit=1), "INVALID_ARGUMENTS"),
        (dict(mode="preview", key="mixed", extra=("--test-credit=grant",), expected_exit=1), "INVALID_ARGUMENTS"),
    ):
        if command(order, **args).get("code") != code:
            raise RuntimeError("Packaged reclamation rejection mismatch: " + code)
    parallel_order, parallel_grant = cancelled()
    token = command(parallel_order, "preview", "parallel-reclaim")["snapshot"]
    with ThreadPoolExecutor(max_workers=2) as pool:
        outcomes = list(pool.map(lambda _: command(parallel_order, "apply", "parallel-reclaim", token, no_changes=False), range(2)))
    if sorted(item["status"] for item in outcomes) != ["ALREADY_RECLAIMED", "RECLAIMED"]:
        raise RuntimeError("Independent reclamation JVMs did not serialize exactly once.")
    if sql(f"SELECT COUNT(*) FROM payment_credit_reclamations WHERE grant_id IN ({grant},{parallel_grant});", "credit_test").strip() != "2":
        raise RuntimeError("Packaged reclamation history mismatch.")
    if sql(f"SELECT available FROM credit_accounts WHERE user_id={owner};", "credit_test").strip() != "0":
        raise RuntimeError("Packaged reclamation final balance mismatch.")
    print("Packaged JAR: reclaim preview SELECT-only/apply/replay/safe rejection/concurrent JVMs passed without Boot or provider calls.", flush=True)
