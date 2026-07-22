package com.hajacheck.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.repository.PlanRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Flyway가 "완전히 빈" PostgreSQL(#359)에서 V1(baseline_schema)→V2(seed_plans)→
 * V3(create_api_system_logs)를 순서대로 적용하고, Hibernate ddl-auto=validate + PlanSeedGuard
 * 부팅 가드가 통과하는지 검증한다.
 *
 * <p>다른 {@code @SpringBootTest} 는 전부 {@link PostgresTestSupport}(withInitScript로 스키마를 미리
 * 만들고 Flyway는 application-test.yml에서 꺼둠)를 쓴다. 이 클래스만 예외적으로 initScript 없는 컨테이너 +
 * {@code spring.flyway.enabled=true} 로 재정의해 "신규 배포/신규 로컬 DB" 경로(빈 DB에서 Flyway가 스키마
 * 전체를 만드는 경로)를 별도로 검증한다 — arm1 승격 #531 스키마 드리프트 재발 방지가 이 기능의 목적이므로,
 * "Flyway가 실제로 빈 DB에 스키마를 만들고 앱이 뜨는지"가 이 PR의 핵심 완료 기준이다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class FlywayBaselineIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17")
            .withDatabaseName("hajacheck_flyway_baseline")
            .withUsername("postgres");
    // withInitScript 를 일부러 붙이지 않는다 — Flyway가 스키마를 처음부터(V1) 만드는 경로를 검증해야 한다.

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        // 운영·로컬과 동일하게 Flyway가 스키마를 만들고 Hibernate 는 검증만 한다.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.dialect",
                () -> "org.hibernate.dialect.PostgreSQLDialect");
        // application-test.yml 기본값(false)을 이 테스트에서만 되살린다 — 빈 컨테이너이므로
        // baseline-on-migrate 여부와 무관하게 V1부터 실제로 실행된다(기존 DB가 아니라서 스탬프 대상이 없음).
        registry.add("spring.flyway.enabled", () -> "true");
        // application-test.yml 기본값(false)을 재정의 — Flyway가 실제로 plans 3티어를 채웠는지
        // 부팅 가드(PlanSeedGuard)로도 함께 확인한다(컨텍스트 기동 자체가 가드를 통과해야 한다).
        registry.add("hajacheck.membership.seed-guard.enabled", () -> "true");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlanRepository planRepository;

    @Test
    void 빈DB에서_V1baseline_V2plans시드_V3apiSystemLogs_V4defectsMedia가_적용되고_hibernateValidate와_PlanSeedGuard를_통과한다() {
        // 컨텍스트가 이미 기동했다는 사실 자체가 Hibernate validate(전체 엔티티 매핑 대조)와
        // PlanSeedGuard(plans 3티어 존재 검증) 둘 다 통과했음을 의미한다.

        Integer appliedMigrations = jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where success = true", Integer.class);
        // V1(baseline_schema) + V2(seed_plans) + V3(api_system_logs) + V4(defects.media_id, #527/HAJA-314)
        assertThat(appliedMigrations).isEqualTo(4);

        assertThat(planRepository.findByName(PlanName.FREE)).isPresent();
        assertThat(planRepository.findByName(PlanName.STANDARD)).isPresent();
        assertThat(planRepository.findByName(PlanName.ENTERPRISE)).isPresent();

        Long planCount = jdbcTemplate.queryForObject("select count(*) from plans", Long.class);
        assertThat(planCount).isEqualTo(3L);

        // V3가 실제로 api_system_logs 테이블(#528 canonical DDL, 아직 JPA 엔티티는 없음)을 만들었는지 확인.
        Long tableExists = jdbcTemplate.queryForObject("""
                select count(*) from information_schema.tables
                where table_schema = 'public' and table_name = 'api_system_logs'
                """, Long.class);
        assertThat(tableExists).isEqualTo(1L);

        // V4가 실제로 defects.media_id 컬럼(#527/HAJA-314)을 만들었는지 확인.
        Long columnExists = jdbcTemplate.queryForObject("""
                select count(*) from information_schema.columns
                where table_schema = 'public' and table_name = 'defects' and column_name = 'media_id'
                """, Long.class);
        assertThat(columnExists).isEqualTo(1L);
    }
}
