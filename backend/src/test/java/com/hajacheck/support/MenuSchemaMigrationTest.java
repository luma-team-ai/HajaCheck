package com.hajacheck.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

class MenuSchemaMigrationTest {

    private static final String MIGRATION_ROOT = "db/migrations/";
    private static final String CONTAINER_ROOT = "/tmp/";
    private static final String BASELINE = "HajaCheck_script_v0.3.sql";
    private static final String EXPAND = "20260716_04_menu_schema_expand.sql";
    private static final String VERIFY = "20260716_05_menu_schema_verify.sql";

    @Test
    void v03기준선에_메뉴스키마를_재실행가능하게_적용하고_의미드리프트를_탐지한다() {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17")
                .withDatabaseName("hajacheck")
                .withUsername("postgres")
                .withStartupTimeout(Duration.ofMinutes(2))
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource(MIGRATION_ROOT + BASELINE),
                        CONTAINER_ROOT + BASELINE)
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource(MIGRATION_ROOT + EXPAND),
                        CONTAINER_ROOT + EXPAND)
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource(MIGRATION_ROOT + VERIFY),
                        CONTAINER_ROOT + VERIFY)) {
            postgres.start();

            runPsql(postgres, BASELINE);
            runPsql(postgres, EXPAND);
            runPsql(postgres, EXPAND);
            runPsql(postgres, VERIFY);

            runSql(postgres, "valid menu tree", """
                    insert into menus (code, name, menu_type)
                    values ('TEST_GROUP', 'test group', 'GROUP');

                    insert into menus (code, name, menu_type, parent_id, path, icon_key)
                    select 'TEST_CHILD', 'test child', 'INTERNAL', id, '/test', 'test-icon'
                    from menus where code = 'TEST_GROUP';

                    insert into menu_role_access (menu_id, role)
                    select id, 'ADMIN' from menus where code = 'TEST_CHILD';
                    """);
            runSql(postgres, "updated-at trigger", """
                    select pg_sleep(0.02);
                    update menus set name = 'updated child' where code = 'TEST_CHILD';
                    """);
            requireQuery(postgres, "updated-at trigger result", """
                    select updated_at > created_at from menus where code = 'TEST_CHILD'
                    """, "t");

            runSqlExpectFailure(postgres, "GROUP with two icons", """
                    insert into menus (code, name, menu_type, icon_key, icon_url)
                    values ('INVALID_GROUP_ICONS', 'invalid', 'GROUP', 'icon', 'https://example.test/icon.svg')
                    """, "ck_menus_icon_single");
            runSqlExpectFailure(postgres, "leaf without icon", """
                    insert into menus (code, name, menu_type, path)
                    values ('INVALID_LEAF_ICON', 'invalid', 'INTERNAL', '/invalid')
                    """, "ck_menus_icon_single");
            runSqlExpectFailure(postgres, "GROUP with a path", """
                    insert into menus (code, name, menu_type, path)
                    values ('INVALID_GROUP_PATH', 'invalid', 'GROUP', '/invalid')
                    """, "ck_menus_path_by_type");
            runSqlExpectFailure(postgres, "leaf without path", """
                    insert into menus (code, name, menu_type, icon_key)
                    values ('INVALID_LEAF_PATH', 'invalid', 'INTERNAL', 'test-icon')
                    """, "ck_menus_path_by_type");
            runSqlExpectFailure(postgres, "negative sort_order", """
                    insert into menus (code, name, menu_type, sort_order)
                    values ('INVALID_SORT_ORDER', 'invalid', 'GROUP', -1)
                    """, "ck_menus_sort_order_nonnegative");
            runSqlExpectFailure(postgres, "duplicate code", """
                    insert into menus (code, name, menu_type)
                    values ('TEST_GROUP', 'duplicate', 'GROUP')
                    """, "menus_code_key");
            runSqlExpectFailure(postgres, "duplicate role mapping", """
                    insert into menu_role_access (menu_id, role)
                    select id, 'ADMIN' from menus where code = 'TEST_CHILD'
                    """, "menu_role_access_pkey");
            runSqlExpectFailure(postgres, "delete parent with child", """
                    delete from menus where code = 'TEST_GROUP'
                    """, "fk_menus_parent");

            runSql(postgres, "cascade role mapping", """
                    delete from menus where code = 'TEST_CHILD'
                    """);
            requireQuery(postgres, "role mapping cascade result", """
                    select count(*) from menu_role_access
                    """, "0");
            runPsql(postgres, VERIFY);

            runSqlExpectFailure(postgres, "direct role mapping on GROUP menu", """
                    insert into menu_role_access (menu_id, role)
                    select id, 'ADMIN' from menus where code = 'TEST_GROUP'
                    """, "GROUP menus must not have direct menu_role_access rows");

            runSql(postgres, "tamper away GROUP role mapping trigger", """
                    drop trigger trg_menu_role_access_reject_group on menu_role_access
                    """);
            runPsqlExpectFailure(postgres, VERIFY, "trg_menu_role_access_reject_group trigger is missing or misconfigured");
            runSql(postgres, "restore GROUP role mapping trigger", """
                    create trigger trg_menu_role_access_reject_group
                        before insert or update of menu_id on menu_role_access
                        for each row execute procedure check_menu_role_access_not_group();
                    """);
            runPsql(postgres, VERIFY);

            runSql(postgres, "tamper created-by foreign key", """
                    alter table menus drop constraint menus_created_by_fkey;
                    alter table menus add constraint menus_created_by_fkey
                        foreign key (updated_by) references users(id);
                    """);
            runPsqlExpectFailure(postgres, VERIFY, "menu schema foreign keys are missing or semantically different");
            runSql(postgres, "restore created-by foreign key", """
                    alter table menus drop constraint menus_created_by_fkey;
                    alter table menus add constraint menus_created_by_fkey
                        foreign key (created_by) references users(id);
                    """);
            runPsql(postgres, VERIFY);

            runSql(postgres, "tamper parent index", """
                    drop index idx_menus_parent;
                    create index idx_menus_parent on menus (code);
                    """);
            runPsqlExpectFailure(postgres, VERIFY, "menu schema indexes are missing or invalid");
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
        assertThat(result.getStdout() + result.getStderr())
                .as(label)
                .contains(expectedMessage);
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
