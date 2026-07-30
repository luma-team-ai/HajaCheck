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

/**
 * V36(#1311)의 <b>실제 forward 생성 경로</b>를 검증한다.
 *
 * <p>왜 별도 테스트가 필요한가 — {@code FlywayBaselineIntegrationTest}(빈 DB)와
 * {@code FlywayBaselineOnExistingDbIntegrationTest}(캐노니컬 DDL 기존 DB)는 둘 다 V36 대상 객체를
 * <b>이미 갖고 있는</b> 상태라 V36의 {@code IF NOT EXISTS}/{@code DO} 가드가 전부 no-op으로 skip된다.
 * 즉 prod 기동(가용성)을 좌우하는 유일한 실제 경로 — "객체가 없는 DB에 V36이 실제로 생성하는 분기" —
 * 가 CI에서 검증되지 않는다(PR #1312 머신 검수 P2).
 *
 * <p>이 테스트는 V35까지 마이그레이션한 뒤 V36 대상 객체를 <b>일부러 제거</b>해 prod(2026-07-22
 * baseline-on-migrate 로 V1을 실행하지 않고 스탬프만 한 DB)의 상태를 모사하고, V36을 적용해 ①객체가
 * 실제로 생성되는지 ②생성된 제약이 실제로 동작하는지(중복 거부·FK 거부·트리거 갱신)까지 확인한다.
 */
@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
class V36PreFlywayGapMigrationTest {

    private static final String GAP_INDEX_LIST =
            "'uq_user_plans_active_user', 'uq_user_plans_active_company', 'uq_counsel_tickets_session', "
                    + "'idx_notifications_user_history', 'idx_menus_parent'";

    private static final String GAP_TRIGGER_LIST =
            "'trg_users_set_updated_at', 'trg_companies_set_updated_at', 'trg_facilities_set_updated_at'";

    private static final String[] LOCK_VERSION_TABLES = {
            "companies", "counsel_tickets", "defects", "notifications", "rag_documents", "reports"};

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("hajacheck_v36_preflyway_gap")
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
    void V36은_객체가없는DB에_누락분을_실제로_생성한다() {
        migrateTo("35");
        JdbcTemplate jdbc = jdbc();
        simulateProdBaselineGap(jdbc);

        // 전제 확인 — 정말로 없는 상태에서 시작해야 이 테스트가 forward 분기를 검증한다.
        assertThat(indexCount(jdbc)).isZero();
        assertThat(triggerCount(jdbc)).isZero();
        assertThat(assignedInspectorFkCount(jdbc)).isZero();
        assertThat(snippetNotNull(jdbc)).isFalse();
        assertThat(lockVersionDefaultCount(jdbc)).isZero();

        migrateLatest();

        assertThat(indexCount(jdbc)).isEqualTo(5);
        assertThat(triggerCount(jdbc)).isEqualTo(3);
        assertThat(assignedInspectorFkCount(jdbc)).isEqualTo(1);
        assertThat(snippetNotNull(jdbc)).isTrue();
        assertThat(lockVersionDefaultCount(jdbc)).isEqualTo(LOCK_VERSION_TABLES.length);

        // 의도적 제외 계약 고정 — V36 은 회사 경계 트리거를 복원하지 않는다(#604). 이 트리거는 앱
        // (AuthService.validateAssignableInspector)보다 엄격해(회사 APPROVED+VERIFIED 추가 요구,
        // 진위확인 fail-open 과 충돌) 그대로 넣으면 정상 점검 생성이 거부된다. 나중에 무심코 V36 에
        // 끼워 넣으면 이 단언이 실패해 결정을 다시 검토하게 만든다.
        assertThat(companyBoundaryTriggerCount(jdbc)).isZero();
    }

