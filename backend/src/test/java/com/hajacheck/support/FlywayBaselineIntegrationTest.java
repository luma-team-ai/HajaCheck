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
 * V3(create_api_system_logs)→V4(add_platform_admin_role)→V5(add_business_start_date)→
 * V6(defects.media_id)→V7(inspection_admin_schema)→V8(grant_admin_to_company_owners, #636)→
 * V9(facilities.next_inspection_due_at 인덱스, #509)→V10(add_facility_registration_fields, #628)→
 * V11(facilities company scope, #637)→V12(defects 조치 결과 등록 필드, #725/HAJA-393)→V13(media.
 * detail_url, #788/#789)→V14(counsel_type 분류, #743)→V15(user_status_type WAITING 라벨, #792)→
 * V16(defects.area_ratio, #803)→V17(seed_bot_scenarios, #20/HAJA-33)→V18(counsel 티켓 스냅샷 +
 * 채팅 첨부, #20/HAJA-33)→V19(media.facility_id + inspection XOR facility, #632/#652/HAJA-377 —
 * 옛 V19(FREE 좌석 한도, #843)는 #858에서 되돌리며 파일이 삭제돼 번호가 비어 있었고, 팀 합의로 이
 * 마이그레이션이 재사용한다. 다음은 정재봉 님 PR이 V20을 쓰기로 조율됨)를 순서대로 적용하고,
 * Hibernate ddl-auto=validate + PlanSeedGuard 부팅 가드가 통과하는지 검증한다.
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
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
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
    void 빈DB에서_V1부터_V19까지_적용되고_hibernateValidate와_PlanSeedGuard를_통과한다() {
        // 컨텍스트가 이미 기동했다는 사실 자체가 Hibernate validate(전체 엔티티 매핑 대조)와
        // PlanSeedGuard(plans 3티어 존재 검증) 둘 다 통과했음을 의미한다.

        Integer appliedMigrations = jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where success = true", Integer.class);
        // V1(baseline_schema) + V2(seed_plans) + V3(api_system_logs) + V4(add_platform_admin_role)
        // + V5(add_business_start_date, #596) + V6(defects.media_id, #527/HAJA-314) + V7(inspection_admin_schema, #568)
        // + V8(grant_admin_to_company_owners, #636) + V9(facilities.next_inspection_due_at 인덱스, #509)
        // + V10(add_facility_registration_fields, #628/HAJA-347) + V11(facilities company scope, #637)
        // + V12(defects 조치 결과 등록 필드, #725/HAJA-393) + V13(media.detail_url, #788/#789)
        // + V14(counsel_type 분류, #743) + V15(user_status_type WAITING 라벨, #792)
        // + V16(defects.area_ratio, #803) + V17(seed_bot_scenarios, #20/HAJA-33 — V13 선점으로 재번호)
        // + V18(counsel 티켓 스냅샷 + 채팅 첨부, #20/HAJA-33 — V14 선점으로 재번호)
        // + V19(media.facility_id + inspection XOR facility, #632/#652/HAJA-377). 옛 V19(FREE 좌석
        //   한도 1→2, #843)는 #858에서 되돌리며 파일 자체를 삭제했다(어느 실제 DB에도 적용된 적이
        //   없어 번호를 소모하지 않는다) — 팀 합의로 이 번호를 재사용한다. 다음은 정재봉 님 PR이 V20을
        //   쓰기로 조율됨. 실제 적용 마이그레이션은 V1~V19 = 19개다.
        assertThat(appliedMigrations).isEqualTo(19);

        // 최신 적용 버전이 실제로 V19 인지 확인.
        String latestVersion = jdbcTemplate.queryForObject(
                "select version from flyway_schema_history where success = true "
                        + "order by installed_rank desc limit 1", String.class);
        assertThat(latestVersion).isEqualTo("19");

        // V19 가 media.facility_id 컬럼을 실제로 추가했는지 확인(#632/#652).
        Long facilityIdColumnExists = jdbcTemplate.queryForObject("""
                select count(*) from information_schema.columns
                where table_schema = 'public' and table_name = 'media' and column_name = 'facility_id'
                """, Long.class);
        assertThat(facilityIdColumnExists).isEqualTo(1L);

        // V19 가 inspection XOR facility CHECK 제약을 실제로 추가했는지 확인.
        Long xorCheckExists = jdbcTemplate.queryForObject("""
                select count(*) from pg_constraint
                where conname = 'chk_media_inspection_xor_facility'
                  and conrelid = 'public.media'::regclass and contype = 'c'
                """, Long.class);
        assertThat(xorCheckExists).isEqualTo(1L);

        // V19 가 media.inspection_id 의 NOT NULL 을 제거했는지 확인(폴리모픽 소유 전제).
        Boolean inspectionIdNullable = jdbcTemplate.queryForObject("""
                select is_nullable = 'YES' from information_schema.columns
                where table_schema = 'public' and table_name = 'media' and column_name = 'inspection_id'
                """, Boolean.class);
        assertThat(inspectionIdNullable).isTrue();

        // V5가 companies.business_start_date 컬럼을 실제로 추가했는지 확인(#596).
        Long businessStartDateColumnExists = jdbcTemplate.queryForObject("""
                select count(*) from information_schema.columns
                where table_schema = 'public' and table_name = 'companies'
                  and column_name = 'business_start_date'
                """, Long.class);
        assertThat(businessStartDateColumnExists).isEqualTo(1L);

        assertThat(planRepository.findByName(PlanName.FREE)).isPresent();
        assertThat(planRepository.findByName(PlanName.STANDARD)).isPresent();
        assertThat(planRepository.findByName(PlanName.ENTERPRISE)).isPresent();
        assertThat(planRepository.findByName(PlanName.ENTERPRISE).orElseThrow().getMaxSeats()).isNull();

        // FREE 는 대표 1인 전용 티어다(#858 — #843/V19 좌석 상향을 되돌림). V1 baseline 시드값
        // 그대로 1이어야 하며, 좌석이 임의로 늘어나면 STANDARD(3석)와의 차등이 무너진다.
        assertThat(planRepository.findByName(PlanName.FREE).orElseThrow().getMaxSeats()).isEqualTo(1);
        assertThat(planRepository.findByName(PlanName.STANDARD).orElseThrow().getMaxSeats()).isEqualTo(3);

        Long planCount = jdbcTemplate.queryForObject("select count(*) from plans", Long.class);
        assertThat(planCount).isEqualTo(3L);

        // V3가 실제로 api_system_logs 테이블(#528 canonical DDL, 아직 JPA 엔티티는 없음)을 만들었는지 확인.
        Long tableExists = jdbcTemplate.queryForObject("""
                select count(*) from information_schema.tables
                where table_schema = 'public' and table_name = 'api_system_logs'
                """, Long.class);
        assertThat(tableExists).isEqualTo(1L);

        // V4가 role_type PG enum에 PLATFORM_ADMIN 라벨을 실제로 추가했는지 확인(#534 P1 회귀 고정).
        Long platformAdminLabelExists = jdbcTemplate.queryForObject("""
                select count(*) from pg_enum e
                join pg_type t on e.enumtypid = t.oid
                where t.typname = 'role_type' and e.enumlabel = 'PLATFORM_ADMIN'
                """, Long.class);
        assertThat(platformAdminLabelExists).isEqualTo(1L);

        // V6이 실제로 defects.media_id 컬럼(#527/HAJA-314)을 만들었는지 확인.
        Long columnExists = jdbcTemplate.queryForObject("""
                select count(*) from information_schema.columns
                where table_schema = 'public' and table_name = 'defects' and column_name = 'media_id'
                """, Long.class);
        assertThat(columnExists).isEqualTo(1L);

        // V7이 점검 알림 설정 테이블을 실제로 추가했는지 확인한다.
        Long settingsTableExists = jdbcTemplate.queryForObject("""
                select count(*) from information_schema.tables
                where table_schema = 'public' and table_name = 'inspection_notification_settings'
                """, Long.class);
        assertThat(settingsTableExists).isEqualTo(1L);

        // V9가 facilities.next_inspection_due_at 부분 인덱스(#509)를 실제로 추가했는지 확인한다.
        Long nextInspectionDueAtIndexExists = jdbcTemplate.queryForObject("""
                select count(*) from pg_indexes
                where schemaname = 'public' and tablename = 'facilities'
                  and indexname = 'idx_facilities_next_inspection_due_at'
                """, Long.class);
        assertThat(nextInspectionDueAtIndexExists).isEqualTo(1L);

        // V10이 시설물 등록 필드(#628/HAJA-347)를 실제로 추가했는지 확인한다.
        Long facilityColumnCount = jdbcTemplate.queryForObject("""
                select count(*) from information_schema.columns
                where table_schema = 'public' and table_name = 'facilities'
                  and column_name in ('initial_grade', 'assignee_user_id', 'memo')
                """, Long.class);
        assertThat(facilityColumnCount).isEqualTo(3L);

        // V11이 facilities를 회사 소유(company_id)로 전환했는지 확인한다(#637).
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from information_schema.columns
                where table_schema = 'public' and table_name = 'facilities' and column_name = 'company_id'
                """, Long.class)).isEqualTo(1L);

        // V12가 defects 조치 결과 등록 필드(#725/HAJA-393)를 실제로 추가했는지 확인한다.
        Long actionResultColumnCount = jdbcTemplate.queryForObject("""
                select count(*) from information_schema.columns
                where table_schema = 'public' and table_name = 'defects'
                  and column_name in ('action_media_id', 'action_content', 'action_date', 'action_assignee_id')
                """, Long.class);
        assertThat(actionResultColumnCount).isEqualTo(4L);

        // V14가 counsel_tickets.counsel_type 컬럼과 counselor_skills 테이블(#743)을 실제로 추가했는지 확인한다.
        Long counselTypeColumnExists = jdbcTemplate.queryForObject("""
                select count(*) from information_schema.columns
                where table_schema = 'public' and table_name = 'counsel_tickets' and column_name = 'counsel_type'
                """, Long.class);
        assertThat(counselTypeColumnExists).isEqualTo(1L);

        Long counselorSkillsTableExists = jdbcTemplate.queryForObject("""
                select count(*) from information_schema.tables
                where table_schema = 'public' and table_name = 'counselor_skills'
                """, Long.class);
        assertThat(counselorSkillsTableExists).isEqualTo(1L);

        // V15가 user_status_type PG enum에 WAITING 라벨을 실제로 추가했는지 확인한다(#792).
        Long waitingLabelExists = jdbcTemplate.queryForObject("""
                select count(*) from pg_enum e
                join pg_type t on e.enumtypid = t.oid
                where t.typname = 'user_status_type' and e.enumlabel = 'WAITING'
                """, Long.class);
        assertThat(waitingLabelExists).isEqualTo(1L);

        // V16이 defects.area_ratio 컬럼(#803)을 실제로 추가했는지 확인한다.
        Long areaRatioColumnExists = jdbcTemplate.queryForObject("""
                select count(*) from information_schema.columns
                where table_schema = 'public' and table_name = 'defects' and column_name = 'area_ratio'
                """, Long.class);
        assertThat(areaRatioColumnExists).isEqualTo(1L);

        // V17이 bot_scenarios 시드(최상위 4개 카테고리)를 실제로 채웠는지 확인(#20/HAJA-33, V13 선점으로 재번호).
        Long rootScenarioCount = jdbcTemplate.queryForObject(
                "select count(*) from bot_scenarios where parent_id is null", Long.class);
        assertThat(rootScenarioCount).isEqualTo(4L);
        Long counselorLinkLeafCount = jdbcTemplate.queryForObject(
                "select count(*) from bot_scenarios where leads_to_counselor = true", Long.class);
        assertThat(counselorLinkLeafCount).isEqualTo(6L);

        // V18이 counsel_tickets 스냅샷 필드 + chat_messages 첨부 컬럼을 실제로 추가했는지 확인(#20/HAJA-33, V14 선점으로 재번호).
        Long counselTicketColumnCount = jdbcTemplate.queryForObject("""
                select count(*) from information_schema.columns
                where table_schema = 'public' and table_name = 'counsel_tickets'
                  and column_name in ('ticket_number', 'category', 'title')
                """, Long.class);
        assertThat(counselTicketColumnCount).isEqualTo(3L);
        Long chatAttachmentColumnCount = jdbcTemplate.queryForObject("""
                select count(*) from information_schema.columns
                where table_schema = 'public' and table_name = 'chat_messages'
                  and column_name in ('attachment_key', 'attachment_mime_type')
                """, Long.class);
        assertThat(chatAttachmentColumnCount).isEqualTo(2L);
    }
}
