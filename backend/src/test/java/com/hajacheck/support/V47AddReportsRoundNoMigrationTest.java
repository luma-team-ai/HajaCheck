package com.hajacheck.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicLong;
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

/**
 * V47(reports.round_no 추가 + 백필 + NOT NULL, #1702)의 <b>데이터 전이 범위</b>를 격리 검증한다
 * (V43AddInspectionsPerformedAtMigrationTest와 동일한 target(N)/target(N+1) 대조 패턴).
 *
 * <p>V47은 <b>데이터 전제 마이그레이션</b>이다 — 백필이 전 행을 채우지 못하면 이어지는 {@code set not
 * null}이 실패해 앱 기동 자체가 거부된다(#531 형태). {@code reports.inspection_id}가 V1에서
 * {@code not null references inspections}라 고아 행은 구조적으로 불가능하지만, 그 전제를 실제 DDL 위에서
 * 실측으로 고정한다.
 *
 * <p>빈 DB(신규 설치) 경로는 {@code FlywayBaselineIntegrationTest}가, 캐노니컬 DDL 기존 DB 경로는
 * {@code FlywayBaselineOnExistingDbIntegrationTest}가 각각 V47 포함 전량 적용을 이미 검증한다.
 */
@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
class V47AddReportsRoundNoMigrationTest {

    private static final AtomicLong BRN_SEQ = new AtomicLong(1);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("hajacheck_v47_report_round_no")
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
    void 백필_기존보고서는_대상점검의회차를그대로물려받는다() {
        migrateTo("46");
        JdbcTemplate jdbc = jdbc();
        Fixture fx = seedInspection(jdbc, "v47-backfill", 3);
        long reportId = insertReport(jdbc, fx.inspectionId(), 1, "DRAFT");

        migrateTo("47");

        assertThat(roundNoOf(jdbc, reportId)).isEqualTo(3);
    }

    @Test
    void 백필_확정보고서도_동일하게회차가채워진다() {
        // FINALIZED 동결은 "이후 재정렬을 따라가지 않는다"는 뜻이지, 백필 대상에서 빠진다는 뜻이 아니다 —
        // 백필 시점의 현재 회차가 곧 그 보고서가 발급된 회차다.
        migrateTo("46");
        JdbcTemplate jdbc = jdbc();
        Fixture fx = seedInspection(jdbc, "v47-finalized", 5);
        long reportId = insertReport(jdbc, fx.inspectionId(), 1, "FINALIZED");

        migrateTo("47");

        assertThat(roundNoOf(jdbc, reportId)).isEqualTo(5);
    }

    @Test
    void 백필후_round_no는_NOT_NULL이라_null삽입이거부된다() {
        migrateTo("46");
        JdbcTemplate jdbc = jdbc();
        Fixture fx = seedInspection(jdbc, "v47-not-null", 1);

        migrateTo("47");

        assertThat(isNotNullColumn(jdbc, "reports", "round_no")).isTrue();
        assertThatThrownBy(() -> jdbc.update("""
                insert into reports (inspection_id, version, content_json, round_no)
                values (?, 9, '{}'::jsonb, null)
                """, fx.inspectionId()))
                .hasMessageContaining("round_no");
    }

    @Test
    void 보고서가없는DB에서는_컬럼만추가되고_백필은no_op이다() {
        migrateTo("46");
        JdbcTemplate jdbc = jdbc();
        assertThat(jdbc.queryForObject("select count(*) from reports", Integer.class)).isZero();

        migrateTo("47");

        assertThat(isNotNullColumn(jdbc, "reports", "round_no")).isTrue();
    }

    @Test
    void V47은_멱등하다_재실행해도_이미채워진값은불변이다() {
        migrateTo("46");
        JdbcTemplate jdbc = jdbc();
        Fixture fx = seedInspection(jdbc, "v47-idempotent", 2);
        long reportId = insertReport(jdbc, fx.inspectionId(), 1, "FINALIZED");

        migrateTo("47");
        assertThat(roundNoOf(jdbc, reportId)).isEqualTo(2);

        // 재실행 전에 점검 회차가 밀려도(재정렬 모사) 백필은 이미 채워진 행을 다시 계산하지 않는다
        // (WHERE round_no is null) — 확정 보고서 동결이 마이그레이션 재적용으로 풀리면 안 된다.
        jdbc.update("update inspections set round_no = 7 where id = ?", fx.inspectionId());
        jdbc.execute(migrationSql());

        assertThat(roundNoOf(jdbc, reportId)).isEqualTo(2);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private record Fixture(long ownerId, long companyId, long facilityId, long inspectionId) {
    }

    private Fixture seedInspection(JdbcTemplate jdbc, String emailPrefix, int roundNo) {
        long owner = jdbc.queryForObject("""
                insert into users (email, name, role, password_hash, status)
                values (?, 'V47 사용자', 'INSPECTOR'::role_type, 'test-password-hash', 'ACTIVE'::user_status_type)
                returning id
                """, Long.class, emailPrefix + "-owner@haja.test");
        String brn = "V47" + BRN_SEQ.getAndIncrement();
        long company = jdbc.queryForObject("""
                insert into companies (owner_user_id, name, business_registration_number,
                                       representative_name, address, business_registration_file_url,
                                       status, verification_status)
                values (?, ?, ?, '대표자', '서울시', '/test/v47.png',
                        'APPROVED'::company_status_type, 'VERIFIED'::business_verification_status_type)
                returning id
                """, Long.class, owner, emailPrefix + " 회사", brn);
        jdbc.update("update users set company_id = ? where id = ?", company, owner);
        jdbc.update("""
                insert into company_memberships (company_id, user_id, status, approved_at)
                values (?, ?, 'APPROVED'::company_membership_status_type, now())
                """, company, owner);
        long facility = jdbc.queryForObject(
                "insert into facilities (company_id, name, type) values (?, ?, 'BUILDING') returning id",
                Long.class, company, emailPrefix + " 빌딩");
        long inspection = jdbc.queryForObject("""
                insert into inspections (facility_id, created_by, assigned_inspector_id, round_no, inspection_date)
                values (?, ?, ?, ?, current_date) returning id
                """, Long.class, facility, owner, owner, roundNo);
        return new Fixture(owner, company, facility, inspection);
    }

    private long insertReport(JdbcTemplate jdbc, long inspectionId, int version, String status) {
        return jdbc.queryForObject("""
                insert into reports (inspection_id, version, content_json, status)
                values (?, ?, '{"summary":"v47"}'::jsonb, cast(? as report_status_type)) returning id
                """, Long.class, inspectionId, version, status);
    }

    private Integer roundNoOf(JdbcTemplate jdbc, long reportId) {
        return jdbc.queryForObject("select round_no from reports where id = ?", Integer.class, reportId);
    }

    private boolean isNotNullColumn(JdbcTemplate jdbc, String table, String column) {
        String nullable = jdbc.queryForObject("""
                select is_nullable from information_schema.columns
                 where table_name = ? and column_name = ?
                """, String.class, table, column);
        return "NO".equals(nullable);
    }

    private String migrationSql() {
        try (var in = new org.springframework.core.io.ClassPathResource(
                "db/migration/V47__add_reports_round_no.sql").getInputStream()) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("V47 마이그레이션 리소스를 읽지 못했다", e);
        }
    }

    private void migrateTo(String target) {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target(target)
                .load()
                .migrate();
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }
}
