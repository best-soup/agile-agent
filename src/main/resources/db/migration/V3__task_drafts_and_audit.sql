CREATE TABLE task_draft (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    created_by BIGINT NOT NULL,
    source_type VARCHAR(30) NOT NULL,
    source_ref VARCHAR(500),
    source_content_hash CHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    idempotency_key VARCHAR(100),
    failure_reason VARCHAR(1000),
    submitted_task_ids TEXT,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    confirmed_at DATETIME,
    submitted_at DATETIME,
    UNIQUE KEY uk_task_draft_idempotency (project_id, idempotency_key),
    INDEX idx_task_draft_project_status (project_id, status, created_at),
    INDEX idx_task_draft_creator (created_by),
    CONSTRAINT fk_task_draft_project FOREIGN KEY (project_id) REFERENCES agile_project (id),
    CONSTRAINT fk_task_draft_creator FOREIGN KEY (created_by) REFERENCES app_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE task_draft_item (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    draft_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    assignee_name VARCHAR(50),
    description TEXT,
    priority VARCHAR(20) NOT NULL DEFAULT 'MEDIUM',
    deadline DATE,
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_task_draft_item_order (draft_id, sort_order, id),
    CONSTRAINT fk_task_draft_item_draft FOREIGN KEY (draft_id) REFERENCES task_draft (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE audit_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    action VARCHAR(50) NOT NULL,
    resource_type VARCHAR(50) NOT NULL,
    resource_id BIGINT NOT NULL,
    request_id VARCHAR(100),
    detail TEXT,
    created_at DATETIME NOT NULL,
    INDEX idx_audit_project_created (project_id, created_at),
    INDEX idx_audit_resource (resource_type, resource_id),
    INDEX idx_audit_user_created (user_id, created_at),
    CONSTRAINT fk_audit_project FOREIGN KEY (project_id) REFERENCES agile_project (id),
    CONSTRAINT fk_audit_user FOREIGN KEY (user_id) REFERENCES app_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
