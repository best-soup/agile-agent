package com.example.agileagent.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.sql.DriverManager;
import java.sql.SQLException;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class MySqlMigrationIT {
    @Container static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @Test void migratesAndEnforcesRelationships() throws Exception {
        Flyway flyway = Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .locations("classpath:db/migration").load();
        flyway.migrate();
        assertEquals("6", flyway.info().current().getVersion().getVersion());
        try (var c = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
             var s = c.createStatement()) {
            for (String table : new String[]{"app_user", "agile_project", "project_member", "task_issue", "task_draft", "meeting_record", "rag_document", "api_key"}) {
                try (var rs = c.getMetaData().getTables(c.getCatalog(), null, table, new String[]{"TABLE"})) { assertTrue(rs.next(), table); }
            }
            s.executeUpdate("INSERT INTO app_user(id,username,created_at) VALUES (1,'integration-user',NOW())");
            s.executeUpdate("INSERT INTO agile_project(id,name,owner_id,created_at) VALUES (1,'test',1,NOW())");
            s.executeUpdate("INSERT INTO task_issue(project_id,title) VALUES (1,'test')");
            s.executeUpdate("INSERT INTO task_draft(project_id,created_by,source_type,source_content_hash,status,created_at,updated_at) VALUES (1,1,'MEETING',REPEAT('a',64),'DRAFT',NOW(),NOW())");
            s.executeUpdate("INSERT INTO meeting_record(project_id,source_type,source_id,identity_hash,content,content_hash,imported_by,index_status,created_at,updated_at) VALUES (1,'MANUAL_TEXT','test',REPEAT('b',64),'synthetic',REPEAT('c',64),1,'PENDING',NOW(),NOW())");
            assertThrows(SQLException.class, () -> s.executeUpdate("INSERT INTO app_user(username,created_at) VALUES ('integration-user',NOW())"));
            assertThrows(SQLException.class, () -> s.executeUpdate("INSERT INTO task_issue(project_id,title) VALUES (999999,'test')"));
        }
    }
}
