package com.meet2action.backend.config;

import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

/**
 * Local-development schema strategy: applies the idempotent db/schema-mysql.sql, then additive migrations
 * for databases created by an earlier release. Unlike Hibernate ddl-auto, a missing or unreachable database
 * does not stop the application from booting, so /api/health keeps working. Restart after fixing the database.
 * Nothing here drops tables or columns or touches existing rows.
 */
@Component
@ConditionalOnProperty(name = "app.schema.init", havingValue = "true", matchIfMissing = true)
public class SchemaInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaInitializer.class);

    private static final String SYNC_ENUM = "enum('NOT_SYNCED','SYNCING','SYNCED','FAILED','UNCERTAIN') NOT NULL";

    /** Columns added to task_proposals after the first release (Trello sync). */
    private static final Map<String, String> TASK_COLUMNS = Map.of(
            "trello_card_id", "varchar(64) DEFAULT NULL",
            "trello_card_url", "varchar(512) DEFAULT NULL",
            "trello_list_id", "varchar(64) DEFAULT NULL",
            "synced_at", "datetime(6) DEFAULT NULL",
            "sync_attempted_at", "datetime(6) DEFAULT NULL",
            "sync_error_code", "varchar(64) DEFAULT NULL",
            "sync_error_message", "varchar(500) DEFAULT NULL",
            "synced_version", "bigint DEFAULT NULL");

    private final DataSource dataSource;

    public SchemaInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            new ResourceDatabasePopulator(new ClassPathResource("db/schema-mysql.sql")).execute(dataSource);
            migrate(new JdbcTemplate(dataSource));
            log.info("Database schema is up to date");
        } catch (Exception e) {
            log.warn("Schema initialization skipped; database unavailable or script failed ({}). "
                    + "Fix the database settings and restart the backend.", e.getClass().getSimpleName());
        }
    }

    private void migrate(JdbcTemplate jdbc) {
        TASK_COLUMNS.forEach((column, definition) -> {
            Integer present = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() "
                            + "AND table_name = 'task_proposals' AND column_name = ?", Integer.class, column);
            if (present != null && present == 0) {
                jdbc.execute("ALTER TABLE task_proposals ADD COLUMN " + column + " " + definition);
                log.info("Added column task_proposals.{}", column);
            }
        });
        // Widen the sync_status enum only when the new values are missing (additive; no data is rewritten).
        String type = jdbc.queryForObject(
                "SELECT column_type FROM information_schema.columns WHERE table_schema = DATABASE() "
                        + "AND table_name = 'task_proposals' AND column_name = 'sync_status'", String.class);
        if (type != null && !type.contains("UNCERTAIN")) {
            jdbc.execute("ALTER TABLE task_proposals MODIFY sync_status " + SYNC_ENUM);
            log.info("Extended task_proposals.sync_status values");
        }
    }
}
