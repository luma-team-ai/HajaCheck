package com.hajacheck.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * V27가 user_plans.current_period_start/current_period_end 를 기존 파생 규칙
 * (startedAt / startedAt + 1개월, 유료만) 그대로 백필하는지 검증한다(#1104 / HAJA-525).
 *
 * <p>{@link V21WarnOnOverdueEnabledDefaultMigrationTest}와 동일 패턴 — V26까지 적용된 상태를 재현해
 * "V27 배포 이전에 이미 존재하던 user_plans 행"을 만든 뒤 V27만 추가 적용해 백필 UPDATE 를 검증한다.
 */
@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
class V27BillingPeriodBackfillMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("hajacheck_v25_billing_period_backfill")
            .withUsername("postgres");

    @Test
    void V27는_유료플랜만_currentPeriodEnd를_채우고_FREE는_NULL로둔다() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .cleanDisabled(false)
                .load()
                .clean();

        // V27 배포 이전 상태(V26)까지만 적용 — plans 3티어(V2 시드: FREE 0원, STANDARD 29000원)가
        // 이미 존재하고, user_plans 는 아직 current_period_* 컬럼이 없는 시점을 재현한다.
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target("26")
                .load()
                .migrate();

        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));

        Long freePlanId = jdbc.queryForObject(
                "select id from plans where name = 'FREE'::plan_name_type", Long.class);
        Long standardPlanId = jdbc.queryForObject(
                "select id from plans where name = 'STANDARD'::plan_name_type", Long.class);

        Long freeOwnerId = jdbc.queryForObject("""
                insert into users (email, name, role, password_hash)
                values ('v27-free-owner@haja.test', 'V27 무료 소유자', 'USER'::role_type, 'test-password-hash')
                returning id
                """, Long.class);
        Long freeCompanyId = jdbc.queryForObject("""
                insert into companies
                    (owner_user_id, name, business_registration_number, representative_name,
                     address, business_registration_file_url)
                values (?, 'V27 무료 회사', '000-00-00001', '대표자', '서울시', 'https://example.com/brn.pdf')
                returning id
                """, Long.class, freeOwnerId);

        Long paidOwnerId = jdbc.queryForObject("""
                insert into users (email, name, role, password_hash)
                values ('v27-paid-owner@haja.test', 'V27 유료 소유자', 'USER'::role_type, 'test-password-hash')
                returning id
                """, Long.class);
        Long paidCompanyId = jdbc.queryForObject("""
                insert into companies
                    (owner_user_id, name, business_registration_number, representative_name,
                     address, business_registration_file_url)
                values (?, 'V27 유료 회사', '000-00-00002', '대표자', '서울시', 'https://example.com/brn.pdf')
                returning id
                """, Long.class, paidOwnerId);

        Timestamp startedAt = Timestamp.valueOf("2026-06-15 09:00:00");

        Long freeUserPlanId = jdbc.queryForObject("""
                insert into user_plans (company_id, plan_id, status, started_at)
                values (?, ?, 'ACTIVE'::user_plan_status_type, ?)
                returning id
                """, Long.class, freeCompanyId, freePlanId, startedAt);
        Long paidUserPlanId = jdbc.queryForObject("""
                insert into user_plans (company_id, plan_id, status, started_at)
                values (?, ?, 'ACTIVE'::user_plan_status_type, ?)
                returning id
                """, Long.class, paidCompanyId, standardPlanId, startedAt);

        // 여기서 V27만 추가 적용해 백필 UPDATE 가 기존 행에 어떻게 반영되는지를 본다.
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target("27")
                .load()
                .migrate();

        // FREE(price_monthly=0) — current_period_start 는 백필되지만 current_period_end 는 NULL(무기한).
        Timestamp freePeriodStart = jdbc.queryForObject(
                "select current_period_start from user_plans where id = ?", Timestamp.class, freeUserPlanId);
        Timestamp freePeriodEnd = jdbc.queryForObject(
                "select current_period_end from user_plans where id = ?", Timestamp.class, freeUserPlanId);
        assertThat(freePeriodStart).isEqualTo(startedAt);
        assertThat(freePeriodEnd).isNull();

        // STANDARD(price_monthly=29000) — current_period_end 가 started_at + 1개월로 채워진다.
        Timestamp paidPeriodStart = jdbc.queryForObject(
                "select current_period_start from user_plans where id = ?", Timestamp.class, paidUserPlanId);
        Timestamp paidPeriodEnd = jdbc.queryForObject(
                "select current_period_end from user_plans where id = ?", Timestamp.class, paidUserPlanId);
        assertThat(paidPeriodStart).isEqualTo(startedAt);
        assertThat(paidPeriodEnd).isEqualTo(Timestamp.valueOf("2026-07-15 09:00:00"));
    }
}
