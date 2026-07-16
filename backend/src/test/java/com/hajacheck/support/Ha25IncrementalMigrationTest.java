package com.hajacheck.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * 운영 증분 경로(v0.3 → HAJA-25)를 실제 psql autocommit으로 적용한 뒤 JPA 전체 스키마를 validate한다.
 * CREATE INDEX CONCURRENTLY를 포함하므로 JDBC 트랜잭션 기반 SQL 실행기로 대체하지 않는다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ActiveProfiles("test")
class Ha25IncrementalMigrationTest {

    private static final String MIGRATION_ROOT = "db/migrations/";
    private static final String CONTAINER_ROOT = "/tmp/";
    private static final String LEGACY_FIXTURE_SQL = """
            insert into users (email, name, password_hash)
            values ('approved-owner@ha25.test', 'approved owner', 'hash'),
                   ('legacy-member@ha25.test', 'legacy member', 'hash'),
                   ('pending-owner@ha25.test', 'pending owner', 'hash');

            insert into companies (
                owner_user_id, name, business_registration_number, representative_name,
                address, business_registration_file_url, verification_status, verified_at,
                status, reviewed_at)
            select id, 'approved company', 'HA25-APPROVED', 'owner', 'Seoul',
                   'https://files.example/approved.pdf',
                   'VERIFIED'::business_verification_status_type, now(),
                   'APPROVED'::company_status_type, now()
            from users where email = 'approved-owner@ha25.test';

            insert into companies (
                owner_user_id, name, business_registration_number, representative_name,
                address, business_registration_file_url)
            select id, 'pending company', 'HA25-PENDING', 'owner', 'Busan',
                   'https://files.example/pending.pdf'
            from users where email = 'pending-owner@ha25.test';

            update users u
            set company_id = c.id
            from companies c
            where c.name = 'approved company'
              and u.email in ('approved-owner@ha25.test', 'legacy-member@ha25.test');

            insert into facilities (owner_id, name, type)
            select id, 'legacy facility', 'BUILDING'
            from users where email = 'approved-owner@ha25.test';

            insert into inspections (facility_id, created_by, round_no, inspection_date)
            select f.id, u.id, 1, date '2026-07-16'
            from facilities f
            join users u on u.email = 'approved-owner@ha25.test'
            where f.name = 'legacy facility';

            insert into chat_sessions (user_id, session_type)
            select id, 'RAG'::chat_session_type
            from users where email = 'approved-owner@ha25.test';

            insert into chat_messages (session_id, sender, content)
            select id, 'BOT'::chat_sender_type, 'legacy answer'
            from chat_sessions;

            insert into rag_documents (title, source_type, file_url, embedding_status, chunk_count, embedded_at)
            values ('legacy regulation', 'LAW'::rag_doc_source_type,
                    'https://files.example/legacy-law.pdf',
                    'DONE'::rag_embedding_status_type, 1, now());

            insert into chat_message_citations (message_id, document_id, chunk_ref, snippet)
            select m.id, d.id, 'legacy-chunk', null
            from chat_messages m
            cross join rag_documents d;
            """;
    private static final String LEGACY_BACKFILL_SQL = """
            update inspections
            set assigned_inspector_id = created_by
            where assigned_inspector_id is null;

            update rag_documents
            set target_collection = 'REGULATIONS'::rag_target_collection_type
            where target_collection is null;

            update chat_message_citations
            set locator = '제1조', snippet = 'legacy snippet'
            where locator is null or snippet is null;
            """;

    private static final String EXTERNAL_URL = System.getenv("HA25_MIGRATION_POSTGRES_URL");
    private static final String EXTERNAL_USERNAME = System.getenv("HA25_MIGRATION_POSTGRES_USERNAME");
    private static final String EXTERNAL_PASSWORD = System.getenv("HA25_MIGRATION_POSTGRES_PASSWORD");
    private static final boolean EXTERNAL_SCHEMA_READY = Boolean.parseBoolean(
            System.getenv("HA25_MIGRATION_EXTERNAL_SCHEMA_READY"));

