-- READ ONLY. Run on the intended database with the existing TLS CLI options.
-- Does not read passwords, user content or payment identifiers.
SELECT DATABASE() AS target_database, VERSION() AS mysql_version, CURRENT_USER() AS connected_account;
SHOW SESSION STATUS LIKE 'Ssl_cipher';
SHOW GRANTS;

SELECT TABLE_NAME
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE'
ORDER BY TABLE_NAME;

SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND ((TABLE_NAME IN ('credit_reservations','credit_ledger') AND COLUMN_NAME IN ('usage','credit_usage'))
       OR (TABLE_NAME='dream_images' AND COLUMN_NAME='credit_reservation_id'))
ORDER BY TABLE_NAME, ORDINAL_POSITION;

SELECT TABLE_NAME, INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE()
  AND ((TABLE_NAME='credit_reservations' AND INDEX_NAME='uk_credit_reservation_target')
       OR (TABLE_NAME='dream_images' AND INDEX_NAME='idx_image_attempt_expiry'))
ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX;

SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA=DATABASE()
  AND TABLE_NAME IN ('world_compositions','world_composition_entries')
ORDER BY TABLE_NAME, ORDINAL_POSITION;

-- Exact counts for credit/payment data. Do not infer all data is empty from TABLE_ROWS estimates.
SELECT 'credit_accounts' AS table_name,COUNT(*) AS exact_rows FROM credit_accounts
UNION ALL SELECT 'credit_grants',COUNT(*) FROM credit_grants
UNION ALL SELECT 'credit_reservations',COUNT(*) FROM credit_reservations
UNION ALL SELECT 'credit_ledger',COUNT(*) FROM credit_ledger
UNION ALL SELECT 'payment_orders',COUNT(*) FROM payment_orders;
