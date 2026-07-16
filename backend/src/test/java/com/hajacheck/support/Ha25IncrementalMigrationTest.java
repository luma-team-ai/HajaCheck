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
 * 운영 증분 경로(v0.3 → HAJA-25)를 실제 psql autocommit으로 적용한 뒤 새 DB에 적용한
 * 캐노니컬 DDL과 PostgreSQL 카탈로그를 대조하고, JPA 전체 스키마를 validate한다.
 * CREATE INDEX CONCURRENTLY를 포함하므로 JDBC 트랜잭션 기반 SQL 실행기로 대체하지 않는다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ActiveProfiles("test")
class Ha25IncrementalMigrationTest {

    private static final String MIGRATION_ROOT = "db/migrations/";
    private static final String CONTAINER_ROOT = "/tmp/";
    private static final String CANONICAL_DDL_FILE = "HajaCheck_script.sql";
    private static final String CANONICAL_DATABASE = "hajacheck_canonical";
    private static final String SCHEMA_SIGNATURE_SQL = """
            with schema_objects as (
                select 'ENUM'::text as kind,
                       type_meta.typname::text as object_name,
                       string_agg(enum_meta.enumlabel::text, ',' order by enum_meta.enumsortorder) as definition
                from pg_type type_meta
                join pg_namespace namespace on namespace.oid = type_meta.typnamespace
                join pg_enum enum_meta on enum_meta.enumtypid = type_meta.oid
                where namespace.nspname = 'public'
                group by type_meta.typname

                union all

                select 'TABLE', table_meta.relname,
                       concat_ws('|', table_meta.relkind::text, table_meta.relpersistence::text)
                from pg_class table_meta
                join pg_namespace namespace on namespace.oid = table_meta.relnamespace
                where namespace.nspname = 'public'
                  and table_meta.relkind in ('r', 'p', 'v', 'm')

                union all

                select 'COLUMN', table_meta.relname || '.' || attribute.attname,
                       concat_ws('|',
                           format_type(attribute.atttypid, attribute.atttypmod),
                           case when attribute.attnotnull then 'NOT NULL' else 'NULL' end,
                           coalesce(pg_get_expr(default_meta.adbin, default_meta.adrelid, true), ''),
                           attribute.attidentity::text,
                           attribute.attgenerated::text)
                from pg_class table_meta
                join pg_namespace namespace on namespace.oid = table_meta.relnamespace
                join pg_attribute attribute on attribute.attrelid = table_meta.oid
                left join pg_attrdef default_meta
                  on default_meta.adrelid = attribute.attrelid
                 and default_meta.adnum = attribute.attnum
                where namespace.nspname = 'public'
                  and table_meta.relkind in ('r', 'p', 'v', 'm')
                  and attribute.attnum > 0
                  and not attribute.attisdropped

                union all

                select 'CONSTRAINT', table_meta.relname || '.' || constraint_meta.conname,
                       concat_ws('|', constraint_meta.contype::text,
                           constraint_meta.convalidated::text,
                           pg_get_constraintdef(constraint_meta.oid, true))
                from pg_constraint constraint_meta
                join pg_class table_meta on table_meta.oid = constraint_meta.conrelid
                join pg_namespace namespace on namespace.oid = table_meta.relnamespace
                where namespace.nspname = 'public'

                union all

                select 'INDEX', index_class.relname,
                       regexp_replace(pg_get_indexdef(index_class.oid), '[[:space:]]+', ' ', 'g')
                from pg_index index_meta
                join pg_class index_class on index_class.oid = index_meta.indexrelid
                join pg_class table_meta on table_meta.oid = index_meta.indrelid
                join pg_namespace namespace on namespace.oid = table_meta.relnamespace
                where namespace.nspname = 'public'

                union all

                select 'TRIGGER', table_meta.relname || '.' || trigger_meta.tgname,
                       concat_ws('|', trigger_meta.tgenabled::text,
                           regexp_replace(pg_get_triggerdef(trigger_meta.oid, true), '[[:space:]]+', ' ', 'g'))
                from pg_trigger trigger_meta
                join pg_class table_meta on table_meta.oid = trigger_meta.tgrelid
                join pg_namespace namespace on namespace.oid = table_meta.relnamespace
                where namespace.nspname = 'public'
                  and not trigger_meta.tgisinternal

                union all

                select 'SEQUENCE', sequence_class.relname,
                       concat_ws('|', format_type(sequence_meta.seqtypid, null),
                           sequence_meta.seqstart::text, sequence_meta.seqincrement::text,
                           sequence_meta.seqmin::text, sequence_meta.seqmax::text,
                           sequence_meta.seqcache::text, sequence_meta.seqcycle::text)
                from pg_sequence sequence_meta
                join pg_class sequence_class on sequence_class.oid = sequence_meta.seqrelid
                join pg_namespace namespace on namespace.oid = sequence_class.relnamespace
                where namespace.nspname = 'public'

                union all

                select 'FUNCTION', procedure_meta.proname || '(' ||
                           pg_get_function_identity_arguments(procedure_meta.oid) || ')',
                       regexp_replace(pg_get_functiondef(procedure_meta.oid), '[[:space:]]+', ' ', 'g')
                from pg_proc procedure_meta
                join pg_namespace namespace on namespace.oid = procedure_meta.pronamespace
                where namespace.nspname = 'public'
                  and procedure_meta.prokind = 'f'
            )
            select kind || '|' || object_name || '|' || definition
            from schema_objects
            order by kind, object_name;
            """;
    private static final String LEGACY_FIXTURE_SQL = """
            insert into users (email, name, password_hash)
            values ('approved-owner@ha25.test', 'approved owner', '<password-hash-placeholder>'),
                   ('legacy-member@ha25.test', 'legacy member', '<password-hash-placeholder>'),
                   ('pending-owner@ha25.test', 'pending owner', '<password-hash-placeholder>');

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
    void v03PlusHa25Migration_matchesCanonicalSchemaAndAllJpaMappings() {
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

        // HAJA-25 P2: 유효한 APPROVED 멤버십이 없는 사용자는 레거시 company_id가 정리되어야
        // AuthService.validateAssignableInspector 등 "같은 회사" 판정이 인가 우회로 이어지지 않는다.
        String approvedCompanyId = query(postgres(),
                "select id::text from companies where name = 'approved company'");
        assertThat(query(postgres(), """
                select email || ':' || coalesce(company_id::text, 'NULL')
                from users
                order by email
                """)).isEqualTo("""
                approved-owner@ha25.test:%s
                legacy-member@ha25.test:NULL
                pending-owner@ha25.test:NULL""".formatted(approvedCompanyId));
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
                        MountableFile.forClasspathResource("db/" + CANONICAL_DDL_FILE),
                        CONTAINER_ROOT + CANONICAL_DDL_FILE)
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
        assertV03BaselineContract(postgres);
        runSql(postgres, "legacy fixture", LEGACY_FIXTURE_SQL);
        runPsql(postgres, "20260716_01_ha25_expand.sql");
        runPsql(postgres, "20260716_01_ha25_expand.sql");
        assertLockVersionBackfill(postgres);

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
        assertCanonicalSchemaParity(postgres);
        return postgres;
    }

    private static void assertV03BaselineContract(PostgreSQLContainer<?> postgres) {
        requireQuery(postgres, "tracked v0.3 baseline contract", """
                select case when
                    exists (
                        select 1
                        from pg_constraint constraint_meta
                        where constraint_meta.conrelid = 'user_consents'::regclass
                          and constraint_meta.contype = 'u'
                          and (
                              select array_agg(attribute.attname::text order by key.ordinality)
                              from unnest(constraint_meta.conkey) with ordinality as key(attnum, ordinality)
                              join pg_attribute attribute
                                on attribute.attrelid = constraint_meta.conrelid
                               and attribute.attnum = key.attnum
                          ) = array['user_id', 'policy_type', 'policy_version']
                    )
                    and exists (
                        select 1
                        from pg_constraint constraint_meta
                        where constraint_meta.conrelid = 'inspections'::regclass
                          and constraint_meta.contype = 'u'
                          and (
                              select array_agg(attribute.attname::text order by key.ordinality)
                              from unnest(constraint_meta.conkey) with ordinality as key(attnum, ordinality)
                              join pg_attribute attribute
                                on attribute.attrelid = constraint_meta.conrelid
                               and attribute.attnum = key.attnum
                          ) = array['facility_id', 'round_no']
                    )
                    and exists (
                        select 1
                        from information_schema.columns
                        where table_schema = 'public'
                          and table_name = 'media'
                          and column_name = 'mime_signature_verified'
                          and is_nullable = 'NO'
                          and lower(column_default) like 'false%'
                    )
                then 'ready' else 'missing' end
                """, "ready");
    }

    private static void assertLockVersionBackfill(PostgreSQLContainer<?> postgres) {
        requireQuery(postgres, "lock_version backfill", """
                select count(*)
                from (
                    select lock_version from companies
                    union all select lock_version from company_memberships
                    union all select lock_version from defects
                    union all select lock_version from reports
                    union all select lock_version from counsel_tickets
                    union all select lock_version from rag_documents
                    union all select lock_version from notifications
                ) state_machine_rows
                where lock_version is distinct from 0
                """, "0");
    }

    private static void assertCanonicalSchemaParity(PostgreSQLContainer<?> postgres) {
        runSql(postgres, "create canonical comparison database",
                "create database " + CANONICAL_DATABASE);
        runPsql(postgres, CANONICAL_DDL_FILE, CANONICAL_DATABASE);

        String migratedSchema = query(
                postgres, postgres.getDatabaseName(), SCHEMA_SIGNATURE_SQL);
        String canonicalSchema = query(
                postgres, CANONICAL_DATABASE, SCHEMA_SIGNATURE_SQL);
        if (!canonicalSchema.equals(migratedSchema)) {
            java.util.Set<String> canonicalOnly = new java.util.TreeSet<>(
                    canonicalSchema.lines().toList());
            canonicalOnly.removeAll(migratedSchema.lines().toList());
            java.util.Set<String> migratedOnly = new java.util.TreeSet<>(
                    migratedSchema.lines().toList());
            migratedOnly.removeAll(canonicalSchema.lines().toList());
            throw new IllegalStateException(
                    "Canonical DDL and v0.3 + HAJA-25 migration schemas differ."
                            + "%nCanonical only:%n%s%nMigrated only:%n%s".formatted(
                            String.join("\n", canonicalOnly.stream().limit(40).toList()),
                            String.join("\n", migratedOnly.stream().limit(40).toList())));
        }
    }

    private static void assertVerifierRejectsSemanticDrift(PostgreSQLContainer<?> postgres) {
        runSql(postgres, "insert duplicate v0.3 inspection key", """
                alter table inspections drop constraint inspections_facility_id_round_no_key;
                insert into inspections (
                    facility_id, created_by, assigned_inspector_id, round_no,
                    inspection_date, status)
                select facility_id, created_by, assigned_inspector_id, round_no,
                       inspection_date, status
                from inspections
                order by id
                limit 1;
                """);
        runPsqlExpectFailure(
                postgres, "20260716_03_ha25_verify.sql", "duplicate inspections");
        runSql(postgres, "restore v0.3 inspection uniqueness", """
                delete from inspections
                where id = (select max(id) from inspections);
                alter table inspections
                    add constraint inspections_facility_id_round_no_key
                    unique (facility_id, round_no);
                """);

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

        runSql(postgres, "leave null optimistic-lock row", """
                alter table companies alter column lock_version drop not null;
                update companies
                set lock_version = null
                where id = (select min(id) from companies);
                """);
        runPsqlExpectFailure(
                postgres, "20260716_03_ha25_verify.sql", "null lock_version");
        runSql(postgres, "restore optimistic-lock rows", """
                update companies set lock_version = 0 where lock_version is null;
                alter table companies alter column lock_version set not null;
                """);

        runSql(postgres, "tamper lock default", """
                alter table companies alter column lock_version drop default;
                """);
        runPsqlExpectFailure(
                postgres, "20260716_03_ha25_verify.sql", "must be DEFAULT 0 NOT NULL");
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

        runSql(postgres, "drop assigned inspector company-boundary trigger", """
                drop trigger trg_inspections_check_assigned_inspector_company on inspections;
                """);
        runPsqlExpectFailure(
                postgres, "20260716_03_ha25_verify.sql",
                "trg_inspections_check_assigned_inspector_company trigger is missing");
        runSql(postgres, "restore assigned inspector company-boundary trigger", """
                create trigger trg_inspections_check_assigned_inspector_company
                    before insert or update of assigned_inspector_id, created_by on inspections
                    for each row execute procedure check_inspection_assigned_inspector_company();
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
        runPsql(postgres, fileName, postgres.getDatabaseName());
    }

    private static void runPsql(
            PostgreSQLContainer<?> postgres, String fileName, String databaseName) {
        Container.ExecResult result = execute(postgres,
                "psql", "-X", "--set", "ON_ERROR_STOP=1",
                "--username", postgres.getUsername(),
                "--dbname", databaseName,
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
        return query(postgres, postgres.getDatabaseName(), sql);
    }

    private static String query(
            PostgreSQLContainer<?> postgres, String databaseName, String sql) {
        Container.ExecResult result = execute(postgres,
                "psql", "-X", "--set", "ON_ERROR_STOP=1",
                "--tuples-only", "--no-align",
                "--username", postgres.getUsername(),
                "--dbname", databaseName,
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
