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
 * Flyway baseline-on-migrate 의 "기존 DB" 경로(#359, #544 P1)를 검증한다.
 *
 * <p>{@link FlywayBaselineIntegrationTest}(빈 컨테이너에서 V1→V2→V3 전체 실행)와 짝을 이룬다. 이 기능의
 * 실제 목적(#531 arm1 승격 스키마 드리프트 재발 방지)에서 운영·로컬이 실제로 밟는 경로는 "이미 전체 스키마가
 * 있는 기존 DB에 baseline 스탬프만 찍고 V2·V3 만 적용"되는 쪽인데, 짝 테스트는 신규 DB 경로만 덮는다.
 *
 * <p>여기서는 캐노니컬 DDL({@code db/HajaCheck_script.sql}, api_system_logs·plans 시드 포함 — arm1·팀원
 * 로컬처럼 이미 모든 스키마가 존재하는 DB를 모사)을 initScript로 미리 적재한 컨테이너에
 * {@code baseline-on-migrate=true, baseline-version=1} 로 Flyway를 돌린다. 특히 api_system_logs 가 이미
 * 존재하는 상태에서 V3 가 처음 적용되므로, V3의 create table/index 가 {@code if not exists} 가드 없이는
 * 'relation already exists' 로 실패한다 — 이 테스트는 #544 P1 회귀(무가드 create table 재도입)를 CI에서
 * 고정한다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class FlywayBaselineOnExistingDbIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17")
            .withDatabaseName("hajacheck_flyway_existing")
            .withUsername("postgres")
            // 기존 DB 모사: 캐노니컬 전체 스키마(api_system_logs·plans 포함)를 Flyway 실행 전에 미리 만든다.
            .withInitScript("db/HajaCheck_script.sql");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        // 운영·로컬과 동일하게 Flyway가 마이그레이션을 관리하고 Hibernate 는 검증만 한다.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.dialect",
                () -> "org.hibernate.dialect.PostgreSQLDialect");
        // application-test.yml 기본값(false)을 이 테스트에서만 되살려 실제 운영(application.yml)과 동일하게 맞춘다.
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.baseline-on-migrate", () -> "true");
        registry.add("spring.flyway.baseline-version", () -> "1");
        // Flyway가 plans 3티어를 (기존 DB에서도) 보장하는지 부팅 가드로 함께 확인한다.
        registry.add("hajacheck.membership.seed-guard.enabled", () -> "true");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlanRepository planRepository;

    @Test
    void 기존DB에_baselineOnMigrate로_V2와_V3를_적용해도_실패하지않고_validate와_PlanSeedGuard를_통과한다() {
        // 컨텍스트가 이미 기동했다는 사실 자체가 (1) Flyway 마이그레이션이 예외 없이 끝났고,
        // (2) Hibernate validate(전체 엔티티 매핑 대조)와 (3) PlanSeedGuard(plans 3티어) 를 통과했음을 의미한다.

        // V1 은 실제 실행이 아니라 baseline 스탬프로만 기록되어야 한다(기존 DB에 스키마가 이미 있으므로).
        String v1Type = jdbcTemplate.queryForObject(
                "select type from flyway_schema_history where version = '1'", String.class);
        assertThat(v1Type).isEqualTo("BASELINE");

        // V2(seed_plans)·V3(api_system_logs)·V4(add_platform_admin_role)·V5(add_business_start_date)
        // ·V6(grant_admin_to_company_owners)만 실제 versioned 마이그레이션으로 성공 적용된다. 캐노니컬
        // DDL(HajaCheck_script.sql)은 이미 role_type에 PLATFORM_ADMIN·companies.business_start_date를
        // 포함하므로 V4/V5는 IF NOT EXISTS로 no-op 성공한다 — 기존 DB(캐노니컬 DDL을 아직 못 받은 실제
        // arm1/팀원 로컬)에서는 이 V4/V5가 실제로 라벨·컬럼을 추가하는 경로다. V6은 데이터 UPDATE 라
        // 대상 owner 가 없어도(빈 companies) 0행 갱신으로 성공한다(#636).
        Integer appliedVersioned = jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where success = true and version in ('2', '3', '4', '5', '6')",
                Integer.class);
        assertThat(appliedVersioned).isEqualTo(5);

        // 실패 기록이 남지 않아야 한다(V3가 if not exists로 skip되어 'relation already exists'가 나지 않음).
        Integer failed = jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where success = false", Integer.class);
        assertThat(failed).isEqualTo(0);

        // 기존 DB에 있던 api_system_logs 는 그대로 유지된다(V3 재실행이 깨거나 중복 생성하지 않음).
        Long apiLogsTables = jdbcTemplate.queryForObject("""
                select count(*) from information_schema.tables
                where table_schema = 'public' and table_name = 'api_system_logs'
                """, Long.class);
        assertThat(apiLogsTables).isEqualTo(1L);

        // plans 3티어가 유지된다(V2 seed 는 ON CONFLICT DO NOTHING 이라 기존 시드를 훼손하지 않는다).
        assertThat(planRepository.findByName(PlanName.FREE)).isPresent();
        assertThat(planRepository.findByName(PlanName.STANDARD)).isPresent();
        assertThat(planRepository.findByName(PlanName.ENTERPRISE)).isPresent();

        Long planCount = jdbcTemplate.queryForObject("select count(*) from plans", Long.class);
        assertThat(planCount).isEqualTo(3L);

        // role_type에 PLATFORM_ADMIN 라벨이 존재한다(#534 P1 회귀 고정).
        Long platformAdminLabelExists = jdbcTemplate.queryForObject("""
                select count(*) from pg_enum e
                join pg_type t on e.enumtypid = t.oid
                where t.typname = 'role_type' and e.enumlabel = 'PLATFORM_ADMIN'
                """, Long.class);
        assertThat(platformAdminLabelExists).isEqualTo(1L);
    }
}