    private static final PostgreSQLContainer<?> POSTGRES = createDatabaseUnderTest();

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> POSTGRES == null ? EXTERNAL_URL : POSTGRES.getJdbcUrl());
        registry.add("spring.datasource.username",
                () -> POSTGRES == null ? EXTERNAL_USERNAME : POSTGRES.getUsername());
        registry.add("spring.datasource.password",
                () -> POSTGRES == null ? EXTERNAL_PASSWORD : POSTGRES.getPassword());
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.dialect",
                () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @Test
    void v03PlusHa25Migration_matchesAllJpaMappings() {
        // ApplicationContext 시작 시 Hibernate validate가 전체 Entity를 대조한다.
        if (POSTGRES == null) {
            assertThat(EXTERNAL_SCHEMA_READY).isTrue();
            return;
        }

        assertThat(POSTGRES.isRunning()).isTrue();
        assertThat(query(postgres(), """
                select u.email || ':' || cm.status::text
                from company_memberships cm
                join users u on u.id = cm.user_id
                order by u.email
                """)).isEqualTo("""
                approved-owner@ha25.test:APPROVED
                legacy-member@ha25.test:PENDING
                pending-owner@ha25.test:PENDING""");
    }

    private static boolean useExternalDatabase() {
        return EXTERNAL_URL != null && !EXTERNAL_URL.isBlank();
    }

    private static PostgreSQLContainer<?> createDatabaseUnderTest() {
        if (!useExternalDatabase()) {
            return createMigratedContainer();
        }
        if (!EXTERNAL_SCHEMA_READY) {
            throw new IllegalStateException(
                    "HA25_MIGRATION_POSTGRES_URL is validate-only. "
                            + "Apply the complete v0.3 → HAJA-25 procedure to a disposable database and set "
                            + "HA25_MIGRATION_EXTERNAL_SCHEMA_READY=true explicitly.");
        }
        return null;
    }

    private static PostgreSQLContainer<?> createMigratedContainer() {
        PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17")
                .withDatabaseName("hajacheck")
                .withUsername("postgres")
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource(MIGRATION_ROOT + "HajaCheck_script_v0.3.sql"),
                        CONTAINER_ROOT + "HajaCheck_script_v0.3.sql")
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource(MIGRATION_ROOT + "20260716_01_ha25_expand.sql"),
                        CONTAINER_ROOT + "20260716_01_ha25_expand.sql")
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource(MIGRATION_ROOT + "20260716_02_ha25_finalize.sql"),
                        CONTAINER_ROOT + "20260716_02_ha25_finalize.sql")
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource(MIGRATION_ROOT + "20260716_03_ha25_verify.sql"),
                        CONTAINER_ROOT + "20260716_03_ha25_verify.sql");
        postgres.start();

        runPsql(postgres, "HajaCheck_script_v0.3.sql");
        runSql(postgres, "legacy fixture", LEGACY_FIXTURE_SQL);
        runPsql(postgres, "20260716_01_ha25_expand.sql");
        runPsql(postgres, "20260716_01_ha25_expand.sql");

        requireQuery(postgres, "membership backfill", """
                select u.email || ':' || cm.status::text
                from company_memberships cm
                join users u on u.id = cm.user_id
                order by u.email
                """, """
                approved-owner@ha25.test:APPROVED
                legacy-member@ha25.test:PENDING
                pending-owner@ha25.test:PENDING""");
        runPsqlExpectFailure(
                postgres, "20260716_02_ha25_finalize.sql", "assigned_inspector_id backfill");

        runSql(postgres, "legacy backfill", LEGACY_BACKFILL_SQL);
        runPsql(postgres, "20260716_02_ha25_finalize.sql");
        runPsql(postgres, "20260716_02_ha25_finalize.sql");
        runPsql(postgres, "20260716_03_ha25_verify.sql");

        assertVerifierRejectsSemanticDrift(postgres);
        runPsql(postgres, "20260716_03_ha25_verify.sql");
        return postgres;
    }

    private static void assertVerifierRejectsSemanticDrift(PostgreSQLContainer<?> postgres) {
        runSql(postgres, "tamper partial unique index", """
                drop index uq_company_memberships_approved_user;
                create index uq_company_memberships_approved_user
                    on company_memberships (company_id);
                """);
        runPsqlExpectFailure(
                postgres, "20260716_03_ha25_verify.sql", "required indexes");
        runSql(postgres, "restore partial unique index", """
                drop index uq_company_memberships_approved_user;
                create unique index uq_company_memberships_approved_user
                    on company_memberships (user_id)
                    where status = 'APPROVED'::company_membership_status_type;
                """);

        runSql(postgres, "tamper citation nullability", """
                alter table chat_message_citations alter column snippet drop not null;
                """);
        runPsqlExpectFailure(
                postgres, "20260716_03_ha25_verify.sql", "snippet is missing");
        runSql(postgres, "restore citation nullability", """
                alter table chat_message_citations alter column snippet set not null;
                """);

        runSql(postgres, "tamper lock default", """
                alter table companies alter column lock_version drop default;
                """);
        runPsqlExpectFailure(
                postgres, "20260716_03_ha25_verify.sql", "must be bigint");
        runSql(postgres, "restore lock default", """
                alter table companies alter column lock_version set default 0;
                """);

        runSql(postgres, "tamper assigned inspector foreign key", """
                alter table inspections drop constraint fk_inspections_assigned_inspector;
                alter table inspections add constraint fk_inspections_assigned_inspector
                    foreign key (assigned_inspector_id) references facilities(id);
                """);
        runPsqlExpectFailure(
                postgres, "20260716_03_ha25_verify.sql", "assigned inspector foreign key");
        runSql(postgres, "restore assigned inspector foreign key", """
                alter table inspections drop constraint fk_inspections_assigned_inspector;
                alter table inspections add constraint fk_inspections_assigned_inspector
                    foreign key (assigned_inspector_id) references users(id);
                """);

        runSql(postgres, "set updated_at trigger to replica-only", """
                alter table company_memberships enable replica trigger trg_company_memberships_set_updated_at;
                """);
        runPsqlExpectFailure(
                postgres, "20260716_03_ha25_verify.sql", "triggers are missing");
        runSql(postgres, "restore updated_at trigger", """
                alter table company_memberships enable trigger trg_company_memberships_set_updated_at;
                """);
    }

    private static void runPsql(PostgreSQLContainer<?> postgres, String fileName) {
        Container.ExecResult result = execute(postgres,
                "psql", "-X", "--set", "ON_ERROR_STOP=1",
                "--username", postgres.getUsername(),
                "--dbname", postgres.getDatabaseName(),
                "--file", CONTAINER_ROOT + fileName);
        requireSuccess(result, fileName);
    }

    private static void runPsqlExpectFailure(
            PostgreSQLContainer<?> postgres, String fileName, String expectedMessage) {
        Container.ExecResult result = execute(postgres,
                "psql", "-X", "--set", "ON_ERROR_STOP=1",
                "--username", postgres.getUsername(),
                "--dbname", postgres.getDatabaseName(),
                "--file", CONTAINER_ROOT + fileName);
        String output = result.getStdout() + result.getStderr();
        if (result.getExitCode() == 0 || !output.contains(expectedMessage)) {
            throw new IllegalStateException(
                    "psql was expected to fail for %s with '%s' (exit=%d)%nstdout:%n%s%nstderr:%n%s"
                            .formatted(fileName, expectedMessage, result.getExitCode(),
                                    result.getStdout(), result.getStderr()));
        }
    }

    private static void runSql(PostgreSQLContainer<?> postgres, String label, String sql) {
        Container.ExecResult result = execute(postgres,
                "psql", "-X", "--set", "ON_ERROR_STOP=1",
                "--username", postgres.getUsername(),
                "--dbname", postgres.getDatabaseName(),
                "--command", sql);
        requireSuccess(result, label);
    }

    private static String query(PostgreSQLContainer<?> postgres, String sql) {
        Container.ExecResult result = execute(postgres,
                "psql", "-X", "--set", "ON_ERROR_STOP=1",
                "--tuples-only", "--no-align",
                "--username", postgres.getUsername(),
                "--dbname", postgres.getDatabaseName(),
                "--command", sql);
        requireSuccess(result, "query");
        return result.getStdout().strip().replace("\r\n", "\n");
    }

    private static void requireQuery(
            PostgreSQLContainer<?> postgres, String label, String sql, String expected) {
        String actual = query(postgres, sql);
        if (!expected.equals(actual)) {
            throw new IllegalStateException(
                    "%s mismatch%nexpected:%n%s%nactual:%n%s".formatted(label, expected, actual));
        }
    }

    private static PostgreSQLContainer<?> postgres() {
        if (POSTGRES == null) {
            throw new IllegalStateException("Testcontainers PostgreSQL is not active");
        }
        return POSTGRES;
    }

    private static Container.ExecResult execute(PostgreSQLContainer<?> postgres, String... command) {
        try {
            return postgres.execInContainer(command);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot execute psql", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while executing psql", exception);
        }
    }

    private static void requireSuccess(Container.ExecResult result, String label) {
        if (result.getExitCode() != 0) {
            throw new IllegalStateException(
                    "psql failed for %s (exit=%d)%nstdout:%n%s%nstderr:%n%s"
                            .formatted(label, result.getExitCode(),
                                    result.getStdout(), result.getStderr()));
        }
    }
}
