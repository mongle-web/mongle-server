-- MySQL 8.4 read-only preflight for #79. Does not change schema or accounts.
-- Run against the intended DB using the existing TLS connection.
SELECT DATABASE() AS target_database;

SELECT provider, COUNT(*) AS account_count
FROM social_accounts
GROUP BY provider
ORDER BY provider;

-- Must be zero before merging/deploying the Kakao-only SocialProvider enum.
-- Binary comparison also detects malformed/case-variant provider values.
SELECT COUNT(*) AS unsupported_provider_count
FROM social_accounts
WHERE CAST(provider AS BINARY) <> CAST('KAKAO' AS BINARY);