    @Test
    void V36이_만든_부분UNIQUE는_동일사용자의_두번째ACTIVE구독을_거부한다() {
        migrateTo("35");
        JdbcTemplate jdbc = jdbc();
        simulateProdBaselineGap(jdbc);
        migrateLatest();

        Long userId = createUser(jdbc, "dup-active");
        Long planId = freePlanId(jdbc);
        insertActiveUserPlan(jdbc, userId, planId);

        assertThatThrownBy(() -> insertActiveUserPlan(jdbc, userId, planId))
                .hasMessageContaining("uq_user_plans_active_user");
    }

    @Test
    void 중복ACTIVE구독이_남아있으면_V36은_조용히넘어가지않고_실패한다() {
        migrateTo("35");
        JdbcTemplate jdbc = jdbc();
        simulateProdBaselineGap(jdbc);

        // 제약이 없는 상태에서는 두 번째 ACTIVE 구독이 그대로 들어간다(가드 부재 실증).
        Long userId = createUser(jdbc, "dup-blocks");
        Long planId = freePlanId(jdbc);
        insertActiveUserPlan(jdbc, userId, planId);
        insertActiveUserPlan(jdbc, userId, planId);

        // 승격 프리플라이트가 중복 0 을 확인하는 이유 — 중복이 남아 있으면 마이그레이션이 실패한다.
        assertThatThrownBy(this::migrateLatest)
                .hasMessageContaining("uq_user_plans_active_user");
    }

    @Test
    void V36이_만든_updatedAt트리거가_실제로_갱신한다() {
        migrateTo("35");
        JdbcTemplate jdbc = jdbc();
        simulateProdBaselineGap(jdbc);
        Long userId = createUser(jdbc, "updated-at");
        LocalDateTime stale = LocalDateTime.of(2025, 1, 2, 3, 4, 5);
        jdbc.update("update users set updated_at = ? where id = ?", stale, userId);

        // 트리거가 없으므로 직접 UPDATE 해도 updated_at 이 그대로 남는다.
        jdbc.update("update users set name = 'before' where id = ?", userId);
        assertThat(jdbc.queryForObject(
                "select updated_at from users where id = ?", LocalDateTime.class, userId))
                .isEqualTo(stale);

        migrateLatest();

        jdbc.update("update users set name = 'after' where id = ?", userId);
        assertThat(jdbc.queryForObject(
                "select updated_at from users where id = ?", LocalDateTime.class, userId))
                .isAfter(stale);
    }

    @Test
    void V36이_만든_담당자FK는_존재하지않는사용자배정을_거부한다() {
        migrateTo("35");
        JdbcTemplate jdbc = jdbc();
        simulateProdBaselineGap(jdbc);
        migrateLatest();

        Long userId = createUser(jdbc, "fk-guard");
        Long facilityId = createFacility(jdbc, userId);

        assertThatThrownBy(() -> jdbc.update("""
                insert into inspections (facility_id, assigned_inspector_id, round_no,
                                         inspection_date, created_by)
                values (?, 999999999, 1, current_date, ?)
                """, facilityId, userId))
                .hasMessageContaining("fk_inspections_assigned_inspector");
    }

    /**
     * prod(baseline 스탬프로 V1 을 실행하지 않은 DB)의 실측 상태를 모사한다 —
     * V1 이 만든 V36 대상 객체를 제거해 "V1 을 실행하지 않은 DB" 와 같은 지형으로 되돌린다.
     */
    private void simulateProdBaselineGap(JdbcTemplate jdbc) {
        jdbc.execute("drop index if exists uq_user_plans_active_user");
        jdbc.execute("drop index if exists uq_user_plans_active_company");
        jdbc.execute("drop index if exists uq_counsel_tickets_session");
        jdbc.execute("drop index if exists idx_notifications_user_history");
        jdbc.execute("drop index if exists idx_menus_parent");
        jdbc.execute("drop trigger if exists trg_users_set_updated_at on users");
        jdbc.execute("drop trigger if exists trg_companies_set_updated_at on companies");
        jdbc.execute("drop trigger if exists trg_facilities_set_updated_at on facilities");
        jdbc.execute("alter table inspections drop constraint if exists fk_inspections_assigned_inspector");
        // prod 에는 회사 경계 DB 레벨 트리거도 없다(#604 — V36 이 의도적으로 복원하지 않는 대상).
        // 이 테스트에서 함께 제거하지 않으면 BEFORE INSERT 트리거가 FK 검사보다 먼저 걸려
        // FK 검증 테스트가 엉뚱한 사유로 통과·실패한다.
        jdbc.execute("drop trigger if exists trg_inspections_check_assigned_inspector_company on inspections");
        jdbc.execute("alter table chat_message_citations alter column snippet drop not null");
        for (String table : LOCK_VERSION_TABLES) {
            jdbc.execute("alter table " + table + " alter column lock_version drop default");
        }
    }

