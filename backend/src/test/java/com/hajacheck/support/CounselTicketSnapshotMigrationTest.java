package com.hajacheck.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * V18(counsel_ticket_snapshot_and_chat_attachment, #20/HAJA-33)이 기존 행이 있는 구스키마에
 * 대해 어떻게 동작하는지 고정하는 문서화 테스트.
 *
 * <p>PR머신 리뷰(P1, PR #820)에서, ticket_number/category/title을 DEFAULT·백필 없이 NOT NULL로
 * 한 번에 추가하면 counsel_tickets에 기존 행이 있는 환경에서 Flyway forward-apply가 실패해 앱 기동을
 * 중단시킬 수 있다는 지적(#531과 같은 클래스의 가용성 리스크)이 있었다. {@link CounselTypeMigrationTest}
 * (V14)와 달리 이 마이그레이션은 nullable 추가 → 방어적 백필 → NOT NULL 전환 3단계로 다시 작성해
 * prod 실측 검증에 의존하지 않고도 안전하도록 했다 — 이 테스트는 "기존 행이 있어도 forward-apply가
 * 성공하고, 그 행이 식별 가능한 백필값으로 채워진다"는 계약을 실제로 고정한다.
 */
@Testcontainers
class CounselTicketSnapshotMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("hajacheck_v18")
            .withUsername("postgres");

    @Test
    void V18은_스냅샷필드없는기존행이있어도_백필하며_성공한다() {
        DataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // 1) V1~V17까지만 적용(스냅샷 필드를 추가하는 V18 적용 전 상태 확보).
        migrateTo(dataSource, "17");

        // 2) ticket_number/category/title이 아직 없는 구스키마에 counsel_tickets 행 1건을 심는다.
        //    V14가 counsel_type을 이미 NOT NULL로 추가했으므로 그 값도 함께 채운다.
        Long userId = jdbc.queryForObject("""
                insert into users (email, name, role, password_hash)
                values ('v18-counsel-legacy@haja.test', 'V18 레거시 상담요청자', 'USER'::role_type,
                        'test-password-hash')
                returning id
                """, Long.class);
        Long ticketId = jdbc.queryForObject("""
                insert into counsel_tickets (user_id, counsel_type)
                values (?, 'USAGE'::counsel_type)
                returning id
                """, Long.class, userId);

        // 3) V18 forward-apply — 방어적 백필 덕분에 기존 행이 있어도 성공해야 한다.
        assertThatCode(() -> migrateTo(dataSource, "18")).doesNotThrowAnyException();

        // 4) 백필값이 식별 가능한 형태(CS-LEGACY-{id}/UNKNOWN)로 채워졌는지 확인한다.
        var row = jdbc.queryForMap(
                "select ticket_number, category, title from counsel_tickets where id = ?", ticketId);
        assertThat(row.get("ticket_number")).isEqualTo("CS-LEGACY-" + ticketId);
        assertThat(row.get("category")).isEqualTo("UNKNOWN");
        assertThat(row.get("title")).isEqualTo("UNKNOWN");
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
