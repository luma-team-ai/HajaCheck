package com.hajacheck.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.dao.DataAccessException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** V4 레거시 데이터가 V7 점검·관리자 스키마로 안전하게 전환되는지 검증한다. */
@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
class InspectionAdminSchemaMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("hajacheck_inspection_admin_migration")
            .withUsername("postgres");

    @BeforeEach
    void resetDatabase() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .cleanDisabled(false)
                .load()
                .clean();
    }

    @Test
    void V7은_레거시데이터를_보정하고_최종제약을_적용한다() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target("3")
                .load()
                .migrate();

        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));

        Long userId = jdbc.queryForObject("""
                insert into users (email, name, role, password_hash)
                values ('v7-inspector@haja.test', 'V7 점검자', 'INSPECTOR'::role_type, 'test-password-hash')
                returning id
                """, Long.class);
        Long facilityId = jdbc.queryForObject("""
                insert into facilities (owner_id, name, type)
                values (?, 'V7 레거시 시설', 'BUILDING')
                returning id
                """, Long.class, userId);

        // 이 테스트의 목적은 담당자 회사 경계가 아니라 V7 type 백필이므로 해당 업무 트리거만 잠시 제외한다.
        jdbc.execute("alter table inspections disable trigger trg_inspections_check_assigned_inspector_company");
        Long inspectionId = jdbc.queryForObject("""
                insert into inspections
                    (facility_id, created_by, assigned_inspector_id, round_no, inspection_date)
                values (?, ?, ?, 1, ?)
                returning id
                """, Long.class, facilityId, userId, userId, Date.valueOf(LocalDate.of(2026, 7, 22)));
        jdbc.execute("alter table inspections enable trigger trg_inspections_check_assigned_inspector_company");

        assertThat(jdbc.queryForObject(
                "select max_seats from plans where name = 'ENTERPRISE'::plan_name_type", Integer.class))
                .isEqualTo(1000000);

        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();

        List<String> inspectionTypes = jdbc.queryForList("""
                select e.enumlabel
                  from pg_enum e
                  join pg_type t on t.oid = e.enumtypid
                 where t.typname = 'inspection_type'
                 order by e.enumsortorder
                """, String.class);
        assertThat(inspectionTypes).containsExactly("REGULAR", "DETAILED", "EMERGENCY");

        List<String> roles = jdbc.queryForList("""
                select e.enumlabel
                  from pg_enum e
                  join pg_type t on t.oid = e.enumtypid
                 where t.typname = 'role_type'
                 order by e.enumsortorder
                """, String.class);
        assertThat(roles).contains("PLATFORM_ADMIN");

        assertThat(jdbc.queryForObject(
                "select type::text from inspections where id = ?", String.class, inspectionId))
                .isEqualTo("REGULAR");
        assertThat(jdbc.queryForObject("""
                select atthasmissing
                  from pg_attribute
                 where attrelid = 'public.inspections'::regclass
                   and attname = 'type'
                """, Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("""
                select is_nullable
                  from information_schema.columns
                 where table_schema = 'public'
                   and table_name = 'inspections'
                   and column_name = 'type'
                """, String.class)).isEqualTo("NO");

        assertThat(jdbc.queryForObject(
                "select max_seats from plans where name = 'ENTERPRISE'::plan_name_type", Integer.class))
                .isNull();
        assertThat(jdbc.queryForObject("""
                select column_default
                  from information_schema.columns
                 where table_schema = 'public'
                   and table_name = 'plans'
                   and column_name = 'max_seats'
                """, String.class)).isNull();

        Long settingId = jdbc.queryForObject("""
                insert into inspection_notification_settings (user_id, facility_id)
                values (?, ?)
                returning id
                """, Long.class, userId, facilityId);
        assertThat(jdbc.queryForObject("""
                select notify_before_days
                  from inspection_notification_settings
                 where id = ?
                """, Integer.class, settingId)).isEqualTo(7);

        assertThatThrownBy(() -> jdbc.update("""
                insert into inspection_notification_settings
                    (user_id, facility_id, notify_before_days)
                values (?, ?, 0)
                """, userId, facilityId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("""
                insert into inspection_notification_settings (user_id, facility_id)
                values (?, ?)
                """, userId, facilityId))
                .isInstanceOf(DataAccessException.class);

        Integer cascadeForeignKeys = jdbc.queryForObject("""
                select count(*)
                  from information_schema.referential_constraints
                 where constraint_schema = 'public'
                   and constraint_name in (
                       'fk_inspection_notification_settings_user',
                       'fk_inspection_notification_settings_facility')
                   and delete_rule = 'CASCADE'
                """, Integer.class);
        assertThat(cascadeForeignKeys).isEqualTo(2);
    }

    @Test
    void V7은_이미완성된_inspections_type_컬럼도_동일계약으로_승인한다() {
        migrateToV3();
        JdbcTemplate jdbc = jdbc();
        jdbc.execute("create type public.inspection_type as enum ('REGULAR', 'DETAILED', 'EMERGENCY')");
        jdbc.execute("""
                alter table public.inspections
                    add column type public.inspection_type
                        default 'REGULAR'::public.inspection_type not null
                """);

        migrateLatest();

        assertThat(jdbc.queryForObject("""
                select a.attnotnull
                       and a.atttypid = 'public.inspection_type'::regtype
                       and replace(pg_get_expr(d.adbin, d.adrelid), 'public.', '') =
                           '''REGULAR''::inspection_type'
                  from pg_attribute a
                  join pg_attrdef d on d.adrelid = a.attrelid and d.adnum = a.attnum
                 where a.attrelid = 'public.inspections'::regclass
                   and a.attname = 'type'
                """, Boolean.class)).isTrue();
    }

    @Test
    void V7은_inspections_type_컬럼의_nullable_default_drift를_거부한다() {
        migrateToV3();
        JdbcTemplate jdbc = jdbc();
        jdbc.execute("create type public.inspection_type as enum ('REGULAR', 'DETAILED', 'EMERGENCY')");
        jdbc.execute("alter table public.inspections add column type public.inspection_type");

        assertMigrationFails("inspections.type semantics mismatch");
    }

    @Test
    void V7은_role_type의_예상밖_라벨을_거부한다() {
        migrateToV3();
        jdbc().execute("alter type public.role_type add value 'UNEXPECTED_ROLE'");

        assertMigrationFails("role_type enum labels mismatch");
    }

    @Test
    void V7은_제약이_누락된_동명_알림설정테이블을_거부한다() {
        migrateToV3();
        jdbc().execute("""
                create table public.inspection_notification_settings
                (
                    id bigint generated always as identity not null,
                    user_id bigint not null,
                    facility_id bigint not null,
                    notify_before_enabled boolean default true not null,
                    notify_before_days smallint default 7 not null,
                    warn_on_overdue_enabled boolean default false not null,
                    created_at timestamp with time zone default now() not null,
                    updated_at timestamp with time zone default now() not null
                )
                """);

        assertMigrationFails("inspection_notification_settings constraint count mismatch");
    }

    @Test
    void V7은_이름만같고_컬럼이다른_알림설정인덱스를_거부한다() {
        migrateToV3();
        JdbcTemplate jdbc = jdbc();
        createCanonicalNotificationTable(jdbc);
        jdbc.execute("""
                create index idx_inspection_notification_settings_facility
                    on public.inspection_notification_settings (user_id)
                """);

        assertMigrationFails("inspection_notification_settings index semantics mismatch");
    }

    @Test
    void V7은_이름만같고_이벤트가다른_알림설정트리거를_거부한다() {
        migrateToV3();
        JdbcTemplate jdbc = jdbc();
        createCanonicalNotificationTable(jdbc);
        jdbc.execute("""
                create index idx_inspection_notification_settings_facility
                    on public.inspection_notification_settings (facility_id)
                """);
        jdbc.execute("""
                create trigger trg_inspection_notification_settings_set_updated_at
                    before insert on public.inspection_notification_settings
                    for each row execute procedure public.set_updated_at()
                """);

        assertMigrationFails("inspection_notification_settings trigger semantics mismatch");
    }

    @Test
    void V7은_동일구조지만_NOT_VALID인_알림설정외래키를_거부한다() {
        migrateToV3();
        JdbcTemplate jdbc = jdbc();
        createCanonicalNotificationTable(jdbc);
        jdbc.execute("""
                alter table public.inspection_notification_settings
                    drop constraint fk_inspection_notification_settings_user,
                    add constraint fk_inspection_notification_settings_user
                        foreign key (user_id) references public.users on delete cascade not valid
                """);

        assertMigrationFails("inspection_notification_settings user foreign key semantics mismatch");
    }

    @Test
    void V7은_WHEN_false로_비활성화된_알림설정트리거를_거부한다() {
        migrateToV3();
        JdbcTemplate jdbc = jdbc();
        createCanonicalNotificationTable(jdbc);
        jdbc.execute("""
                create index idx_inspection_notification_settings_facility
                    on public.inspection_notification_settings (facility_id)
                """);
        jdbc.execute("""
                create trigger trg_inspection_notification_settings_set_updated_at
                    before update on public.inspection_notification_settings
                    for each row when (false)
                    execute procedure public.set_updated_at()
                """);

        assertMigrationFails("inspection_notification_settings trigger semantics mismatch");
    }

    @Test
    void V7은_UNLOGGED_알림설정테이블을_거부한다() {
        migrateToV3();
        JdbcTemplate jdbc = jdbc();
        createCanonicalNotificationTable(jdbc);
        jdbc.execute("alter table public.inspection_notification_settings set unlogged");

        assertMigrationFails("inspection_notification_settings table semantics mismatch");
    }

    @Test
    void V7은_partitioned_parent_알림설정테이블을_거부한다() {
        migrateToV3();
        jdbc().execute("""
                create table public.inspection_notification_settings
                (
                    id bigint,
                    user_id bigint not null
                ) partition by range (user_id)
                """);

        assertMigrationFails("inspection_notification_settings table semantics mismatch");
    }

    @Test
    void V7은_UPDATE_OF로_대상컬럼이_제한된_알림설정트리거를_거부한다() {
        migrateToV3();
        JdbcTemplate jdbc = jdbc();
        createCanonicalNotificationTable(jdbc);
        jdbc.execute("""
                create index idx_inspection_notification_settings_facility
                    on public.inspection_notification_settings (facility_id)
                """);
        jdbc.execute("""
                create trigger trg_inspection_notification_settings_set_updated_at
                    before update of notify_before_days on public.inspection_notification_settings
                    for each row execute procedure public.set_updated_at()
                """);

        assertMigrationFails("inspection_notification_settings trigger semantics mismatch");
    }

    @Test
    void V7은_INHERITS_부모인_알림설정테이블을_거부한다() {
        migrateToV3();
        JdbcTemplate jdbc = jdbc();
        createCanonicalNotificationTable(jdbc);
        jdbc.execute("""
                create table public.inspection_notification_settings_child
                    () inherits (public.inspection_notification_settings)
                """);

        assertMigrationFails("inspection_notification_settings table semantics mismatch");
    }

    @Test
    void V7은_ROW_LEVEL_SECURITY가_활성화된_알림설정테이블을_거부한다() {
        migrateToV3();
        JdbcTemplate jdbc = jdbc();
        createCanonicalNotificationTable(jdbc);
        jdbc.execute("""
                alter table public.inspection_notification_settings
                    enable row level security
                """);

        assertMigrationFails("inspection_notification_settings table semantics mismatch");
    }

    @Test
    void V7은_FORCE_ROW_LEVEL_SECURITY가_설정된_알림설정테이블을_거부한다() {
        migrateToV3();
        JdbcTemplate jdbc = jdbc();
        createCanonicalNotificationTable(jdbc);
        jdbc.execute("""
                alter table public.inspection_notification_settings
                    force row level security
                """);

        assertMigrationFails("inspection_notification_settings table semantics mismatch");
    }

    @Test
    void V7은_예상밖_추가_UNIQUE_알림설정인덱스를_거부한다() {
        migrateToV3();
        JdbcTemplate jdbc = jdbc();
        createCanonicalNotificationTable(jdbc);
        jdbc.execute("""
                create unique index unexpected_inspection_notification_settings_facility
                    on public.inspection_notification_settings (facility_id)
                """);

        assertMigrationFails("inspection_notification_settings index set mismatch");
    }

    @Test
    void V7은_예상밖_추가_사용자트리거를_거부한다() {
        migrateToV3();
        JdbcTemplate jdbc = jdbc();
        createCanonicalNotificationTable(jdbc);
        jdbc.execute("""
                create index idx_inspection_notification_settings_facility
                    on public.inspection_notification_settings (facility_id)
                """);
        jdbc.execute("""
                create trigger unexpected_inspection_notification_settings_trigger
                    before insert on public.inspection_notification_settings
                    for each row execute procedure public.set_updated_at()
                """);

        assertMigrationFails("inspection_notification_settings trigger set mismatch");
    }

    @Test
    void V7_SQL은_동일스키마에서_직접_두번실행해도_같은계약을_유지한다() throws Exception {
        migrateToV4();
        JdbcTemplate jdbc = jdbc();
        String v7Sql = new ClassPathResource("db/migration/V7__inspection_admin_schema.sql")
                .getContentAsString(StandardCharsets.UTF_8);

        jdbc.execute(v7Sql);
        jdbc.execute(v7Sql);

        assertThat(jdbc.queryForObject("""
                select count(*)
                  from pg_trigger
                 where tgrelid = 'public.inspection_notification_settings'::regclass
                   and tgname = 'trg_inspection_notification_settings_set_updated_at'
                   and not tgisinternal
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*)
                  from pg_attribute
                 where attrelid = 'public.inspections'::regclass
                   and attname = 'type'
                   and not attisdropped
                """, Integer.class)).isEqualTo(1);
    }

    private void migrateToV3() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target("3")
                .load()
                .migrate();
    }

    private void migrateToV4() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target("4")
                .load()
                .migrate();
    }

    private void migrateLatest() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
    }

    private void assertMigrationFails(String expectedMessage) {
        assertThatThrownBy(this::migrateLatest)
                .hasStackTraceContaining(expectedMessage);
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    private void createCanonicalNotificationTable(JdbcTemplate jdbc) {
        jdbc.execute("""
                create table public.inspection_notification_settings
                (
                    id bigint generated always as identity primary key,
                    user_id bigint not null
                        constraint fk_inspection_notification_settings_user
                            references public.users on delete cascade,
                    facility_id bigint not null
                        constraint fk_inspection_notification_settings_facility
                            references public.facilities on delete cascade,
                    notify_before_enabled boolean default true not null,
                    notify_before_days smallint default 7 not null,
                    warn_on_overdue_enabled boolean default false not null,
                    created_at timestamp with time zone default now() not null,
                    updated_at timestamp with time zone default now() not null,
                    constraint uk_inspection_notification_settings_user_facility
                        unique (user_id, facility_id),
                    constraint ck_inspection_notification_settings_before_days
                        check (notify_before_days between 1 and 365)
                )
                """);
    }
}
