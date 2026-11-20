package db.migration;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Adds non-destructive indexes and foreign keys after the baseline schema. */
public class V2__Harden_schema extends BaseJavaMigration {

    private static final Set<String> TASK_ISSUE_COLUMNS = Set.of(
            "id", "project_id", "task_log_id", "title", "assignee_name",
            "description", "priority", "deadline", "is_completed");

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        requireTables(connection, "app_user", "agile_project", "project_member", "task_issue");
        requireColumns(connection, "task_issue", TASK_ISSUE_COLUMNS);

        ensureIndex(connection, "task_issue", "idx_task_issue_project",
                "CREATE INDEX idx_task_issue_project ON task_issue (project_id)");
        ensureIndex(connection, "task_issue", "idx_task_issue_project_completed",
                "CREATE INDEX idx_task_issue_project_completed ON task_issue (project_id, is_completed)");

        ensureForeignKey(connection, "agile_project", "owner_id", "app_user", "id",
                "ALTER TABLE agile_project ADD CONSTRAINT fk_project_owner_user "
                        + "FOREIGN KEY (owner_id) REFERENCES app_user (id)");
        ensureForeignKey(connection, "project_member", "project_id", "agile_project", "id",
                "ALTER TABLE project_member ADD CONSTRAINT fk_member_project "
                        + "FOREIGN KEY (project_id) REFERENCES agile_project (id) ON DELETE CASCADE");
        ensureForeignKey(connection, "project_member", "user_id", "app_user", "id",
                "ALTER TABLE project_member ADD CONSTRAINT fk_member_user "
                        + "FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE");
        ensureForeignKey(connection, "task_issue", "project_id", "agile_project", "id",
                "ALTER TABLE task_issue ADD CONSTRAINT fk_task_issue_project "
                        + "FOREIGN KEY (project_id) REFERENCES agile_project (id)");
    }

    private void requireTables(Connection connection, String... requiredTables) throws Exception {
        Set<String> tables = new HashSet<>();
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet result = metadata.getTables(connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
            while (result.next()) {
                tables.add(result.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
            }
        }
        for (String table : requiredTables) {
            if (!tables.contains(table.toLowerCase(Locale.ROOT))) {
                throw new FlywayException("Required table is missing: " + table
                        + ". Inspect the existing database before migrating.");
            }
        }
    }

    private void requireColumns(Connection connection, String table, Set<String> requiredColumns)
            throws Exception {
        Set<String> columns = new HashSet<>();
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet result = metadata.getColumns(connection.getCatalog(), null, table, "%")) {
            while (result.next()) {
                columns.add(result.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
            }
        }
        for (String column : requiredColumns) {
            if (!columns.contains(column.toLowerCase(Locale.ROOT))) {
                throw new FlywayException("Existing table task_issue is missing required column: " + column
                        + ". No task_issue column is invented automatically.");
            }
        }
    }

    private void ensureIndex(Connection connection, String table, String indexName, String ddl)
            throws Exception {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet result = metadata.getIndexInfo(connection.getCatalog(), null, table, false, false)) {
            while (result.next()) {
                String name = result.getString("INDEX_NAME");
                if (name != null && indexName.equalsIgnoreCase(name)) {
                    return;
                }
            }
        }
        execute(connection, ddl);
    }

    private void ensureForeignKey(Connection connection, String table, String fkColumn,
                                  String pkTable, String pkColumn, String ddl) throws Exception {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet result = metadata.getImportedKeys(connection.getCatalog(), null, table)) {
            while (result.next()) {
                String existingFkColumn = result.getString("FKCOLUMN_NAME");
                String existingPkTable = result.getString("PKTABLE_NAME");
                String existingPkColumn = result.getString("PKCOLUMN_NAME");
                if (fkColumn.equalsIgnoreCase(existingFkColumn)
                        && pkTable.equalsIgnoreCase(existingPkTable)
                        && pkColumn.equalsIgnoreCase(existingPkColumn)) {
                    return;
                }
            }
        }
        execute(connection, ddl);
    }

    private void execute(Connection connection, String ddl) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute(ddl);
        }
    }
}
