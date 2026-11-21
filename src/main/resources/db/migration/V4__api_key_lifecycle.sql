CREATE TABLE IF NOT EXISTS api_key (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    key_prefix VARCHAR(20) NOT NULL,
    key_hash CHAR(64) NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL,
    expires_at DATETIME,
    revoked_at DATETIME,
    last_used_at DATETIME,
    created_at DATETIME NOT NULL,
    INDEX idx_api_key_user_status (user_id, status),
    INDEX idx_api_key_prefix (key_prefix),
    CONSTRAINT fk_api_key_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO api_key (user_id, name, key_prefix, key_hash, status, created_at)
SELECT u.id, 'legacy', LEFT(u.api_key_hash, 12), u.api_key_hash, 'ACTIVE', u.created_at
FROM app_user u
WHERE u.api_key_hash IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM api_key k WHERE k.key_hash = u.api_key_hash);

ALTER TABLE app_user MODIFY api_key_hash CHAR(64) NULL;
