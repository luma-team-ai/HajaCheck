package com.hajacheck.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
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
 * V37(#1325)의 <b>실제 제거 경로</b>를 검증한다.
 *
 * <p>왜 별도 테스트가 필요한가 — 제거 대상인 {@code usage_counters.lock_version} 은 <b>prod 에만</b>
 * 있는 pre-Flyway Hibernate 잔재다. 신규설치·CI·공유 dev 에는 컬럼 자체가 없어서
 * {@code FlywayBaselineIntegrationTest}(빈 DB)와 {@code FlywayBaselineOnExistingDbIntegrationTest}
 * (캐노니컬 DDL 기존 DB) 양쪽 모두 V37의 {@code IF EXISTS} 가드가 no-op 으로 skip 된다. 즉 prod
 * 가용성을 좌우하는 유일한 실제 경로가 CI 에서 검증되지 않는다(V36 이 PR #1312 머신 검수에서 받은
 * P2 지적과 동일한 구조 — 같은 공백을 되풀이하지 않는다).
 *
 * <p>이 테스트는 V36 까지 마이그레이션한 뒤 컬럼을 <b>일부러 추가</b>해 prod 지형을 모사하고,
 * ①V37 이전에는 앱의 실제 INSERT 가 정말로 실패하는지(회귀 방향 고정) ②V37 이후 컬럼이 사라지고
 * 같은 INSERT 가 성공하는지 ③컬럼이 없는 환경에서는 no-op 인지까지 확인한다.
 */
@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
class V37UsageCounterLockVersionMigrationTest {

    /** 앱이 실제로 실행하는 쿼리 — {@code UsageCounterRepository#insertPeriodRowIfAbsent} 원문. */
    private static final String APP_INSERT = """
            insert into usage_counters
                (user_plan_id, period, analyzed_image_count, facility_count,
                 analysis_request_count, seat_count, counsel_ticket_count, pdf_generation_count)
            values (?, cast(? as date), 0, cast(0 as integer),
                    0, cast(1 as integer), 0, 0)
            on conflict (user_plan_id, period) do nothing
            """;

    /** {@code ck_usage_counters_period_month_start} 를 만족해야 한다(월 1일). */
    private static final LocalDate PERIOD = LocalDate.of(2026, 7, 1);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("hajacheck_v37_usage_counter_lock_version")
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
    void V37은_prod지형의_orphan컬럼을_제거하고_막혀있던_INSERT를_통과시킨다() {
        migrateTo("36");
        JdbcTemplate jdbc = jdbc();
        simulateProdOrphanColumn(jdbc);
        Long userPlanId = createUserPlan(jdbc, "drop-path");

        // 전제 — V37 이전에는 앱의 실제 INSERT 가 정말로 실패한다(이 단언이 없으면 회귀 방향이 고정되지
        // 않아, 컬럼을 지우기만 하고 장애가 재현되는지 모른 채 통과할 수 있다).
        assertThat(lockVersionExists(jdbc)).isTrue();
        assertThatThrownBy(() -> jdbc.update(APP_INSERT, userPlanId, PERIOD))
                .hasMessageContaining("lock_version");

        migrateLatest();

        assertThat(lockVersionExists(jdbc)).isFalse();
        assertThatCode(() -> jdbc.update(APP_INSERT, userPlanId, PERIOD)).doesNotThrowAnyException();
        assertThat(usageCounterRows(jdbc)).isEqualTo(1);
    }

    @Test
    void V37은_컬럼이없는_신규설치DB에서는_noop이고_기동을_막지않는다() {
        // 신규설치(V1~V36)에는 컬럼이 애초에 없다 — 가드가 없으면 여기서 "column does not exist" 로
        // 마이그레이션이 실패해 prod 아닌 전 환경의 기동이 깨진다.
        migrateTo("36");
        JdbcTemplate jdbc = jdbc();
        assertThat(lockVersionExists(jdbc)).isFalse();

        assertThatCode(this::migrateLatest).doesNotThrowAnyException();

        assertThat(lockVersionExists(jdbc)).isFalse();
        Long userPlanId = createUserPlan(jdbc, "noop-path");
        assertThatCode(() -> jdbc.update(APP_INSERT, userPlanId, PERIOD)).doesNotThrowAnyException();
    }

    @Test
    void V37은_재적용해도_실패하지않는다() {
        migrateTo("36");
        JdbcTemplate jdbc = jdbc();
        simulateProdOrphanColumn(jdbc);
        migrateLatest();

        // Flyway 는 같은 버전을 다시 돌리지 않으므로 멱등성은 SQL 자체로 확인한다 —
        // 수동 복구·부분 적용 후 재실행 같은 상황에서도 안전해야 한다.
        assertThatCode(() -> jdbc.execute("alter table usage_counters drop column if exists lock_version"))
                .doesNotThrowAnyException();
        assertThat(lockVersionExists(jdbc)).isFalse();
    }

    /**
     * prod(2026-07-22 baseline-on-migrate 로 V1 을 실행하지 않고 스탬프만 한 DB)의 실측 상태를 모사한다 —
     * pre-Flyway Hibernate ddl-auto 가 만든 {@code NOT NULL} · DEFAULT 없는 컬럼을 되살린다.
     */
    private void simulateProdOrphanColumn(JdbcTemplate jdbc) {
        jdbc.execute("alter table usage_counters add column lock_version bigint not null");
    }

    private boolean lockVersionExists(JdbcTemplate jdbc) {
        return jdbc.queryForObject("""
                select exists (
                    select 1 from information_schema.columns
                     where table_schema = 'public' and table_name = 'usage_counters'
                       and column_name = 'lock_version')
                """, Boolean.class);
    }

    private int usageCounterRows(JdbcTemplate jdbc) {
        return jdbc.queryForObject("select count(*) from usage_counters", Integer.class);
    }

    private Long createUserPlan(JdbcTemplate jdbc, String suffix) {
        Long userId = jdbc.queryForObject("""
                insert into users (email, name, role, password_hash)
                values (?, 'V37 사용자', 'ADMIN'::role_type, 'test-password-hash')
                returning id
                """, Long.class, "v37-" + suffix + "@haja.test");
        Long planId = jdbc.queryForObject(
                "select id from plans where name = 'FREE'::plan_name_type", Long.class);
        return jdbc.queryForObject("""
                insert into user_plans (user_id, plan_id, status)
                values (?, ?, 'ACTIVE'::user_plan_status_type)
                returning id
                """, Long.class, userId, planId);
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
