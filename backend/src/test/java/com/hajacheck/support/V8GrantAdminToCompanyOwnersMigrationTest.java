package com.hajacheck.support;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * V8(grant_admin_to_company_owners, #636)의 데이터 변환을 격리 검증한다.
 *
 * <p>빈 컨테이너에 V1~V7까지만 적용(target=7)한 뒤 시드 데이터를 심고, V8만 추가 적용해
 * 소급 UPDATE 의 대상 범위가 정확한지(= owner 인 USER 만 ADMIN 으로 상향)를 고정한다.
 * 파괴적 UPDATE 라 WHERE 조건이 넓어져 전체 user 를 ADMIN 으로 만들지 않는지가 이 테스트의 핵심이다.
 */
@Testcontainers
class V8GrantAdminToCompanyOwnersMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("hajacheck_v8")
            .withUsername("postgres");

    @Test
    void owner인_USER만_ADMIN으로_상향되고_그외는_불변() {
        DataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // 1) V1~V7까지만 적용(V8 데이터 변환 전 상태 확보 — V8만 소급 UPDATE라 그 직전까지 스키마를 만든다).
        migrateTo(dataSource, "7");

        // 2) 시드: owner USER / owner 기존 ADMIN / non-owner USER / owner PLATFORM_ADMIN.
        long ownerUser = insertUser(jdbc, "owner-user@haja.com", "USER");
        long ownerAlreadyAdmin = insertUser(jdbc, "owner-admin@haja.com", "ADMIN");
        long nonOwnerUser = insertUser(jdbc, "plain-user@haja.com", "USER");
        long ownerPlatformAdmin = insertUser(jdbc, "owner-platform@haja.com", "PLATFORM_ADMIN");

        insertCompany(jdbc, ownerUser, "회사A", "1000000001");
        insertCompany(jdbc, ownerAlreadyAdmin, "회사B", "1000000002");
        insertCompany(jdbc, ownerPlatformAdmin, "회사C", "1000000003");
        // nonOwnerUser 는 어떤 회사의 owner 도 아니다.

        // 3) V8 적용(소급 UPDATE).
        migrateTo(dataSource, "8");

        // 4) 검증.
        // owner 인 USER 만 ADMIN 으로 상향.
        assertThat(roleOf(jdbc, ownerUser)).isEqualTo("ADMIN");
        // owner 아닌 USER 는 그대로 USER (전체 user 를 ADMIN 으로 만들지 않는지 = WHERE owner 스코프 확인).
        assertThat(roleOf(jdbc, nonOwnerUser)).isEqualTo("USER");
        // 이미 ADMIN 인 owner 는 그대로 ADMIN (변화 없음).
        assertThat(roleOf(jdbc, ownerAlreadyAdmin)).isEqualTo("ADMIN");
        // USER 가 아닌 owner(PLATFORM_ADMIN)는 건드리지 않는다.
        assertThat(roleOf(jdbc, ownerPlatformAdmin)).isEqualTo("PLATFORM_ADMIN");

        // 정확히 1건만 USER→ADMIN 상향됐다(ownerUser). 남은 USER 는 nonOwnerUser 1명뿐.
        Integer remainingUsers = jdbc.queryForObject(
                "select count(*) from users where role = 'USER'::role_type", Integer.class);
        assertThat(remainingUsers).isEqualTo(1);
    }

    private void migrateTo(DataSource dataSource, String target) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target(target)
                .load()
                .migrate();
    }

    private long insertUser(JdbcTemplate jdbc, String email, String role) {
        return jdbc.queryForObject("""
                insert into users (email, name, role, password_hash, status)
                values (?, ?, ?::role_type, 'x', 'ACTIVE'::user_status_type)
                returning id
                """, Long.class, email, email, role);
    }

    private void insertCompany(JdbcTemplate jdbc, long ownerUserId, String name, String brn) {
        jdbc.update("""
                insert into companies
                    (owner_user_id, name, business_registration_number,
                     representative_name, address, business_registration_file_url)
                values (?, ?, ?, ?, ?, ?)
                """, ownerUserId, name, brn, "대표", "서울시", "/f.png");
    }

    private String roleOf(JdbcTemplate jdbc, long userId) {
        return jdbc.queryForObject(
                "select role::text from users where id = ?", String.class, userId);
    }
}
