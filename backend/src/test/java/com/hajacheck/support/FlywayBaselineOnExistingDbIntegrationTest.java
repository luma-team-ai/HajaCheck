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
 * <p>{@link FlywayBaselineIntegrationTest}(빈 컨테이너에서 V1→…→V11 전체 실행)와 짝을 이룬다. 이 기능의
 * 실제 목적(#531 arm1 승격 스키마 드리프트 재발 방지)에서 운영·로컬이 실제로 밟는 경로는 "이미 전체 스키마가
 * 있는 기존 DB에 baseline 스탬프만 찍고 V2~V11만 적용"되는 쪽인데, 짝 테스트는 신규 DB 경로만 덮는다.
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
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
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
    void 기존DB에_baselineOnMigrate로_V2부터_V12까지_적용해도_실패하지않고_validate와_PlanSeedGuard를_통과한다() {
        // 컨텍스트가 이미 기동했다는 사실 자체가 (1) Flyway 마이그레이션이 예외 없이 끝났고,
        // (2) Hibernate validate(전체 엔티티 매핑 대조)와 (3) PlanSeedGuard(plans 3티어) 를 통과했음을 의미한다.

        // V1 은 실제 실행이 아니라 baseline 스탬프로만 기록되어야 한다(기존 DB에 스키마가 이미 있으므로).
        String v1Type = jdbcTemplate.queryForObject(
                "select type from flyway_schema_history where version = '1'", String.class);
        assertThat(v1Type).isEqualTo("BASELINE");

        // V2(seed_plans)·V3(api_system_logs)·V4(add_platform_admin_role)·V5(add_business_start_date, #596)·
        // V6(defects.media_id, #527/HAJA-314)·V7(inspection_admin_schema, #568)·
        // V8(grant_admin_to_company_owners, #636)·V9(facilities.next_inspection_due_at 인덱스, #509)·
        // V10(add_facility_registration_fields, #628/HAJA-347)·V11(facilities company scope, #637)·
        // V12(defects 조치 결과 등록 필드, #725/HAJA-393)이 실제 versioned 마이그레이션으로 성공 적용된다.
        // 캐노니컬 DDL(HajaCheck_script.sql)은 이미 role_type에 PLATFORM_ADMIN·companies.business_start_date·
        // defects.media_id·점검 관리자 스키마·next_inspection_due_at 인덱스·시설물 등록 필드 확장·
        // 회사 시설(company_id) 전환·defects 조치 결과 등록 필드를 모두 포함하므로 V4~V12는
        // IF NOT EXISTS(또는 대상 없는 UPDATE)로 no-op 성공한다 — 기존 DB(캐노니컬 DDL을 아직 못 받은 실제
        // arm1/팀원 로컬)에서는 이 V4~V12가 실제로 라벨·컬럼·테이블·인덱스를 추가/전환하는 경로다.
        // V8은 데이터 UPDATE 라 대상 owner 가 없어도(캐노니컬 DDL은 스키마만 적재, companies 빈 상태) 0행
        // 갱신으로 성공한다(#636).
        Integer appliedVersioned = jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where success = true "
                        + "and version in ('2', '3', '4', '5', '6', '7', '8', '9', '10', '11', '12')",
                Integer.class);
        assertThat(appliedVersioned).isEqualTo(11);

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

        // 기존 DB에 있던 defects.media_id 도 그대로 유지된다(V6 재실행이 깨거나 중복 생성하지 않음, #527/HAJA-314).
        Long mediaIdColumns = jdbcTemplate.queryForObject("""
                select count(*) from information_schema.columns
                where table_schema = 'public' and table_name = 'defects' and column_name = 'media_id'
                """, Long.class);
        assertThat(mediaIdColumns).isEqualTo(1L);

        // plans 3티어가 유지된다(V2 seed 는 ON CONFLICT DO NOTHING 이라 기존 시드를 훼손하지 않는다).
        assertThat(planRepository.findByName(PlanName.FREE)).isPresent();
        assertThat(planRepository.findByName(PlanName.STANDARD)).isPresent();
        assertThat(planRepository.findByName(PlanName.ENTERPRISE)).isPresent();
        assertThat(planRepository.findByName(PlanName.ENTERPRISE).orElseThrow().getMaxSeats()).isNull();

        Long planCount = jdbcTemplate.queryForObject("select count(*) from plans", Long.class);
        assertThat(planCount).isEqualTo(3L);

        // role_type에 PLATFORM_ADMIN 라벨이 존재한다(#534 P1 회귀 고정).
        Long platformAdminLabelExists = jdbcTemplate.queryForObject("""
                select count(*) from pg_enum e
                join pg_type t on e.enumtypid = t.oid
                where t.typname = 'role_type' and e.enumlabel = 'PLATFORM_ADMIN'
                """, Long.class);
        assertThat(platformAdminLabelExists).isEqualTo(1L);

        Long settingsTableExists = jdbcTemplate.queryForObject("""
                select count(*) from information_schema.tables
                where table_schema = 'public' and table_name = 'inspection_notification_settings'
                """, Long.class);
        assertThat(settingsTableExists).isEqualTo(1L);

        // 기존 DB에 있던 facilities.next_inspection_due_at 인덱스(#509)도 그대로 유지된다
        // (V9 재실행이 깨거나 중복 생성하지 않음).
        Long nextInspectionDueAtIndexExists = jdbcTemplate.queryForObject("""
                select count(*) from pg_indexes
                where schemaname = 'public' and tablename = 'facilities'
                  and indexname = 'idx_facilities_next_inspection_due_at'
                """, Long.class);
        assertThat(nextInspectionDueAtIndexExists).isEqualTo(1L);

        // 기존 DB에 있던 시설물 등록 필드(#628/HAJA-347)도 그대로 유지된다(V10 재실행이 깨거나
        // 중복 생성하지 않음).
        Long facilityColumnCount = jdbcTemplate.queryForObject("""
                select count(*) from information_schema.columns
                where table_schema = 'public' and table_name = 'facilities'
                  and column_name in ('initial_grade', 'assignee_user_id', 'memo')
                """, Long.class);
        assertThat(facilityColumnCount).isEqualTo(3L);

        // 기존 DB의 facilities도 회사 소유(company_id)로 전환되어 있다(V11, #637 — 캐노니컬 DDL이 이미
        // company_id 전환을 포함하므로 no-op 성공 경로).
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from information_schema.columns
                where table_schema = 'public' and table_name = 'facilities' and column_name = 'company_id'
                """, Long.class)).isEqualTo(1L);

        // 기존 DB에 있던 defects 조치 결과 등록 필드(V12, #725/HAJA-393)도 그대로 유지된다
        // (V12 재실행이 깨거나 중복 생성하지 않음 — 캐노니컬 DDL이 이미 포함하므로 no-op 성공 경로).
        Long actionResultColumnCount = jdbcTemplate.queryForObject("""
                select count(*) from information_schema.columns
                where table_schema = 'public' and table_name = 'defects'
                  and column_name in ('action_media_id', 'action_content', 'action_date', 'action_assignee_id')
                """, Long.class);
        assertThat(actionResultColumnCount).isEqualTo(4L);
    }
}
