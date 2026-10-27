-- Read-only metadata checks for Flyway adoption. No row data or credentials.
-- Run with the existing TLS connection to the mongle schema.
USE mongle;
SELECT DATABASE() AS target_database, VERSION() AS mysql_version, CURRENT_USER() AS connection_account;
SELECT table_name, engine, table_collation
FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE'
ORDER BY table_name;
SELECT COUNT(*) AS business_table_count
FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE'
AND table_name <> 'flyway_schema_history';
WITH expected AS (
SELECT 'ai_generation_logs' AS table_name
UNION ALL
SELECT 'credit_accounts' AS table_name
UNION ALL
SELECT 'credit_allocations' AS table_name
UNION ALL
SELECT 'credit_grants' AS table_name
UNION ALL
SELECT 'credit_ledger' AS table_name
UNION ALL
SELECT 'credit_payment_holds' AS table_name
UNION ALL
SELECT 'credit_reservations' AS table_name
UNION ALL
SELECT 'dream_analyses' AS table_name
UNION ALL
SELECT 'dream_analysis_display_keywords' AS table_name
UNION ALL
SELECT 'dream_emotions' AS table_name
UNION ALL
SELECT 'dream_entities' AS table_name
UNION ALL
SELECT 'dream_generation_jobs' AS table_name
UNION ALL
SELECT 'dream_images' AS table_name
UNION ALL
SELECT 'dream_scene_entities' AS table_name
UNION ALL
SELECT 'dream_scenes' AS table_name
UNION ALL
SELECT 'dream_stories' AS table_name
UNION ALL
SELECT 'dream_story_versions' AS table_name
UNION ALL
SELECT 'dreams' AS table_name
UNION ALL
SELECT 'image_cleanup_requests' AS table_name
UNION ALL
SELECT 'payment_notifications' AS table_name
UNION ALL
SELECT 'payment_orders' AS table_name
UNION ALL
SELECT 'payment_transaction_bindings' AS table_name
UNION ALL
SELECT 'refresh_session_tokens' AS table_name
UNION ALL
SELECT 'refresh_sessions' AS table_name
UNION ALL
SELECT 'social_accounts' AS table_name
UNION ALL
SELECT 'users' AS table_name
UNION ALL
SELECT 'world_bridge_results' AS table_name
UNION ALL
SELECT 'world_composition_entries' AS table_name
UNION ALL
SELECT 'world_compositions' AS table_name
UNION ALL
SELECT 'world_folders' AS table_name
UNION ALL
SELECT 'world_image_sources' AS table_name
UNION ALL
SELECT 'world_images' AS table_name
UNION ALL
SELECT 'world_selections' AS table_name
), actual AS (
 SELECT table_name FROM information_schema.tables
 WHERE table_schema=DATABASE() AND table_type='BASE TABLE' AND table_name <> 'flyway_schema_history'
)
SELECT 'missing' AS difference, e.table_name
FROM expected e LEFT JOIN actual a USING(table_name) WHERE a.table_name IS NULL
UNION ALL
SELECT 'unexpected' AS difference, a.table_name
FROM actual a LEFT JOIN expected e USING(table_name) WHERE e.table_name IS NULL
ORDER BY difference, table_name;
SELECT table_name, ordinal_position, column_name, column_type, is_nullable, column_default,
       character_set_name, collation_name, extra, generation_expression
FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name <> 'flyway_schema_history'
ORDER BY table_name, ordinal_position;
SELECT table_name, index_name, non_unique, seq_in_index, column_name, expression,
       sub_part, collation, index_type, is_visible
FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name <> 'flyway_schema_history'
ORDER BY table_name, index_name, seq_in_index;
SELECT k.table_name, k.constraint_name, k.column_name, k.ordinal_position,
       k.referenced_table_name, k.referenced_column_name, r.update_rule, r.delete_rule
FROM information_schema.key_column_usage k
JOIN information_schema.referential_constraints r
  ON r.constraint_schema=k.constraint_schema AND r.table_name=k.table_name AND r.constraint_name=k.constraint_name
WHERE k.table_schema=DATABASE()
ORDER BY k.table_name, k.constraint_name, k.ordinal_position;
SELECT t.table_name, t.constraint_name, t.enforced, c.check_clause
FROM information_schema.table_constraints t
JOIN information_schema.check_constraints c
  ON c.constraint_schema=t.constraint_schema AND c.constraint_name=t.constraint_name
WHERE t.table_schema=DATABASE() AND t.table_name <> 'flyway_schema_history' AND t.constraint_type='CHECK'
ORDER BY t.table_name, t.constraint_name;
SELECT table_name AS existing_flyway_history
FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='flyway_schema_history';
SHOW GRANTS FOR CURRENT_USER;
