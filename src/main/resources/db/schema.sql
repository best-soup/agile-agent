-- Manual bootstrap/reference schema. Normal application startup uses Flyway.
-- Safe to re-run: every CREATE is guarded by IF NOT EXISTS.

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
    INDEX idx_project_owner (owner_id),
    CONSTRAINT fk_project_owner_user FOREIGN KEY (owner_id) REFERENCES app_user (id)
);

CREATE TABLE IF NOT EXISTS project_member (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role VARCHAR(20) NOT NULL,
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_project_member (project_id, user_id),
    INDEX idx_member_user (user_id),
    INDEX idx_member_project (project_id),
    CONSTRAINT fk_member_project FOREIGN KEY (project_id) REFERENCES agile_project (id) ON DELETE CASCADE,
    CONSTRAINT fk_member_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS task_issue (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    task_log_id BIGINT DEFAULT NULL,
    title VARCHAR(255) NOT NULL,
    assignee_name VARCHAR(50) DEFAULT '待定',
    description TEXT,
    priority VARCHAR(20) DEFAULT 'MEDIUM',
    deadline DATE DEFAULT NULL,
    is_completed TINYINT(1) DEFAULT 0,
    INDEX idx_task_issue_project (project_id),
    INDEX idx_task_issue_project_completed (project_id, is_completed),
    CONSTRAINT fk_task_issue_project FOREIGN KEY (project_id) REFERENCES agile_project (id)
);
