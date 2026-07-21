package com.hajacheck.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

class ApiSystemLogSchemaMigrationTest {

    private static final String MIGRATION_ROOT = "db/migrations/";
    private static final String CONTAINER_ROOT = "/tmp/";
    private static final String BASELINE = "HajaCheck_script_v0.3.sql";
    private static final String MIGRATION = "20260720_01_create_api_system_logs.sql";

    @Test
    void v03기준선에_API시스템로그를_재실행가능하게_적용하고_의미드리프트를_거부한다() {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17")
                .withDatabaseName("hajacheck")
                .withUsername("postgres")
                .withStartupTimeout(Duration.ofMinutes(2))
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource(MIGRATION_ROOT + BASELINE),
                        CONTAINER_ROOT + BASELINE)
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource(MIGRATION_ROOT + MIGRATION),
                        CONTAINER_ROOT + MIGRATION)) {
            postgres.start();

            runPsql(postgres, BASELINE);
            runPsql(postgres, MIGRATION);
            runPsql(postgres, MIGRATION);

            requireQuery(postgres, "column contract", """
                    select count(*)
                    from information_schema.columns
                    where table_schema = 'public'
                      and table_name = 'api_system_logs'
                    """, "13");
            requireQuery(postgres, "identity and default contract", """
                    select is_identity || ':' || identity_generation || ':'
                           || (select column_default = 'now()'
                               from information_schema.columns
                               where table_schema = 'public'
                                 and table_name = 'api_system_logs'
                                 and column_name = 'created_at')::text
                    from information_schema.columns
                    where table_schema = 'public'
                      and table_name = 'api_system_logs'
                      and column_name = 'id'
                    """, "YES:ALWAYS:true");
            requireQuery(postgres, "no user foreign key", """
                    select count(*)
                    from pg_constraint
                    where conrelid = 'api_system_logs'::regclass
                      and contype = 'f'
                    """, "0");
            requireQuery(postgres, "three secondary indexes", """
                    select count(*)
                    from pg_index index_meta
                    join pg_class index_class on index_class.oid = index_meta.indexrelid
                    where index_meta.indrelid = 'api_system_logs'::regclass
                      and not index_meta.indisprimary
                      and index_class.relname in (
                          'idx_api_system_logs_created_at',
                          'idx_api_system_logs_level_created_at',
                          'idx_api_system_logs_request_id'
                      )
                    """, "3");

            runSql(postgres, "valid WARN and ERROR rows", """
                    insert into api_system_logs (
                        level, request_id, http_method, endpoint, http_status, duration_ms)
                    values ('WARN', 'request-1', 'GET', '/api/facilities/{id}', 404, 3),
                           ('ERROR', 'request-2', 'POST', '/api/inspections', 500, 7),
                           ('WARN', 'request-1', 'GET', '/api/facilities/{id}', 409, 4);
                    """);
            requireQuery(postgres, "request id is intentionally not unique", """
                    select count(*) from api_system_logs where request_id = 'request-1'
                    """, "2");
            runSqlExpectFailure(postgres, "unknown level", """
                    insert into api_system_logs (
                        level, request_id, http_method, endpoint, http_status, duration_ms)
                    values ('INFO', 'invalid-1', 'GET', '/api/test', 400, 1)
                    """, "ck_api_system_logs_level");
            runSqlExpectFailure(postgres, "WARN with 5xx", """
                    insert into api_system_logs (
                        level, request_id, http_method, endpoint, http_status, duration_ms)
                    values ('WARN', 'invalid-2', 'GET', '/api/test', 500, 1)
                    """, "ck_api_system_logs_level_http_status");
            runSqlExpectFailure(postgres, "ERROR with 4xx", """
                    insert into api_system_logs (
                        level, request_id, http_method, endpoint, http_status, duration_ms)
                    values ('ERROR', 'invalid-3', 'GET', '/api/test', 499, 1)
                    """, "ck_api_system_logs_level_http_status");
            runSqlExpectFailure(postgres, "negative duration", """
                    insert into api_system_logs (
                        level, request_id, http_method, endpoint, http_status, duration_ms)
                    values ('WARN', 'invalid-4', 'GET', '/api/test', 400, -1)
                    """, "ck_api_system_logs_duration");

            runSql(postgres, "tamper level-status check", """
                    alter table api_system_logs
                        drop constraint ck_api_system_logs_level_http_status;
                    alter table api_system_logs
                        add constraint ck_api_system_logs_level_http_status
                        check (http_status between 400 and 599);
                    """);
            runPsqlExpectFailure(
                    postgres, MIGRATION, "api_system_logs check constraints differ from canonical DDL");
            runSql(postgres, "restore level-status check", """
                    alter table api_system_logs
                        drop constraint ck_api_system_logs_level_http_status;
                    alter table api_system_logs
                        add constraint ck_api_system_logs_level_http_status
                        check (
                            (level = 'WARN' and http_status between 400 and 499)
                            or (level = 'ERROR' and http_status between 500 and 599)
                        );
                    """);
            runPsql(postgres, MIGRATION);

            runSql(postgres, "tamper request-id index", """
                    drop index idx_api_system_logs_request_id;
                    create index idx_api_system_logs_request_id on api_system_logs (http_method);
                    """);
            runPsqlExpectFailure(
                    postgres, MIGRATION, "api_system_logs indexes differ from canonical DDL");
        }
    }

    private static void runPsql(PostgreSQLContainer<?> postgres, String fileName) {
        ExecResult result = exec(postgres, "psql", "-X", "--set", "ON_ERROR_STOP=1",
                "--username", "postgres", "--dbname", "hajacheck",
                "--file", CONTAINER_ROOT + fileName);
        assertThat(result.getExitCode())
                .withFailMessage("%s failed:%nstdout:%n%s%nstderr:%n%s",
                        fileName, result.getStdout(), result.getStderr())
                .isZero();
    }

    private static void runPsqlExpectFailure(
            PostgreSQLContainer<?> postgres, String fileName, String expectedMessage) {
        ExecResult result = exec(postgres, "psql", "-X", "--set", "ON_ERROR_STOP=1",
                "--username", "postgres", "--dbname", "hajacheck",
                "--file", CONTAINER_ROOT + fileName);
        assertThat(result.getExitCode()).isNotZero();
        assertThat(result.getStdout() + result.getStderr()).contains(expectedMessage);
    }

    private static void runSql(PostgreSQLContainer<?> postgres, String label, String sql) {
        ExecResult result = exec(postgres, "psql", "-X", "--set", "ON_ERROR_STOP=1",
                "--username", "postgres", "--dbname", "hajacheck", "--command", sql);
        assertThat(result.getExitCode())
                .withFailMessage("%s failed:%nstdout:%n%s%nstderr:%n%s",
                        label, result.getStdout(), result.getStderr())
                .isZero();
    }

    private static void runSqlExpectFailure(
            PostgreSQLContainer<?> postgres, String label, String sql, String expectedMessage) {
        ExecResult result = exec(postgres, "psql", "-X", "--set", "ON_ERROR_STOP=1",
                "--username", "postgres", "--dbname", "hajacheck", "--command", sql);
        assertThat(result.getExitCode()).isNotZero();
        assertThat(result.getStdout() + result.getStderr()).as(label).contains(expectedMessage);
    }

    private static void requireQuery(
            PostgreSQLContainer<?> postgres, String label, String sql, String expectedOutput) {
        ExecResult result = exec(postgres, "psql", "-X", "--tuples-only", "--no-align",
                "--set", "ON_ERROR_STOP=1", "--username", "postgres", "--dbname", "hajacheck",
                "--command", sql);
        assertThat(result.getExitCode())
                .withFailMessage("%s failed:%nstdout:%n%s%nstderr:%n%s",
                        label, result.getStdout(), result.getStderr())
                .isZero();
        assertThat(result.getStdout().trim()).as(label).isEqualTo(expectedOutput);
    }

    private static ExecResult exec(PostgreSQLContainer<?> postgres, String... command) {
        try {
            return postgres.execInContainer(command);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("failed to execute PostgreSQL migration test command", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to execute PostgreSQL migration test command", exception);
        }
    }
}
