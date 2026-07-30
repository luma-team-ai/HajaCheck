package com.hajacheck.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.membership.repository.PlanRepository;
import java.util.List;
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
 * 마이그레이션이 재사용한다)→V20(payments 결제 원장, #988/HAJA-489)→V21(inspection_notification_settings.
 * warn_on_overdue_enabled 기본값 false→true, HAJA-498 — 유병현 님 승인)→V22(defect_status_type 에서
 * ACTION_PENDING 제거 — 하자 상태 4단계화, #1072/#1079/#1083)→V23(counsel_ticket_notes 상담원 비공개
 * 메모, #1021/HAJA-503/#1091)→V24(defects.location + defects.previous_defect_id, #970 갭3/HAJA-437)→
 * V25(notifications.uq_notifications_inspection_due_dedupe 부분 유니크 인덱스, #1050 — INSPECTION_DUE
 * 알림 멱등성을 애플리케이션 메모리 조회에서 DB 유니크 제약 기반으로 전환)→V26(media.original_filename,
 * #1116 — AI 분석 실행/상태 화면 "이미지 N" 순번 표시 문제 수정)→V27(user_plans.current_period_start/
 * current_period_end 결제 주기 실체화, #1104/HAJA-525)→V28(notification_type PLAN_EXPIRED 라벨,
 * #1145/HAJA-549 — 구독 결제 주기 만료 FREE 자동 강등 알림)→V29(reports.deleted_at DRAFT soft delete
 * 시각, #1172)→V30(scheduled_plan_changes 플랜 하향 예약 원장, #1105/HAJA-526)→V31(notification_type
 * 예약 하향 알림 라벨 2종, #1105/HAJA-526)→V32(defect_action_logs 조치 등록 이력 append-only 테이블,
 * #1193/HAJA-569 — 조치중 단계 다중 등록 지원)→V33(user_plans.payment_pending_until 미결제 유예 표식 +
 * 부분 인덱스, #1177 — 유료→유료 하향 C안 "유예 후 강등")→V34(counsel_tickets.created_at 인덱스, #1168 —
 * 플랫폼 관리자 상담 관리 페이지 날짜별 조회 성능, PR머신 리뷰 P2)을 순서대로 적용하고,
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
    void 빈DB에서_V1부터_V32까지_적용되고_hibernateValidate와_PlanSeedGuard를_통과한다() {
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
        //   없어 번호를 소모하지 않는다) — 팀 합의로 이 번호를 재사용한다.
        // + V20(payments 결제 원장 + payment_status/method enum, #988/HAJA-489). V19 를 #632 가 선점해
        //   결제 원장은 V20 으로 조율됐고, 두 PR 이 모두 들어온 지금 번호는 V1~V20 으로 연속이다
        //   (결번 여부 자체는 FlywayMigrationVersionSequenceTest 가 별도로 고정한다).
        // + V21(inspection_notification_settings.warn_on_overdue_enabled DEFAULT false→true + 방어적
        //   백필, #540/HAJA-498 — 유병현 님 승인 옵션1: 연체 알림 미수신 회귀 방지 하위호환 확보).
        // + V22(defect_status_type 에서 ACTION_PENDING 제거 — 하자 상태 4단계화, #1072/#1079/#1083).
        // + V23(counsel_ticket_notes 상담원 비공개 메모, #1021/HAJA-503/#1091).
        // + V24(defects.location + defects.previous_defect_id, #970 갭3/HAJA-437).
        // + V25(notifications.uq_notifications_inspection_due_dedupe 부분 유니크 인덱스, #1050 —
        //   INSPECTION_DUE 알림 멱등성을 애플리케이션 메모리 조회에서 DB 유니크 제약 기반으로 전환).
        // + V26(media.original_filename — AI 분석 실행/상태 화면 "이미지 N" 순번 표시 문제 수정, #1116).
        // + V27(user_plans.current_period_start/current_period_end 결제 주기 실체화, #1104/HAJA-525).
        //   번호 배분(2026-07-28 팀 확정): V25=#1050 · V26=#1116 · V27=#1104 — 착수 시점엔 이 작업이
        //   V25였으나 앞의 두 건이 먼저 dev에 확정돼 재번호했다.
        // + V28(notification_type PLAN_EXPIRED 라벨, #1145/HAJA-549 — 구독 결제 주기 만료 FREE 자동
        //   강등 배치가 강등 시점에 발행하는 알림 유형).
        // + V29(reports.deleted_at DRAFT soft delete 시각, #1172).
        // + V30(scheduled_plan_changes 플랜 하향 예약 원장 + scheduled_plan_change_status_type enum,
        //   #1105/HAJA-526 — 하향을 즉시가 아니라 다음 결제 주기에 적용).
        // + V31(notification_type PLAN_DOWNGRADED·PLAN_DOWNGRADE_FAILED 라벨, #1105/HAJA-526 — 예약
        //   하향이 적용/실패로 종료된 시점에 신청자(회사 owner)에게 나가는 알림 유형. 만료 강등
        //   (PLAN_EXPIRED)과 사용자에게 전혀 다른 사건이라 라벨을 나눈다).
        //   ⚠️ #1105는 착수 시 V29로 잡았다가 #1172가 그 번호를 선점해 V30·V31로 재번호했고,
        //   #1172가 dev에 머지되면서(2026-07-29) 결번 [29]가 해소돼 번호열이 다시 연속이 됐다.
        // + V32(defect_action_logs 조치 등록 이력 append-only 테이블 + 전용 defect_action_log_phase_type
        //   enum, #1193/HAJA-569 — 조치중(IN_PROGRESS) 단계 다중 등록 지원). 착수 시 V29로 잡았다가
        //   #1172/#1105가 먼저 dev에 들어와 V32로 재번호했다.
        // + V33(user_plans.payment_pending_until 미결제 유예 표식 + idx_user_plans_payment_pending
        //   부분 인덱스, #1177 — 유료→유료 하향 C안 "유예 후 강등"의 상태 표식).
        //   착수 시점에는 #1193이 V32를 선점한 상태라 이 작업이 V33을 쓰면서 결번 [32]가 생겼지만,
        //   #1193이 dev에 머지되면서(2026-07-29) 해소돼 번호열이 V1…V32·V33으로 다시 연속이 됐다.
        // + V34(counsel_tickets.created_at 인덱스, #1168 — 플랫폼 관리자 상담 관리 페이지 날짜별 조회
        //   성능, PR머신 리뷰 P2 지적 반영).
        //   마이그레이션 수는 V1~V24(24개) + V25~V31(7개) + V32~V34(3개) = 34이다.
        assertThat(appliedMigrations).isEqualTo(34);

        // 최신 적용 버전이 실제로 V34 인지 확인.
        String latestVersion = jdbcTemplate.queryForObject(
                "select version from flyway_schema_history where success = true "
                        + "order by installed_rank desc limit 1", String.class);
        assertThat(latestVersion).isEqualTo("34");

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

        // V26이 media.original_filename 컬럼을 실제로 추가했는지 확인(#1116 — AI 분석 실행/상태 화면
        // "이미지 N" 순번 표시 문제 수정).
        Long originalFilenameColumnExists = jdbcTemplate.queryForObject("""
                select count(*) from information_schema.columns
                where table_schema = 'public' and table_name = 'media' and column_name = 'original_filename'
                """, Long.class);
        assertThat(originalFilenameColumnExists).isEqualTo(1L);

        // V20이 payments 테이블(#988/HAJA-489)을 실제로 만들었는지 확인한다.
        Long paymentsTableExists = jdbcTemplate.queryForObject("""
                select count(*) from information_schema.tables
                where table_schema = 'public' and table_name = 'payments'
                """, Long.class);
        assertThat(paymentsTableExists).isEqualTo(1L);

        // 중복 승인 최종 방어선인 부분 유니크 인덱스(payment_key is not null)도 함께 고정한다.
        Long paymentKeyPartialUniqueExists = jdbcTemplate.queryForObject("""
                select count(*) from pg_indexes
                where schemaname = 'public' and tablename = 'payments'
                  and indexname = 'uq_payments_payment_key'
                  and indexdef like '%WHERE (payment_key IS NOT NULL)%'
                """, Long.class);
        assertThat(paymentKeyPartialUniqueExists).isEqualTo(1L);

        // V21이 inspection_notification_settings.warn_on_overdue_enabled 컬럼 DEFAULT를
        // true로 바꿨는지 확인(#540/HAJA-498 — 유병현 님 승인, 연체 알림 미수신 회귀 방지).
        String warnOnOverdueEnabledDefault = jdbcTemplate.queryForObject("""
                select column_default from information_schema.columns
                where table_schema = 'public' and table_name = 'inspection_notification_settings'
                  and column_name = 'warn_on_overdue_enabled'
                """, String.class);
        assertThat(warnOnOverdueEnabledDefault).contains("true");

        // V25가 INSPECTION_DUE 알림 멱등성용 부분 유니크 인덱스를 실제로 만들었는지 확인(#1050).
        Long dedupeIndexExists = jdbcTemplate.queryForObject("""
                select count(*) from pg_indexes
                where schemaname = 'public' and tablename = 'notifications'
                  and indexname = 'uq_notifications_inspection_due_dedupe'
                """, Long.class);
        assertThat(dedupeIndexExists).isEqualTo(1L);

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

        // V22가 defect_status_type 을 4단계로 좁혔는지 확인 — 타입 이름은 그대로 유지되고
        // ACTION_PENDING 만 사라져야 한다(구 타입 drop + rename 이 제대로 끝났다는 뜻).
        List<String> defectStatusLabels = jdbcTemplate.queryForList("""
                select e.enumlabel from pg_enum e
                join pg_type t on t.oid = e.enumtypid
                where t.typname = 'defect_status_type'
                order by e.enumsortorder
                """, String.class);
        assertThat(defectStatusLabels)
                .containsExactly("DETECTED", "CONFIRMED", "IN_PROGRESS", "RESOLVED");

        // 타입 교체 과정에서 drop 했던 컬럼 default 가 다시 붙어 있어야 한다(신규 하자 기본 상태).
        String defectStatusDefault = jdbcTemplate.queryForObject("""
                select column_default from information_schema.columns
                where table_schema = 'public' and table_name = 'defects' and column_name = 'status'
                """, String.class);
        assertThat(defectStatusDefault).contains("DETECTED");

        // V23가 counsel_ticket_notes 테이블(ticket_id unique, #1021/HAJA-503)을 실제로 추가했는지 확인한다.
        Long counselTicketNotesTableExists = jdbcTemplate.queryForObject("""
                select count(*) from information_schema.tables
                where table_schema = 'public' and table_name = 'counsel_ticket_notes'
                """, Long.class);
        assertThat(counselTicketNotesTableExists).isEqualTo(1L);
        Long counselTicketNotesUniqueIndexExists = jdbcTemplate.queryForObject("""
                select count(*) from pg_indexes
                where schemaname = 'public' and tablename = 'counsel_ticket_notes'
                  and indexname = 'uq_counsel_ticket_notes_ticket_id'
                """, Long.class);
        assertThat(counselTicketNotesUniqueIndexExists).isEqualTo(1L);

        // V24가 defects.location + defects.previous_defect_id 컬럼(#970 갭3/HAJA-437)을
        // 실제로 추가했는지 확인한다.
        Long defectDetailColumnCount = jdbcTemplate.queryForObject("""
                select count(*) from information_schema.columns
                where table_schema = 'public' and table_name = 'defects'
                  and column_name in ('location', 'previous_defect_id')
                """, Long.class);
        assertThat(defectDetailColumnCount).isEqualTo(2L);

        // previous_defect_id가 defects.id를 가리키는 self-referencing FK인지 확인한다.
        Long previousDefectIdFkExists = jdbcTemplate.queryForObject("""
                select count(*) from information_schema.table_constraints tc
                join information_schema.constraint_column_usage ccu
                  on tc.constraint_name = ccu.constraint_name and tc.table_schema = ccu.table_schema
                where tc.table_schema = 'public' and tc.table_name = 'defects'
                  and tc.constraint_type = 'FOREIGN KEY'
                  and ccu.table_name = 'defects' and ccu.column_name = 'id'
                """, Long.class);
        assertThat(previousDefectIdFkExists).isGreaterThanOrEqualTo(1L);

        // V27이 user_plans.current_period_start/current_period_end 컬럼(#1104/HAJA-525)을
        // 실제로 추가했는지 확인한다.
        Long billingPeriodColumnCount = jdbcTemplate.queryForObject("""
                select count(*) from information_schema.columns
                where table_schema = 'public' and table_name = 'user_plans'
                  and column_name in ('current_period_start', 'current_period_end')
                """, Long.class);
        assertThat(billingPeriodColumnCount).isEqualTo(2L);

        // V28이 notification_type PG enum에 PLAN_EXPIRED 라벨을 실제로 추가했는지 확인한다
        // (#1145/HAJA-549 — 라벨이 없으면 만료 강등 알림 INSERT가 런타임에 실패한다, V4와 같은 형태).
        Long planExpiredLabelExists = jdbcTemplate.queryForObject("""
                select count(*) from pg_enum e
                join pg_type t on e.enumtypid = t.oid
                where t.typname = 'notification_type' and e.enumlabel = 'PLAN_EXPIRED'
                """, Long.class);
        assertThat(planExpiredLabelExists).isEqualTo(1L);

        // V29가 reports.deleted_at 컬럼을 실제로 추가했는지 확인한다(#1172).
        Long reportsDeletedAtColumnExists = jdbcTemplate.queryForObject("""
                select count(*) from information_schema.columns
                where table_schema = 'public' and table_name = 'reports' and column_name = 'deleted_at'
                """, Long.class);
        assertThat(reportsDeletedAtColumnExists).isEqualTo(1L);

        // V30이 scheduled_plan_changes 테이블(#1105/HAJA-526)을 실제로 만들었는지 확인한다.
        Long scheduledPlanChangesTableExists = jdbcTemplate.queryForObject("""
                select count(*) from information_schema.tables
                where table_schema = 'public' and table_name = 'scheduled_plan_changes'
                """, Long.class);
        assertThat(scheduledPlanChangesTableExists).isEqualTo(1L);

        // 중복 예약 최종 방어선인 부분 유니크 인덱스(status = PENDING)도 함께 고정한다 — 이 인덱스가
        // 없으면 동시 예약 두 건이 같은 주기에 하향을 두 번 실행해 좌석 정지 결과가 예측 불가능해진다.
        Long pendingPartialUniqueExists = jdbcTemplate.queryForObject("""
                select count(*) from pg_indexes
                where schemaname = 'public' and tablename = 'scheduled_plan_changes'
                  and indexname = 'uq_scheduled_plan_changes_pending'
                  and indexdef like '%PENDING%'
                """, Long.class);
        assertThat(pendingPartialUniqueExists).isEqualTo(1L);

        // keep_user_ids 가 실제로 bigint[] 인지 고정한다 — 엔티티가 Long[] + SqlTypes.ARRAY 로 매핑하므로
        // 타입이 어긋나면 ddl-auto=validate 가 기동을 거부한다(컨텍스트 기동 자체가 그 검증이지만,
        // 회귀 시 원인이 바로 보이도록 컬럼 타입을 명시적으로 단정한다).
        String keepUserIdsUdtName = jdbcTemplate.queryForObject("""
                select udt_name from information_schema.columns
                where table_schema = 'public' and table_name = 'scheduled_plan_changes'
                  and column_name = 'keep_user_ids'
                """, String.class);
        assertThat(keepUserIdsUdtName).isEqualTo("_int8");

        // 확인받은 정지 규모 스냅샷 컬럼(#1105 리뷰 P2-4) — 이 컬럼이 없으면 실행 시점 정지 인원이
        // 신청 시점 동의 범위를 넘었는지 판정할 근거가 사라진다(NOT NULL + 기본값 0이어야 한다).
        String confirmedSeatOverflowNullable = jdbcTemplate.queryForObject("""
                select is_nullable from information_schema.columns
                where table_schema = 'public' and table_name = 'scheduled_plan_changes'
                  and column_name = 'confirmed_seat_overflow'
                """, String.class);
        assertThat(confirmedSeatOverflowNullable).isEqualTo("NO");

        // V31이 notification_type PG enum에 예약 하향 알림 라벨 2종을 실제로 추가했는지 확인한다
        // (#1105/HAJA-526 — 라벨이 없으면 적용/실패 알림 INSERT가 런타임에 실패한다).
        Long scheduledDowngradeLabels = jdbcTemplate.queryForObject("""
                select count(*) from pg_enum e
                join pg_type t on e.enumtypid = t.oid
                where t.typname = 'notification_type'
                  and e.enumlabel in ('PLAN_DOWNGRADED', 'PLAN_DOWNGRADE_FAILED')
                """, Long.class);
        assertThat(scheduledDowngradeLabels).isEqualTo(2L);

        // V33이 user_plans.payment_pending_until 컬럼과 전용 부분 인덱스를 실제로 만들었는지 확인한다
        // (#1177 — 컬럼이 없으면 미결제 유예 판정 자체가 성립하지 않고 ddl-auto=validate 가 기동을 막는다).
        Long paymentPendingColumn = jdbcTemplate.queryForObject("""
                select count(*) from information_schema.columns
                where table_schema = 'public' and table_name = 'user_plans'
                  and column_name = 'payment_pending_until'
                """, Long.class);
        assertThat(paymentPendingColumn).isEqualTo(1L);

        Long paymentPendingIndex = jdbcTemplate.queryForObject("""
                select count(*) from pg_indexes
                where schemaname = 'public' and tablename = 'user_plans'
                  and indexname = 'idx_user_plans_payment_pending'
                """, Long.class);
        assertThat(paymentPendingIndex).isEqualTo(1L);

        // V34가 counsel_tickets.created_at 인덱스를 실제로 만들었는지 확인한다(#1168, PR머신 리뷰 P2 —
        // 상담 관리 페이지 날짜별 조회가 인덱스 없이 created_at으로 필터링/정렬돼 풀스캔 위험이 있었다).
        Long counselTicketsCreatedAtIndex = jdbcTemplate.queryForObject("""
                select count(*) from pg_indexes
                where schemaname = 'public' and tablename = 'counsel_tickets'
                  and indexname = 'idx_counsel_tickets_created_at'
                """, Long.class);
        assertThat(counselTicketsCreatedAtIndex).isEqualTo(1L);
    }
}
