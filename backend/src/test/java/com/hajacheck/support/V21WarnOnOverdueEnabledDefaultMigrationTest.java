package com.hajacheck.support;

import static org.assertj.core.api.Assertions.assertThat;

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
 * V21이 inspection_notification_settings.warn_on_overdue_enabled 컬럼 DEFAULT를 true로 되돌리고,
 * 이미 false로 저장돼 있던 기존 행도 true로 백필하는지 검증한다(HAJA-498).
 *
 * <p>배경: #540 ③ 알림설정 게이팅 최초 도입 시 이 컬럼의 DB 기본값(false, V7)을 애플리케이션 폴백값으로
 * 그대로 썼더니, 예정일이 지난(overdue) 시설물에 더 이상 알림이 발행되지 않는 회귀가 발생했다.
 * Polalise 승인(옵션1)으로 컬럼 기본값을 true로 되돌린다.
 *
 * <p>확인 시점(2026-07-27) 기준 공유 dev DB의 flyway_schema_history는 V18까지만 적용돼 있고
 * inspection_notification_settings는 0행이라, 이 마이그레이션이 실제로 되돌릴 기존 데이터는 없다.
 * 이 테스트는 그 사실과 무관하게(배포 시점 레이스 대비) "이미 false 행이 있었다면" 시나리오를
 * Testcontainers로 재현해 백필 UPDATE가 실제로 동작하는지 증명한다 — 공유 dev DB에는 이 마이그레이션을
 * 적용하지 않는다(격리된 컨테이너에서만 검증).
 */
@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
class V21WarnOnOverdueEnabledDefaultMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("hajacheck_v21_warn_overdue_default")
            .withUsername("postgres");

    @Test
    void V21은_컬럼기본값을_true로바꾸고_기존false행도_true로백필한다() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .cleanDisabled(false)
                .load()
                .clean();

        // V21 배포 이전 상태(이 코드베이스 로컬 기준 최신 병합본 V18)까지만 적용 — inspection_notification_settings
        // 테이블은 V7에서 이미 생성돼 있어(warn_on_overdue_enabled default false) 이 시점에 기존 행을 만들 수 있다.
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target("18")
                .load()
                .migrate();

        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));

        Long userId = jdbc.queryForObject("""
                insert into users (email, name, role, password_hash)
                values ('v21-owner@haja.test', 'V21 소유자', 'USER'::role_type, 'test-password-hash')
                returning id
                """, Long.class);
        Long companyId = jdbc.queryForObject("""
                insert into companies
                    (owner_user_id, name, business_registration_number, representative_name,
                     address, business_registration_file_url)
                values (?, 'V21 테스트 회사', '000-00-00000', '대표자', '서울시', 'https://example.com/brn.pdf')
                returning id
                """, Long.class, userId);
        Long facilityId = jdbc.queryForObject("""
                insert into facilities (company_id, name, type)
                values (?, 'V21 레거시 시설', 'BUILDING')
                returning id
                """, Long.class, companyId);

        // V21 배포 이전에 만들어졌을 수 있는 기존 행 — 그 시점 컬럼 기본값(false)을 그대로 물려받은 상태를
        // 재현한다(실측: 확인 시점 기준 이런 행은 0건이지만, 배포 레이스 대비 방어 검증).
        Long legacySettingId = jdbc.queryForObject("""
                insert into inspection_notification_settings
                    (user_id, facility_id, warn_on_overdue_enabled)
                values (?, ?, false)
                returning id
                """, Long.class, userId, facilityId);

        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target("21")
                .load()
                .migrate();

        // 1) 컬럼 DEFAULT 자체가 true로 바뀌었는지(향후 값 미지정 INSERT가 true가 되는 근거).
        assertThat(jdbc.queryForObject("""
                select column_default
                  from information_schema.columns
                 where table_schema = 'public'
                   and table_name = 'inspection_notification_settings'
                   and column_name = 'warn_on_overdue_enabled'
                """, String.class)).isEqualTo("true");

        // 2) 기존에 false로 저장돼 있던 행이 true로 백필됐는지.
        assertThat(jdbc.queryForObject("""
                select warn_on_overdue_enabled
                  from inspection_notification_settings
                 where id = ?
                """, Boolean.class, legacySettingId)).isTrue();
    }
}