    private int indexCount(JdbcTemplate jdbc) {
        return jdbc.queryForObject(
                "select count(*) from pg_indexes where schemaname = 'public' and indexname in ("
                        + GAP_INDEX_LIST + ")", Integer.class);
    }

    private int triggerCount(JdbcTemplate jdbc) {
        return jdbc.queryForObject(
                "select count(*) from pg_trigger where not tgisinternal and tgname in ("
                        + GAP_TRIGGER_LIST + ")", Integer.class);
    }

    private int assignedInspectorFkCount(JdbcTemplate jdbc) {
        return jdbc.queryForObject("""
                select count(*) from pg_constraint
                 where conname = 'fk_inspections_assigned_inspector'
                   and conrelid = 'public.inspections'::regclass
                   and contype = 'f' and convalidated
                """, Integer.class);
    }

    private int companyBoundaryTriggerCount(JdbcTemplate jdbc) {
        return jdbc.queryForObject("""
                select count(*) from pg_trigger
                 where not tgisinternal
                   and tgname = 'trg_inspections_check_assigned_inspector_company'
                """, Integer.class);
    }

    private boolean snippetNotNull(JdbcTemplate jdbc) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select attnotnull from pg_attribute
                 where attrelid = 'public.chat_message_citations'::regclass
                   and attname = 'snippet' and not attisdropped
                """, Boolean.class));
    }

    private int lockVersionDefaultCount(JdbcTemplate jdbc) {
        return jdbc.queryForObject("""
                select count(*) from information_schema.columns
                 where table_schema = 'public' and column_name = 'lock_version'
                   and column_default = '0'
                   and table_name in ('companies', 'counsel_tickets', 'defects',
                                      'notifications', 'rag_documents', 'reports')
                """, Integer.class);
    }

    private Long createUser(JdbcTemplate jdbc, String suffix) {
        return jdbc.queryForObject("""
                insert into users (email, name, role, password_hash)
                values (?, 'V36 사용자', 'ADMIN'::role_type, 'test-password-hash')
                returning id
                """, Long.class, "v36-" + suffix + "@haja.test");
    }

    private Long createFacility(JdbcTemplate jdbc, Long ownerUserId) {
        Long companyId = jdbc.queryForObject("""
                insert into companies (
                    owner_user_id, name, business_registration_number, representative_name,
                    address, business_registration_file_url)
                values (?, 'V36 회사', ?, '대표자', '서울시', '/test/v36-company.png')
                returning id
                """, Long.class, ownerUserId, "v36-brn-" + ownerUserId);
        return jdbc.queryForObject("""
                insert into facilities (company_id, name, type)
                values (?, 'V36 시설', 'BUILDING')
                returning id
                """, Long.class, companyId);
    }

    private Long freePlanId(JdbcTemplate jdbc) {
        return jdbc.queryForObject(
                "select id from plans where name = 'FREE'::plan_name_type", Long.class);
    }

    private void insertActiveUserPlan(JdbcTemplate jdbc, Long userId, Long planId) {
        jdbc.update("""
                insert into user_plans (user_id, plan_id, status)
                values (?, ?, 'ACTIVE'::user_plan_status_type)
                """, userId, planId);
    }

    private void migrateTo(String target) {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target(target)
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
}
