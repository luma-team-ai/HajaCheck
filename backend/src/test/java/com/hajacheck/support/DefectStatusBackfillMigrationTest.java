package com.hajacheck.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * V22(defect_status_type 4단계 축소)의 <b>데이터 백필 경로</b>를 고정한다.
 *
 * <p><b>왜 별도 테스트인가</b>: {@link FlywayBaselineOnExistingDbIntegrationTest} 와
 * {@link FlywayBaselineIntegrationTest} 는 defects 테이블이 비어 있는 상태로 마이그레이션을 돌린다.
 * 그래서 V22 의 {@code update defects set status='CONFIRMED' where status::text='ACTION_PENDING'} 이
 * 항상 0행 no-op 으로 지나가고, <b>UPDATE 를 지우거나 타입 교체 뒤로 옮겨도 CI 는 계속 초록</b>이다.
 * 정작 ACTION_PENDING 행이 실제로 존재하는 prod 에서만 "invalid input value for enum" 으로 기동이
 * 막힌다 — #531 과 같은 형태의 배포 정지다. 여기서 그 경로를 실제 데이터로 고정한다.
 *
 * <p><b>구성</b>: 캐노니컬 DDL(= ACTION_PENDING 이 살아있는 pre-V22 스냅샷)로 컨테이너를 띄운 뒤,
 * <b>Flyway 가 돌기 전에</b> ACTION_PENDING 상태 하자 1건(+ FK 부모 users/companies/facilities/
 * inspections)을 심는다. 시드를 스프링 컨텍스트 기동 전에 끝내야 하므로 {@code @Container} 대신
 * static 초기화 블록에서 컨테이너를 직접 start 한다(Testcontainers 싱글턴 컨테이너 패턴) —
 * {@code @DynamicPropertySource} 의 supplier 는 컨텍스트 refresh 시점에 평가되므로 이 순서가 보장된다.
 */
@SpringBootTest
@ActiveProfiles("test")
class DefectStatusBackfillMigrationTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("hajacheck_defect_status_backfill")
            .withUsername("postgres")
            // 기존 DB 모사: ACTION_PENDING 이 아직 살아있는 캐노니컬 전체 스키마.
            .withInitScript("db/HajaCheck_script.sql");

    static {
        POSTGRES.start();
        seedActionPendingDefect();
    }

    /** Flyway 실행 전에 ACTION_PENDING 하자 1건과 그 FK 부모들을 심는다. */
    private static void seedActionPendingDefect() {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            // inspections 에는 "담당 점검자가 승인된 회사의 유효 멤버인지" 검증하는 트리거가 걸려 있다.
            // 승인/검증 완료 회사 + APPROVED 멤버십 + ADMIN 권한 사용자까지 갖춰야 통과한다.
            statement.execute("""
                    insert into users (email, name, role, password_hash)
                    values ('backfill-owner@haja.com', '백필오너', 'ADMIN', 'x');

                    insert into companies (owner_user_id, name, business_registration_number,
                                           representative_name, address, business_registration_file_url,
                                           status, verification_status)
                    select id, '백필회사', '000-00-00000', '백필오너', '서울시', '/files/x.png',
                           'APPROVED', 'VERIFIED'
                    from users where email = 'backfill-owner@haja.com';

                    update users
                    set company_id = (select id from companies where name = '백필회사')
                    where email = 'backfill-owner@haja.com';

                    insert into company_memberships (company_id, user_id, status, approved_at)
                    select c.id, u.id, 'APPROVED', now()
                    from companies c
                    cross join users u
                    where c.name = '백필회사' and u.email = 'backfill-owner@haja.com';

                    insert into facilities (company_id, name, type)
                    select id, '백필빌딩', 'BUILDING' from companies where name = '백필회사';

                    insert into inspections (facility_id, created_by, assigned_inspector_id,
                                             round_no, inspection_date)
                    select f.id, u.id, u.id, 1, current_date
                    from facilities f
                    cross join users u
                    where f.name = '백필빌딩' and u.email = 'backfill-owner@haja.com';

                    insert into defects (inspection_id, type, confidence, status)
                    select id, 'CRACK', 0.9, 'ACTION_PENDING'
                    from inspections
                    where facility_id = (select id from facilities where name = '백필빌딩');
                    """);
        } catch (SQLException e) {
            throw new IllegalStateException("ACTION_PENDING 시드 실패 — 캐노니컬 DDL 스키마와 어긋났는지 확인할 것", e);
        }
    }

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.dialect",
                () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.baseline-on-migrate", () -> "true");
        registry.add("spring.flyway.baseline-version", () -> "1");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 기존_ACTION_PENDING_하자는_V22적용후_CONFIRMED로_이관된다() {
        // 컨텍스트가 떴다는 것 자체가 V22 이 실제 ACTION_PENDING 행이 있는 DB 에서 성공했다는 뜻이다
        // (UPDATE 를 빼먹으면 enum 축소 단계에서 "invalid input value for enum" 으로 기동이 막힌다).
        Integer v21Applied = jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where version = '21' and success = true",
                Integer.class);
        assertThat(v21Applied).isEqualTo(1);

        // 시드했던 그 행이 유실되지 않고 CONFIRMED 로 살아 있어야 한다.
        List<String> statuses = jdbcTemplate.queryForList(
                "select status::text from defects order by id", String.class);
        assertThat(statuses).containsExactly("CONFIRMED");

        // enum 도 4단계로 좁혀져 있어야 한다.
        List<String> labels = jdbcTemplate.queryForList("""
                select e.enumlabel from pg_enum e
                join pg_type t on t.oid = e.enumtypid
                where t.typname = 'defect_status_type'
                order by e.enumsortorder
                """, String.class);
        assertThat(labels).containsExactly("DETECTED", "CONFIRMED", "IN_PROGRESS", "RESOLVED");
    }
}
