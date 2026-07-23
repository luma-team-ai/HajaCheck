package com.hajacheck.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** V11이 시설 소유 범위를 사용자에서 회사로 안전하게 전환하는지 검증한다. */
@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
class FacilityCompanyMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("hajacheck_facility_company_migration")
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
    void V11은_owner시설을_사용자의회사로이관하고_최종제약을적용한다() {
        migrateToV7();
        JdbcTemplate jdbc = jdbc();
        LegacyIds ids = createCompanyOwner(jdbc, "mapped");
        LocalDateTime originalUpdatedAt = LocalDateTime.of(2025, 1, 2, 3, 4, 5);
        Long facilityId = jdbc.queryForObject("""
                insert into facilities (owner_id, name, type, updated_at)
                values (?, 'V11 이관 시설', 'BUILDING', ?)
                returning id
                """, Long.class, ids.userId(), originalUpdatedAt);

        migrateLatest();

        assertThat(jdbc.queryForObject(
                "select company_id from facilities where id = ?", Long.class, facilityId))
                .isEqualTo(ids.companyId());
        assertThat(jdbc.queryForObject(
                "select updated_at from facilities where id = ?", LocalDateTime.class, facilityId))
                .isEqualTo(originalUpdatedAt);
        assertThat(jdbc.queryForObject("""
                select tgenabled::text
                  from pg_trigger
                 where tgrelid = 'public.facilities'::regclass
                   and tgname = 'trg_facilities_set_updated_at'
                """, String.class)).isEqualTo("O");
        assertThat(columnExists(jdbc, "owner_id")).isFalse();
        assertThat(columnExists(jdbc, "company_id")).isTrue();
        assertThat(jdbc.queryForObject("""
                select a.atttypid = 'pg_catalog.int8'::regtype and a.attnotnull
                  from pg_attribute a
                 where a.attrelid = 'public.facilities'::regclass
                   and a.attname = 'company_id'
                   and not a.attisdropped
                """, Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("""
                select count(*)
                  from pg_constraint c
                 where c.conrelid = 'public.facilities'::regclass
                   and c.contype = 'f'
                   and c.conname = 'fk_facilities_company'
                   and c.confrelid = 'public.companies'::regclass
                   and c.convalidated
                   and not c.condeferrable
                   and not c.condeferred
                   and c.confmatchtype = 's'
                   and c.confupdtype = 'a'
                   and c.confdeltype = 'a'
                   and c.confkey[1] = (
                       select attnum
                         from pg_attribute
                        where attrelid = 'public.companies'::regclass
                          and attname = 'id'
                          and not attisdropped
                   )
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*)
                  from pg_indexes
                 where schemaname = 'public'
                   and tablename = 'facilities'
                   and indexname = 'idx_facilities_company'
                   and indexdef like '%USING btree (company_id)'
                """, Integer.class)).isEqualTo(1);
    }

    @Test
    void V11은_회사없는소유자의시설이있으면_구체적인예외로실패한다() {
        migrateToV7();
        JdbcTemplate jdbc = jdbc();
        Long userId = jdbc.queryForObject("""
                insert into users (email, name, role, password_hash)
                values ('v11-unmapped@haja.test', 'V11 무소속', 'USER'::role_type, 'test-password-hash')
                returning id
                """, Long.class);
        Long facilityId = jdbc.queryForObject("""
                insert into facilities (owner_id, name, type)
                values (?, 'V11 이관 불가 시설', 'BUILDING')
                returning id
                """, Long.class, userId);

        assertThatThrownBy(this::migrateLatest)
                .hasStackTraceContaining("cannot map facilities.owner_id to company_id")
                .hasStackTraceContaining("facility_id=" + facilityId)
                .hasStackTraceContaining("owner_id=" + userId);
    }

    @Test
    void V11은_이미회사소유스키마인_baseline기존DB를_검증후그대로승인한다() {
        migrateToV7();
        JdbcTemplate jdbc = jdbc();
        LegacyIds ids = createCompanyOwner(jdbc, "baseline");
        jdbc.execute("""
                alter table facilities
                    add column company_id bigint
                """);
        jdbc.update("""
                update facilities f
                   set company_id = u.company_id
                  from users u
                 where u.id = f.owner_id
                """);
        jdbc.execute("alter table facilities alter column company_id set not null");
        jdbc.execute("alter table facilities drop column owner_id");
        jdbc.execute("""
                alter table facilities
                    add constraint fk_facilities_company
                        foreign key (company_id) references companies (id)
                """);
        jdbc.execute("create index idx_facilities_company on facilities (company_id)");

        migrateLatest();

        assertThat(columnExists(jdbc, "owner_id")).isFalse();
        assertThat(columnExists(jdbc, "company_id")).isTrue();
        assertThat(jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success = true and version = '10'",
                Integer.class)).isEqualTo(1);
        assertThat(ids.companyId()).isPositive();
    }

    @Test
    void V11은_이미회사소유스키마의_FK의미가다르면_실패한다() {
        migrateToV7();
        JdbcTemplate jdbc = jdbc();
        jdbc.execute("alter table facilities add column company_id bigint");
        jdbc.execute("alter table facilities alter column company_id set not null");
        jdbc.execute("alter table facilities drop column owner_id");
        jdbc.execute("""
                alter table facilities
                    add constraint fk_facilities_company
                        foreign key (company_id) references companies (id)
                        deferrable initially deferred
                """);
        jdbc.execute("create index idx_facilities_company on facilities (company_id)");

        assertThatThrownBy(this::migrateLatest)
                .hasStackTraceContaining("facilities.company_id foreign key semantics mismatch");
    }

    @Test
    void V11은_companyId가_companiesId외컬럼을참조하면_실패한다() {
        migrateToV7();
        JdbcTemplate jdbc = jdbc();
        jdbc.execute("alter table companies add column alternate_id bigint unique");
        jdbc.execute("alter table facilities add column company_id bigint");
        jdbc.execute("alter table facilities alter column company_id set not null");
        jdbc.execute("alter table facilities drop column owner_id");
        jdbc.execute("""
                alter table facilities
                    add constraint fk_facilities_company
                        foreign key (company_id) references companies (alternate_id)
                """);
        jdbc.execute("create index idx_facilities_company on facilities (company_id)");

        assertThatThrownBy(this::migrateLatest)
                .hasStackTraceContaining("facilities.company_id foreign key semantics mismatch");
    }

    @Test
    void V11은_history없는_oldOwner스키마를_baselineOnMigrate로_V2부터재적용한다() {
        migrateToV7();
        JdbcTemplate jdbc = jdbc();
        LegacyIds ids = createCompanyOwner(jdbc, "historyless");
        Long facilityId = jdbc.queryForObject("""
                insert into facilities (owner_id, name, type)
                values (?, 'history 없는 시설', 'BUILDING')
                returning id
                """, Long.class, ids.userId());
        jdbc.execute("drop table flyway_schema_history");

        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .baselineOnMigrate(true)
                .baselineVersion("1")
                .load()
                .migrate();

        assertThat(jdbc.queryForObject(
                "select company_id from facilities where id = ?", Long.class, facilityId))
                .isEqualTo(ids.companyId());
        assertThat(jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success = true and version = '10'",
                Integer.class)).isEqualTo(1);
    }

    private LegacyIds createCompanyOwner(JdbcTemplate jdbc, String suffix) {
        Long userId = jdbc.queryForObject("""
                insert into users (email, name, role, password_hash)
                values (?, 'V11 회사 소유자', 'ADMIN'::role_type, 'test-password-hash')
                returning id
                """, Long.class, "v11-" + suffix + "@haja.test");
        Long companyId = jdbc.queryForObject("""
                insert into companies (
                    owner_user_id, name, business_registration_number, representative_name,
                    address, business_registration_file_url)
                values (?, 'V11 회사', ?, '대표자', '서울시', '/test/v11-company.png')
                returning id
                """, Long.class, userId, "v11-brn-" + suffix);
        jdbc.update("update users set company_id = ? where id = ?", companyId, userId);
        return new LegacyIds(userId, companyId);
    }

    private boolean columnExists(JdbcTemplate jdbc, String columnName) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (
                    select 1
                      from information_schema.columns
                     where table_schema = 'public'
                       and table_name = 'facilities'
                       and column_name = ?
                )
                """, Boolean.class, columnName));
    }

    private void migrateToV7() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target("7")
                .load()
                .migrate();
    }

    private void migrateLatest() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    private record LegacyIds(Long userId, Long companyId) {
    }
}
