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
    private static final String MIGRATION = "20260720_01_create_api_system_logs.sql";

    @Test
    void postgres역할없는_DB에_API시스템로그를_재실행가능하게_적용하고_의미드리프트를_거부한다() {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17")
                .withDatabaseName("hajacheck")
                .withUsername("hajacheck")
                .withStartupTimeout(Duration.ofMinutes(2))
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource(MIGRATION_ROOT + MIGRATION),
                        CONTAINER_ROOT + MIGRATION)) {
            postgres.start();

            requireQuery(postgres, "portable owner prerequisite", """
                    select current_user || ':'
                           || (select count(*)::text from pg_roles where rolname = 'postgres')
                    """, "hajacheck:0");
            runPsql(postgres, MIGRATION);
            runPsql(postgres, MIGRATION);
            requireQuery(postgres, "migration executor owns table", """
                    select pg_get_userbyid(relowner)
                    from pg_class
                    where oid = 'api_system_logs'::regclass
                    """, "hajacheck");

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
                        level, request_id, http_method, endpoint, http_status, duration_ms, client_ip)
                    values (
                               'WARN', '00000000-0000-0000-0000-000000000001',
                               'GET', '/api/facilities/{id}', 404, 3, '192.0.2.0/24'
                           ),
                           (
                               'ERROR', '01ARZ3NDEKTSV4RRFFQ69G5FAV',
                               'POST', '/api/inspections', 500, 7, '2001:db8:1::/48'
                           ),
                           (
                               'WARN', '00000000-0000-0000-0000-000000000001',
                               'GET', '/api/facilities/{id}', 409, 4, null
                           );
                    """);
            requireQuery(postgres, "request id is intentionally not unique", """
                    select count(*) from api_system_logs
                    where request_id = '00000000-0000-0000-0000-000000000001'
                    """, "2");
            runSqlExpectFailure(postgres, "unknown level", """
                    insert into api_system_logs (
                        level, request_id, http_method, endpoint, http_status, duration_ms)
                    values ('INFO', '00000000-0000-0000-0000-000000000011', 'GET', '/api/test', 400, 1)
                    """, "ck_api_system_logs_level");
            runSqlExpectFailure(postgres, "WARN with 5xx", """
                    insert into api_system_logs (
                        level, request_id, http_method, endpoint, http_status, duration_ms)
                    values ('WARN', '00000000-0000-0000-0000-000000000012', 'GET', '/api/test', 500, 1)
                    """, "ck_api_system_logs_level_http_status");
            runSqlExpectFailure(postgres, "ERROR with 4xx", """
                    insert into api_system_logs (
                        level, request_id, http_method, endpoint, http_status, duration_ms)
                    values ('ERROR', '00000000-0000-0000-0000-000000000013', 'GET', '/api/test', 499, 1)
                    """, "ck_api_system_logs_level_http_status");
            runSqlExpectFailure(postgres, "negative duration", """
                    insert into api_system_logs (
                        level, request_id, http_method, endpoint, http_status, duration_ms)
                    values ('WARN', '00000000-0000-0000-0000-000000000014', 'GET', '/api/test', 400, -1)
                    """, "ck_api_system_logs_duration");
            runSqlExpectFailure(postgres, "untrusted request id", """
                    insert into api_system_logs (
                        level, request_id, http_method, endpoint, http_status, duration_ms)
                    values ('WARN', 'external-request-id', 'GET', '/api/test', 400, 1)
                    """, "ck_api_system_logs_request_id_format");
            runSqlExpectFailure(postgres, "raw endpoint query", """
                    insert into api_system_logs (
                        level, request_id, http_method, endpoint, http_status, duration_ms)
                    values (
                        'WARN', '00000000-0000-0000-0000-000000000015',
                        'GET', '/api/users/123?email=user@example.test', 400, 1
                    )
                    """, "ck_api_system_logs_endpoint_pattern");
            runSqlExpectFailure(postgres, "control character in message", """
                    insert into api_system_logs (
                        level, request_id, http_method, endpoint, http_status, message, duration_ms)
                    values (
                        'WARN', '00000000-0000-0000-0000-000000000016',
                        'GET', '/api/test', 400, E'unsafe\nmessage', 1
                    )
                    """, "ck_api_system_logs_message_no_control");
            runSqlExpectFailure(postgres, "unmasked client IP", """
                    insert into api_system_logs (
                        level, request_id, http_method, endpoint, http_status, duration_ms, client_ip)
                    values (
                        'WARN', '00000000-0000-0000-0000-000000000017',
                        'GET', '/api/test', 400, 1, '192.0.2.123/24'
                    )
                    """, "ck_api_system_logs_client_ip_masked");

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

            runSql(postgres, "tamper request-id index as unique", """
                    delete from api_system_logs
                    where id = (
                        select max(id)
                        from api_system_logs
                        where request_id = '00000000-0000-0000-0000-000000000001'
                    );
                    drop index idx_api_system_logs_request_id;
                    create unique index idx_api_system_logs_request_id
                        on api_system_logs (request_id);
                    """);
            runPsqlExpectFailure(
                    postgres, MIGRATION, "api_system_logs indexes differ from canonical DDL");
            runSql(postgres, "restore request-id index", """
                    drop index idx_api_system_logs_request_id;
                    create index idx_api_system_logs_request_id on api_system_logs (request_id);
                    """);
            runPsql(postgres, MIGRATION);

            runSql(postgres, "add unexpected expression index", """
                    create index idx_api_system_logs_unexpected_expression
                        on api_system_logs (lower(http_method));
                    """);
            runPsqlExpectFailure(
                    postgres, MIGRATION, "api_system_logs indexes differ from canonical DDL");
        }
    }

    private static void runPsql(PostgreSQLContainer<?> postgres, String fileName) {
        ExecResult result = exec(postgres, "psql", "-X", "--set", "ON_ERROR_STOP=1",
                "--username", postgres.getUsername(), "--dbname", "hajacheck",
                "--file", CONTAINER_ROOT + fileName);
        assertThat(result.getExitCode())
                .withFailMessage("%s failed:%nstdout:%n%s%nstderr:%n%s",
                        fileName, result.getStdout(), result.getStderr())
                .isZero();
    }

    private static void runPsqlExpectFailure(
            PostgreSQLContainer<?> postgres, String fileName, String expectedMessage) {
        ExecResult result = exec(postgres, "psql", "-X", "--set", "ON_ERROR_STOP=1",
                "--username", postgres.getUsername(), "--dbname", "hajacheck",
                "--file", CONTAINER_ROOT + fileName);
        assertThat(result.getExitCode()).isNotZero();
        assertThat(result.getStdout() + result.getStderr()).contains(expectedMessage);
    }

    private static void runSql(PostgreSQLContainer<?> postgres, String label, String sql) {
        ExecResult result = exec(postgres, "psql", "-X", "--set", "ON_ERROR_STOP=1",
                "--username", postgres.getUsername(), "--dbname", "hajacheck", "--command", sql);
        assertThat(result.getExitCode())
                .withFailMessage("%s failed:%nstdout:%n%s%nstderr:%n%s",
                        label, result.getStdout(), result.getStderr())
                .isZero();
    }

    private static void runSqlExpectFailure(
            PostgreSQLContainer<?> postgres, String label, String sql, String expectedMessage) {
        ExecResult result = exec(postgres, "psql", "-X", "--set", "ON_ERROR_STOP=1",
                "--username", postgres.getUsername(), "--dbname", "hajacheck", "--command", sql);
        assertThat(result.getExitCode()).isNotZero();
        assertThat(result.getStdout() + result.getStderr()).as(label).contains(expectedMessage);
    }

    private static void requireQuery(
            PostgreSQLContainer<?> postgres, String label, String sql, String expectedOutput) {
        ExecResult result = exec(postgres, "psql", "-X", "--tuples-only", "--no-align",
                "--set", "ON_ERROR_STOP=1", "--username", postgres.getUsername(), "--dbname", "hajacheck",
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
