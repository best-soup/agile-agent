CREATE TABLE IF NOT EXISTS rag_document (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    document_id VARCHAR(128) NOT NULL,
    project_id BIGINT NOT NULL,
    source_type VARCHAR(30) NOT NULL,
    source_id VARCHAR(500) NOT NULL,
    title VARCHAR(500),
    content_hash CHAR(64) NOT NULL,
    version INT NOT NULL DEFAULT 1,
    status VARCHAR(20) NOT NULL,
    failure_reason VARCHAR(1000),
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_rag_document_identity (project_id, source_type, source_id, content_hash),
    UNIQUE KEY uk_rag_document_version (project_id, document_id, version),
    INDEX idx_rag_document_project_source (project_id, source_type, source_id),
    INDEX idx_rag_document_project_status (project_id, status),
    CONSTRAINT fk_rag_document_project FOREIGN KEY (project_id) REFERENCES agile_project (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
