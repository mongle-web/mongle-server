"""Run the packaged recovery command against the disposable TLS fixture."""
import hashlib
import json
import subprocess
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from urllib.parse import urlencode


def verify(repo, jar, env, sql):
    if not env["DB_URL"].startswith("jdbc:mysql://127.0.0.1:") or not env["DB_URL"].endswith("/credit_test"):
        raise RuntimeError("Disposable loopback fixture required.")
    database = "credit_test"
    seller, key, value, secret = ("fixture-seller", "fixture-link-key", "fixture-link-value", "fixture-request-key")
    config = {
        **env, "MONGLE_PAYMENT_INSPECT_ENABLED": "true", "MONGLE_PAYMENT_INSPECT_EXPECTED_HOST": "127.0.0.1",
        "MONGLE_PAYMENT_INSPECT_EXPECTED_DATABASE": database, "MONGLE_PAYMENT_RECOVERY_ENABLED": "true",
        "MONGLE_PAYMENT_RECOVERY_EXPECTED_HOST": "127.0.0.1", "MONGLE_PAYMENT_RECOVERY_EXPECTED_DATABASE": database,
        "MONGLE_PAYMENT_ENABLED": "false", "MONGLE_PAYAPP_ENABLED": "true", "MONGLE_CREDIT_ENABLED": "true",
        "PAYAPP_SELLER_ID": seller, "PAYAPP_LINK_KEY": key, "PAYAPP_LINK_VALUE": value, "PAYAPP_REQUEST_KEY": secret,
        "PAYAPP_FEEDBACK_URL": "https://api.example.invalid/api/v1/payments/payapp/feedback",
        "PAYAPP_RETURN_URL": "https://example.invalid/payment",
    }
    owner = sql("""INSERT INTO users(email,nickname,created_at,updated_at)
        VALUES('recovery-cli@test.invalid','Recovery',UTC_TIMESTAMP(),UTC_TIMESTAMP()); SELECT LAST_INSERT_ID();""", database).strip()

    def fixture(state, deadline):
        public_id, token = str(uuid.uuid4()), str(uuid.uuid4())
        sql(f"""INSERT INTO payment_orders(public_id,user_id,request_id,input_hash,notification_token,product_id,
            product_name,price,currency,credits,state,request_deadline,version,created_at,updated_at)
            VALUES('{public_id}',{owner},'{uuid.uuid4()}',REPEAT('a',64),'{token}','credit-pack','Pack',
            1100,'KRW',10,'{state}',UTC_TIMESTAMP()+INTERVAL {deadline} MINUTE,0,
            UTC_TIMESTAMP()-INTERVAL 2 HOUR,UTC_TIMESTAMP());""", database)
        return public_id, token

    order, token = fixture("UNKNOWN", -60)
    expired, _ = fixture("REQUESTING", -60)
    pending, _ = fixture("PENDING", -60)
    fresh, _ = fixture("REQUESTING", 10)
    ready, _ = fixture("READY", -60)
    stamp = (datetime.now(timezone(timedelta(hours=9))) - timedelta(hours=1)).strftime("%Y%m%d%H%M%S")
    transaction = str(uuid.uuid4().int % 10**25 + 1)
    form = urlencode({
        "userid": seller, "linkkey": key, "linkval": value, "hash": hashlib.sha256((seller+stamp+secret).encode()).hexdigest(),
        "responsedate": stamp, "pay_date": stamp, "var1": order, "var2": token, "mul_no": transaction,
        "pay_state": "4", "price": "1100",
    })
    evidence = json.dumps({"originalForm": form, "confirmedTransaction": transaction,
                           "confirmedState": 4, "confirmedAmount": 1100})

    def command(args, data="", expected=0, overrides=None):
        result = subprocess.run(
            ["java", "-Xmx192m", "-jar", str(jar), *args], cwd=repo, env={**config, **(overrides or {})},
            input=data, capture_output=True, text=True, timeout=90,
        )
        sensitive = (token, key, value, secret, transaction, config["DB_PASSWORD"], config["DB_TRUSTSTORE_PASSWORD"])
        if (result.returncode != expected or result.stderr.strip() or len(result.stdout.splitlines()) != 1
                or any(item in result.stdout for item in sensitive)):
            # Print only a whitelisted diagnostic code from a failed fixture.
            try:
                code = json.loads(result.stdout).get("code", "")
            except (ValueError, AttributeError):
                code = ""
            allowed = {"OPERATOR_COMMAND_DISABLED", "PAYMENT_RECOVERY_FAILED", "INVALID_CONFIGURATION",
                       "INVALID_PAYAPP_CONFIGURATION", "INVALID_RECOVERY_EVIDENCE", "INVALID_ORIGINAL_NOTIFICATION",
                       "ORDER_NOT_UNCONFIRMED", "NOTIFICATION_TIMELINE_CONFLICT", "SNAPSHOT_CHANGED"}
            raise RuntimeError("Packaged payment recovery failed. " + (code if code in allowed else ""))
        return json.loads(result.stdout)

    preview_args = ["--payment-recovery=preview", "--order-id=" + order, "--operator-reference=fixture-141"]
    if command(preview_args, evidence, expected=1, overrides={"MONGLE_PAYMENT_RECOVERY_ENABLED": "false"}).get("code") != "OPERATOR_COMMAND_DISABLED":
        raise RuntimeError("Recovery flag was bypassed.")
    if command(preview_args, evidence + " {}", expected=1).get("code") != "INVALID_RECOVERY_EVIDENCE":
        raise RuntimeError("Recovery accepted trailing evidence.")

    sql("REVOKE INSERT,UPDATE,DELETE ON credit_test.* FROM 'mongle_app'@'127.0.0.1';")
    try:
        items = command(["--payment-inspect=unconfirmed"])["items"]
        found = {item["orderId"]: item for item in items}
        if (set(found) != {order, expired, pending} or fresh in found or ready in found
                or found[expired]["effectiveState"] != "UNKNOWN" or found[pending]["notificationCount"] != 0):
            raise RuntimeError("Unconfirmed discovery mismatch.")
        later = command(["--payment-inspect=unconfirmed", "--after-order-id=" + order])["items"]
        if any(item["orderId"] <= order for item in later):
            raise RuntimeError("Unconfirmed cursor repeated a previous row.")
        preview = command(preview_args, evidence)
        if preview["status"] != "READY" or preview["creditGranted"]:
            raise RuntimeError("Recovery preview mismatch.")
    finally:
        sql("GRANT SELECT,INSERT,UPDATE,DELETE ON credit_test.* TO 'mongle_app'@'127.0.0.1';")

    apply_args = ["--payment-recovery=apply", *preview_args[1:], "--expected-snapshot=" + preview["snapshot"]]
    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(lambda _: command(apply_args, evidence), range(2)))
    if sorted(item["status"] for item in results) != ["ALREADY_RECOVERED", "RECOVERED"]:
        raise RuntimeError("Recovery JVMs did not serialize exactly once.")
    if sql(f"SELECT count(*) FROM credit_ledger WHERE user_id={owner} AND kind='GRANT';", database).strip() != "1":
        raise RuntimeError("Recovery duplicated or skipped the credit ledger.")
    print("Packaged JAR: unconfirmed discovery and preview SELECT-only; concurrent recovery applied once.", flush=True)
