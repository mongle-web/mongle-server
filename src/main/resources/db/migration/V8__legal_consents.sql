ALTER TABLE users ADD COLUMN terms_version VARCHAR(40) NULL;
ALTER TABLE users ADD COLUMN terms_digest VARCHAR(64) NULL;
ALTER TABLE users ADD COLUMN privacy_version VARCHAR(40) NULL;
ALTER TABLE users ADD COLUMN privacy_digest VARCHAR(64) NULL;
-- The initial digest is bound atomically on the first accepted consent.
-- Once bound, changing content requires a new version rather than replacing the digest.
CREATE TABLE legal_document_versions (
 id VARCHAR(64) NOT NULL PRIMARY KEY,
 document_type VARCHAR(20) NOT NULL,
 document_version VARCHAR(40) NOT NULL,
 document_digest VARCHAR(64) NULL,
 CONSTRAINT uk_legal_document_version UNIQUE(document_type,document_version)
);
INSERT INTO legal_document_versions(id,document_type,document_version)
 VALUES ('TERMS:2026-11-07-v1','TERMS','2026-11-07-v1'),
        ('PRIVACY:2026-11-07-v1','PRIVACY','2026-11-07-v1');
CREATE TABLE legal_consents (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 document_type VARCHAR(20) NOT NULL,
 document_version VARCHAR(40) NOT NULL,
 document_digest VARCHAR(64) NOT NULL,
 accepted_at DATETIME(6) NOT NULL,
 age14_confirmed BOOLEAN NOT NULL,
 CONSTRAINT uk_legal_consent_version UNIQUE(user_id,document_type,document_version),
 CONSTRAINT fk_legal_consent_user FOREIGN KEY(user_id) REFERENCES users(id),
 CONSTRAINT fk_legal_consent_document FOREIGN KEY(document_type,document_version)
     REFERENCES legal_document_versions(document_type,document_version)
);
