CREATE TABLE IF NOT EXISTS app_user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(100) NOT NULL UNIQUE,
    display_name VARCHAR(100),
    api_key_hash CHAR(64) NOT NULL UNIQUE,
    created_at DATETIME NOT NULL
);

CREATE TABLE IF NOT EXISTS agile_project (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    owner_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    INDEX idx_project_owner (owner_id)
);

CREATE TABLE IF NOT EXISTS project_member (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role VARCHAR(20) NOT NULL,
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_project_member (project_id, user_id),
    INDEX idx_member_user (user_id),
    INDEX idx_member_project (project_id)
);
