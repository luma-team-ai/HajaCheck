package com.hajacheck.support;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * V13(add_counsel_type, #743)이 기존 행이 있는 구스키마에 대해 어떻게 동작하는지 고정하는 문서화 테스트.
 *
 * <p>PR #772 리뷰(P1)에서, {@code counsel_tickets.counsel_type}을 DEFAULT 없는 NOT NULL로 추가하면
 * 기존 행이 있을 때 PostgreSQL이 'column contains null values'로 거부해 Flyway가 앱 기동을 중단시킨다는
 * 지적(#531과 같은 클래스의 리스크)이 있었다. prod(hajacheck-arm1-postgres-1) {@code counsel_tickets}가
 * 실측 0건임을 직접 확인(2026-07-24, V13__add_counsel_type.sql 헤더 주석 참고)해 현재 설계를 그대로
 * 유지하기로 했으므로, 이 테스트는 DEFAULT를 추가하는 대신 "행이 있는 구스키마에 forward-apply하면
 * 지금 설계상 반드시 실패한다"는 계약을 실제로 고정한다 — 즉 실패를 기대하는 회귀 테스트다.
 */
@Testcontainers
class CounselTypeMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("hajacheck_v13")
            .withUsername("postgres");

    @Test
    void V13은_counsel_type없는기존행이있으면_NOT_NULL위반으로실패한다() {
        DataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // 1) V1~V12까지만 적용(V13 적용 전 상태 확보).
        migrateTo(dataSource, "12");

        // 2) counsel_type 컬럼이 아직 없는 구스키마에 counsel_tickets 행 1건을 심는다.
        Long userId = jdbc.queryForObject("""
                insert into users (email, name, role, password_hash)
                values ('v13-counsel-legacy@haja.test', 'V13 레거시 상담요청자', 'USER'::role_type,
                        'test-password-hash')
                returning id
                """, Long.class);
        jdbc.update("""
                insert into counsel_tickets (user_id)
                values (?)
                """, userId);

        // 3) V13 forward-apply — 현재 설계(DEFAULT 없는 NOT NULL)이므로 반드시 실패해야 한다.
        //    실패하지 않고 통과해 버리면, 이 계약(prod 0건 근거로 DEFAULT를 안 붙이기로 한 결정)이
        //    조용히 깨진 것이므로 이 테스트가 대신 실패해 알려준다.
        assertThatThrownBy(() -> migrateTo(dataSource, "13"))
                .isInstanceOf(FlywayException.class)
                .hasStackTraceContaining("counsel_type")
                .hasStackTraceContaining("null");
    }

    private void migrateTo(DataSource dataSource, String target) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target(target)
                .load()
                .migrate();
    }
}
