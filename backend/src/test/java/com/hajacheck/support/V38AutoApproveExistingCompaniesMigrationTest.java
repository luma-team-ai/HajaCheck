package com.hajacheck.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * V38(#1324 기존 기업 소급 자동승인 + 오너 멤버십 소급 발급)의 <b>데이터 전이 범위</b>를 격리 검증한다.
 *
 * <p>V36 까지 마이그레이션한 뒤 여러 모양의 기존 데이터를 심고 V38 만 추가 적용해, 파괴적 UPDATE/INSERT 의
 * WHERE 조건이 넓어지지 않는지(= 반려 이력·회수 멤버십·타 회사 포인터를 덮지 않는지)를 고정한다.
 * 소급 승인은 인가 불변식을 데이터로 바꾸는 작업이라 대상 범위가 곧 보안 경계다.
 *
 * <p>빈 DB(신규 설치) 경로는 {@code FlywayBaselineIntegrationTest} 가, 캐노니컬 DDL 기존 DB 경로는
 * {@code FlywayBaselineOnExistingDbIntegrationTest} 가 각각 V38 포함 전량 적용을 이미 검증한다
 * (양쪽 모두 companies 0행이라 V38 은 no-op 으로 통과해야 한다 — 그 사실 자체도 여기서 함께 고정한다).
 */
@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
class V38AutoApproveExistingCompaniesMigrationTest {

    private static final String MIGRATION_RESOURCE = "db/migration/V38__auto_approve_existing_companies.sql";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("hajacheck_v38_auto_approve")
            .withUsername("postgres");

    @BeforeEach
    void resetDatabase() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .cleanDisabled(false)
                .load()
                .clean();
    }

    @Test
    void PENDING_REVIEW회사는_APPROVED_VERIFIED로_승격되고_오너멤버십이_발급된다() {
        migrateTo("36");
        JdbcTemplate jdbc = jdbc();

        long owner = insertUser(jdbc, "v38-pending-owner@haja.test", "ADMIN", "ACTIVE");
        long company = insertCompany(jdbc, owner, "V38 승격회사", "3700000001", "PENDING_REVIEW", "PENDING");
        jdbc.update("update users set company_id = ? where id = ?", company, owner);

        // reviewed_by 에 일부러 사람 심사자를 심어 둔다 — 픽스처가 null 인 채로 두면 "마이그레이션 후
        // null" 단언이 V38 유무와 무관하게 통과하는 공허한 단언이 된다(PR 리뷰 P3).
        long staleReviewer = insertUser(jdbc, "v38-stale-reviewer@haja.test", "PLATFORM_ADMIN", "ACTIVE");
        jdbc.update("update companies set reviewed_by = ? where id = ?", staleReviewer, company);
        assertThat(jdbc.queryForObject(
                "select reviewed_by from companies where id = ?", Long.class, company)).isEqualTo(staleReviewer);

        // 전제 — 승격 전에는 스코프 3조건이 모두 비어 있다.
        assertThat(statusOf(jdbc, company)).isEqualTo("PENDING_REVIEW");
        assertThat(verificationOf(jdbc, company)).isEqualTo("PENDING");
        assertThat(membershipCount(jdbc, company, owner)).isZero();

        migrateTo("38");

        assertThat(statusOf(jdbc, company)).isEqualTo("APPROVED");
        assertThat(verificationOf(jdbc, company)).isEqualTo("VERIFIED");
        assertThat(jdbc.queryForObject(
                "select reviewed_at is not null from companies where id = ?", Boolean.class, company)).isTrue();
        // 사람 심사자 없음 = 시스템 자동승인 → 심어둔 reviewed_by 가 null 로 덮여야 한다.
        assertThat(jdbc.queryForObject(
                "select reviewed_by from companies where id = ?", Long.class, company)).isNull();
        assertThat(jdbc.queryForObject(
                "select verified_at is not null from companies where id = ?", Boolean.class, company)).isTrue();

        // 오너 멤버십 — 유효 APPROVED(approved_at 있음 · revoked_at 없음 · 만료 없음 · 초대자 없음).
        assertThat(membershipCount(jdbc, company, owner)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select status::text = 'APPROVED' and approved_at is not null
                       and revoked_at is null and expires_at is null and invited_by is null
                  from company_memberships where company_id = ? and user_id = ?
                """, Boolean.class, company, owner)).isTrue();
    }

    @Test
    void 소급승격후_스코프쿼리와_동일한3조건이_모두충족된다() {
        migrateTo("36");
        JdbcTemplate jdbc = jdbc();

        long owner = insertUser(jdbc, "v38-scope-owner@haja.test", "ADMIN", "ACTIVE");
        long company = insertCompany(jdbc, owner, "V38 스코프회사", "3700000002", "PENDING_REVIEW", "PENDING");
        jdbc.update("update users set company_id = ? where id = ?", company, owner);

        migrateTo("38");

        // existsEffectiveApprovedMembership(CompanyMembershipRepository) 와 같은 조건을 SQL 로 재현한다 —
        // 개별 컬럼 단언이 통과해도 조합이 어긋나면 스코프는 여전히 닫힌다.
        Boolean scopeOpen = jdbc.queryForObject("""
                select exists (
                    select 1
                      from company_memberships m
                      join users u on u.id = m.user_id
                      join companies c on c.id = m.company_id
                     where m.company_id = ? and m.user_id = ?
                       and m.status = 'APPROVED'::company_membership_status_type
                       and m.approved_at is not null
                       and m.revoked_at is null
                       and (m.expires_at is null or m.expires_at > now())
                       and u.status = 'ACTIVE'::user_status_type
                       and u.company_id = m.company_id
                       and c.status = 'APPROVED'::company_status_type
                       and c.verification_status = 'VERIFIED'::business_verification_status_type)
                """, Boolean.class, company, owner);

        assertThat(scopeOpen).isTrue();
    }

    @Test
    void 국세청확정불량_FAILED회사는_승격되지않고_오너멤버십도_발급되지않는다() {
        migrateTo("36");
        JdbcTemplate jdbc = jdbc();

        // (PENDING_REVIEW, FAILED) — 가입 시점에는 만들어지지 않는 조합이다. #888 재검증 배치
        // (PendingBusinessReverifyScheduler)가 국세청으로부터 미등록/불일치/휴업/폐업을 **확정**
        // 응답받았을 때 markBusinessVerificationFailed() 로 찍는다(status 는 PENDING_REVIEW 로 남는다).
        // 이미 탐지·격리된 불량 사업자를 소급 승인이 되살리면 안 된다.
        long owner = insertUser(jdbc, "v38-failed-owner@haja.test", "ADMIN", "ACTIVE");
        long company = insertCompany(jdbc, owner, "V38 폐업회사", "3800000001", "PENDING_REVIEW", "FAILED");
        jdbc.update("update users set company_id = ? where id = ?", company, owner);

        migrateTo("38");

        assertThat(statusOf(jdbc, company)).isEqualTo("PENDING_REVIEW");
        assertThat(verificationOf(jdbc, company)).isEqualTo("FAILED");
        assertThat(membershipCount(jdbc, company, owner)).isZero();
    }

    @Test
    void 이미APPROVED인데_이후FAILED확정된회사는_VERIFIED로_올라가지않는다() {
        migrateTo("36");
        JdbcTemplate jdbc = jdbc();

        // 문장(1)만 FAILED 를 제외하면 이 행이 그대로 문장(2)로 흘러든다 — 그래서 문장(2)의 조건을
        // `<> VERIFIED` 가 아니라 `= PENDING` 으로 좁혔다. 그 계약을 여기서 고정한다.
        long owner = insertUser(jdbc, "v38-approved-failed@haja.test", "ADMIN", "ACTIVE");
        long company = insertCompany(jdbc, owner, "V38 사후폐업회사", "3800000002", "APPROVED", "FAILED");
        jdbc.update("update users set company_id = ? where id = ?", company, owner);

        migrateTo("38");

        assertThat(verificationOf(jdbc, company)).isEqualTo("FAILED");
        // 스코프의 세 번째 조건도 열어주지 않는다(나중에 verification 을 손대면 즉시 열리는 지뢰 방지).
        assertThat(membershipCount(jdbc, company, owner)).isZero();
    }

    @Test
    void FAILED회사_오너의_company_id포인터는_새로_배선되지_않는다() {
        migrateTo("36");
        JdbcTemplate jdbc = jdbc();

        long owner = insertUser(jdbc, "v38-failed-ptr@haja.test", "ADMIN", "ACTIVE");
        long company = insertCompany(jdbc, owner, "V38 폐업포인터회사", "3800000003", "APPROVED", "FAILED");
        // company_id 는 null 인 상태로 둔다 — 문장(3)이 이 회사를 대상으로 삼으면 채워질 것이다.

        migrateTo("38");

        assertThat(jdbc.queryForObject(
                "select company_id from users where id = ?", Long.class, owner)).isNull();
        assertThat(membershipCount(jdbc, company, owner)).isZero();
    }

    @Test
    void 소급승격된회사는_검증없이승인됨을_ocrRaw에_남기고_기존키를_보존한다() {
        migrateTo("36");
        JdbcTemplate jdbc = jdbc();

        long owner = insertUser(jdbc, "v38-provenance@haja.test", "ADMIN", "ACTIVE");
        long company = insertCompany(
                jdbc, owner, "V38 출처회사", "3800000004", "PENDING_REVIEW", "PENDING");
        jdbc.update("update users set company_id = ? where id = ?", company, owner);
        jdbc.update("update companies set business_registration_ocr_raw = ?::jsonb where id = ?",
                "{\"source\":\"MANUAL_INPUT\"}", company);

        // ocr_raw 가 null 인 행도 실제로 있다(컬럼 nullable) — coalesce 분기도 함께 검증한다.
        long nullRawOwner = insertUser(jdbc, "v38-provenance-null@haja.test", "ADMIN", "ACTIVE");
        long nullRawCompany = insertCompany(
                jdbc, nullRawOwner, "V38 출처없는회사", "3800000005", "PENDING_REVIEW", "PENDING");
        jdbc.update("update users set company_id = ? where id = ?", nullRawCompany, nullRawOwner);
        jdbc.update("update companies set business_registration_ocr_raw = null where id = ?", nullRawCompany);

        migrateTo("38");

        // 소급분은 "국세청이 확인해 준 값이 아니다"라는 표식을 남긴다.
        assertThat(ocrRawField(jdbc, company, "ntsOutcome")).isEqualTo("UNKNOWN_BACKFILL");
        assertThat(ocrRawField(jdbc, company, "ntsBackfilledAt")).isNotBlank();
        // 기존 키는 병합으로 보존한다(|| 는 통째 교체가 아니라 키 병합).
        assertThat(ocrRawField(jdbc, company, "source")).isEqualTo("MANUAL_INPUT");
        // null 컬럼도 '{}' 로 깔고 병합돼 표식이 남는다(null || x = null 함정 회피).
        assertThat(ocrRawField(jdbc, nullRawCompany, "ntsOutcome")).isEqualTo("UNKNOWN_BACKFILL");

        // 운영 집계 쿼리가 실제로 동작하는지 — "국세청 검증을 증명할 수 없는 회사"를 한 번에 셀 수 있어야
        // 한다(LEGACY_VERIFIED 도 증명 가능 집합이므로 화이트리스트가 두 값이다).
        Integer unprovable = jdbc.queryForObject("""
                select count(*) from companies
                 where business_registration_ocr_raw->>'ntsOutcome' not in ('VERIFIED', 'LEGACY_VERIFIED')
                    or business_registration_ocr_raw->>'ntsOutcome' is null
                """, Integer.class);
        assertThat(unprovable).isEqualTo(2);
    }

    @Test
    void 이미VERIFIED인_회사의_ocrRaw는_소급표식으로_오염되지_않는다() {
        migrateTo("36");
        JdbcTemplate jdbc = jdbc();

        // 국세청이 실제로 확인해 준 회사(가입 시 VERIFIED)는 문장(2) 대상이 아니다 →
        // provenance 를 UNKNOWN_BACKFILL 로 덮어써 "진짜 검증"을 지우면 안 된다.
        long owner = insertUser(jdbc, "v38-real-verified@haja.test", "ADMIN", "ACTIVE");
        long company = insertCompany(
                jdbc, owner, "V38 진짜검증회사", "3800000006", "PENDING_REVIEW", "VERIFIED");
        jdbc.update("update users set company_id = ? where id = ?", company, owner);
        jdbc.update("update companies set business_registration_ocr_raw = ?::jsonb where id = ?",
                "{\"source\":\"MANUAL_INPUT\",\"ntsOutcome\":\"VERIFIED\"}", company);

        migrateTo("38");

        // 승인은 되지만(스코프는 열려야 한다) provenance 는 그대로다.
        assertThat(statusOf(jdbc, company)).isEqualTo("APPROVED");
        assertThat(membershipCount(jdbc, company, owner)).isEqualTo(1);
        assertThat(ocrRawField(jdbc, company, "ntsOutcome")).isEqualTo("VERIFIED");
    }

    @Test
    void 레거시_진짜검증분은_LEGACY_VERIFIED로_스탬프된다() {
        migrateTo("36");
        JdbcTemplate jdbc = jdbc();

        // #1324 이전에 국세청 검증을 통과한 회사 — ntsOutcome 키가 없다(그 시절엔 기록하지 않았다).
        // 이걸 구분해 두지 않으면 "키 부재 = 증명 불가 = false" 규칙 때문에 **진짜 검증된 기존 회사의
        // 배지가 거짓으로 꺼진다**(자동승인분에 배지를 잘못 켜는 것과 정반대 방향의 허위 표시).
        long owner = insertUser(jdbc, "v38-legacy@haja.test", "ADMIN", "ACTIVE");
        long company = insertCompany(jdbc, owner, "V38 레거시검증회사", "3900000001", "APPROVED", "VERIFIED");
        jdbc.update("update users set company_id = ? where id = ?", company, owner);
        jdbc.update("update companies set business_registration_ocr_raw = ?::jsonb where id = ?",
                "{\"source\":\"MANUAL_INPUT\"}", company);

        migrateTo("38");

        assertThat(ocrRawField(jdbc, company, "ntsOutcome")).isEqualTo("LEGACY_VERIFIED");
        assertThat(ocrRawField(jdbc, company, "ntsBackfilledAt")).isNotBlank();
        // 기존 키 보존(통째 교체가 아니라 병합).
        assertThat(ocrRawField(jdbc, company, "source")).isEqualTo("MANUAL_INPUT");
        // 상태는 원래대로(이 문장은 승인이 아니라 사실 기록이다).
        assertThat(statusOf(jdbc, company)).isEqualTo("APPROVED");
        assertThat(verificationOf(jdbc, company)).isEqualTo("VERIFIED");
    }

    @Test
    void 소급승인분은_LEGACY_VERIFIED가아니라_UNKNOWN_BACKFILL이다() {
        migrateTo("36");
        JdbcTemplate jdbc = jdbc();

        // 실행 순서 계약 — (2) 레거시 스탬프가 (3) 승격보다 먼저 돌기 때문에, 이 회사는 (2) 시점엔
        // 아직 PENDING 이라 레거시로 잡히지 않고 (3) 에서 UNKNOWN_BACKFILL 을 받는다.
        // 순서가 뒤바뀌면 "검증한 적 없는 회사"가 LEGACY_VERIFIED(=진짜 검증)로 둔갑한다.
        long owner = insertUser(jdbc, "v38-order@haja.test", "ADMIN", "ACTIVE");
        long company = insertCompany(jdbc, owner, "V38 순서회사", "3900000002", "PENDING_REVIEW", "PENDING");
        jdbc.update("update users set company_id = ? where id = ?", company, owner);

        migrateTo("38");

        assertThat(verificationOf(jdbc, company)).isEqualTo("VERIFIED");
        assertThat(ocrRawField(jdbc, company, "ntsOutcome")).isEqualTo("UNKNOWN_BACKFILL");
    }

    @Test
    void FAILED회사는_레거시스탬프도_받지않는다() {
        migrateTo("36");
        JdbcTemplate jdbc = jdbc();

        long owner = insertUser(jdbc, "v38-legacy-failed@haja.test", "ADMIN", "ACTIVE");
        long company = insertCompany(
                jdbc, owner, "V38 레거시폐업회사", "3900000003", "PENDING_REVIEW", "FAILED");

        migrateTo("38");

        // verification_status 가 VERIFIED 가 아니므로 (2) 대상이 아니다 = 검증 증명이 생기지 않는다.
        assertThat(ocrRawField(jdbc, company, "ntsOutcome")).isNull();
        assertThat(verificationOf(jdbc, company)).isEqualTo("FAILED");
    }

    @Test
    void 감사쿼리로_국세청검증을_증명할수없는회사만_집계된다() {
        migrateTo("36");
        JdbcTemplate jdbc = jdbc();

        // 레거시 진짜 검증 1건 + 소급 승인분 1건 → 집계에는 소급분만 잡혀야 한다.
        long legacyOwner = insertUser(jdbc, "v38-audit-legacy@haja.test", "ADMIN", "ACTIVE");
        long legacyCompany = insertCompany(
                jdbc, legacyOwner, "V38 감사레거시", "3900000004", "APPROVED", "VERIFIED");
        long backfillOwner = insertUser(jdbc, "v38-audit-backfill@haja.test", "ADMIN", "ACTIVE");
        long backfillCompany = insertCompany(
                jdbc, backfillOwner, "V38 감사소급", "3900000005", "PENDING_REVIEW", "PENDING");

        migrateTo("38");

        Integer unprovable = jdbc.queryForObject("""
                select count(*) from companies
                 where business_registration_ocr_raw->>'ntsOutcome' not in ('VERIFIED', 'LEGACY_VERIFIED')
                    or business_registration_ocr_raw->>'ntsOutcome' is null
                """, Integer.class);

        assertThat(unprovable).isEqualTo(1);
        assertThat(ocrRawField(jdbc, legacyCompany, "ntsOutcome")).isEqualTo("LEGACY_VERIFIED");
        assertThat(ocrRawField(jdbc, backfillCompany, "ntsOutcome")).isEqualTo("UNKNOWN_BACKFILL");
    }

    @Test
    void REJECTED회사는_소급승인대상이_아니다() {
        migrateTo("36");
        JdbcTemplate jdbc = jdbc();

        long owner = insertUser(jdbc, "v38-rejected-owner@haja.test", "ADMIN", "ACTIVE");
        long company = insertCompany(jdbc, owner, "V38 반려회사", "3700000003", "REJECTED", "PENDING");
        jdbc.update("update users set company_id = ? where id = ?", company, owner);

        migrateTo("38");

        // 명시적 반려 이력을 소급 승인이 덮으면 안 된다(가입 차단 우회).
        assertThat(statusOf(jdbc, company)).isEqualTo("REJECTED");
        assertThat(verificationOf(jdbc, company)).isEqualTo("PENDING");
        assertThat(membershipCount(jdbc, company, owner)).isZero();
    }

    @Test
    void 이미APPROVED_VERIFIED이고_멤버십이있는회사는_불변() {
        migrateTo("36");
        JdbcTemplate jdbc = jdbc();

        long reviewer = insertUser(jdbc, "v38-reviewer@haja.test", "PLATFORM_ADMIN", "ACTIVE");
        long owner = insertUser(jdbc, "v38-approved-owner@haja.test", "ADMIN", "ACTIVE");
        long company = insertCompany(jdbc, owner, "V38 기승인회사", "3700000004", "APPROVED", "VERIFIED");
        jdbc.update("""
                update companies
                   set reviewed_by = ?, reviewed_at = timestamptz '2026-01-02 03:04:05+09',
                       verified_at = timestamptz '2026-01-01 00:00:00+09'
                 where id = ?
                """, reviewer, company);
        jdbc.update("update users set company_id = ? where id = ?", company, owner);
        insertMembership(jdbc, company, owner, "APPROVED");

        migrateTo("38");

        // 기존 심사 이력(reviewed_by/reviewed_at)과 최초 검증 시각(verified_at)을 덮지 않는다.
        assertThat(jdbc.queryForObject(
                "select reviewed_by from companies where id = ?", Long.class, company)).isEqualTo(reviewer);
        assertThat(jdbc.queryForObject(
                "select reviewed_at = timestamptz '2026-01-02 03:04:05+09' from companies where id = ?",
                Boolean.class, company)).isTrue();
        assertThat(jdbc.queryForObject(
                "select verified_at = timestamptz '2026-01-01 00:00:00+09' from companies where id = ?",
                Boolean.class, company)).isTrue();
        // 중복 멤버십을 만들지 않는다.
        assertThat(membershipCount(jdbc, company, owner)).isEqualTo(1);
    }

    @Test
    void 회수된_기존멤버십은_소급승인되지않고_새행도_추가되지않는다() {
        migrateTo("36");
        JdbcTemplate jdbc = jdbc();

        long owner = insertUser(jdbc, "v38-revoked-owner@haja.test", "ADMIN", "ACTIVE");
        long company = insertCompany(jdbc, owner, "V38 회수회사", "3700000005", "PENDING_REVIEW", "PENDING");
        jdbc.update("update users set company_id = ? where id = ?", company, owner);
        insertMembership(jdbc, company, owner, "REVOKED");

        migrateTo("38");

        // 회사는 승격되지만, 회수 이력은 보존한다(소급 대상은 "행 없음" 케이스 한정).
        assertThat(statusOf(jdbc, company)).isEqualTo("APPROVED");
        assertThat(membershipCount(jdbc, company, owner)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select status::text from company_memberships where company_id = ? and user_id = ?
                """, String.class, company, owner)).isEqualTo("REVOKED");
    }

    @Test
    void 오너의_company_id가_null이면_채우고_다른회사를가리키면_덮지않는다() {
        migrateTo("36");
        JdbcTemplate jdbc = jdbc();

        // (a) company_id null 오너 → 보완 + 멤버십 발급.
        long nullPointerOwner = insertUser(jdbc, "v38-null-ptr@haja.test", "ADMIN", "ACTIVE");
        long companyA = insertCompany(
                jdbc, nullPointerOwner, "V38 포인터없는회사", "3700000006", "PENDING_REVIEW", "PENDING");

        // (b) 다른 회사를 가리키는 오너 → 포인터 유지 + 이 회사에는 멤버십을 만들지 않는다.
        long strayOwner = insertUser(jdbc, "v38-stray-ptr@haja.test", "ADMIN", "ACTIVE");
        long companyB = insertCompany(
                jdbc, strayOwner, "V38 오너이탈회사", "3700000007", "PENDING_REVIEW", "PENDING");
        long otherCompanyOwner = insertUser(jdbc, "v38-other-owner@haja.test", "ADMIN", "ACTIVE");
        long otherCompany = insertCompany(
                jdbc, otherCompanyOwner, "V38 타사", "3700000008", "APPROVED", "VERIFIED");
        jdbc.update("update users set company_id = ? where id = ?", otherCompany, strayOwner);

        migrateTo("38");

        assertThat(jdbc.queryForObject(
                "select company_id from users where id = ?", Long.class, nullPointerOwner))
                .isEqualTo(companyA);
        assertThat(membershipCount(jdbc, companyA, nullPointerOwner)).isEqualTo(1);

        // 오너가 다른 회사를 가리키면 포인터를 되돌리지 않는다(더 큰 사고 방지).
        assertThat(jdbc.queryForObject(
                "select company_id from users where id = ?", Long.class, strayOwner))
                .isEqualTo(otherCompany);
        // 그 결과 이 회사에는 오너 멤버십이 생기지 않는다(잘못된 회사에 소속을 만들지 않는다).
        assertThat(membershipCount(jdbc, companyB, strayOwner)).isZero();
        // 회사 상태 승격 자체는 진행된다.
        assertThat(statusOf(jdbc, companyB)).isEqualTo("APPROVED");
    }

    @Test
    void 오너가_다른회사에_이미APPROVED멤버십이있으면_부분UNIQUE를_밟지않고_건너뛴다() {
        migrateTo("36");
        JdbcTemplate jdbc = jdbc();

        // uq_company_memberships_approved_user(V1): user_id 당 APPROVED 멤버십 1건.
        long owner = insertUser(jdbc, "v38-dual-owner@haja.test", "ADMIN", "ACTIVE");
        long otherCompanyOwner = insertUser(jdbc, "v38-dual-other@haja.test", "ADMIN", "ACTIVE");
        long otherCompany = insertCompany(
                jdbc, otherCompanyOwner, "V38 선점회사", "3700000009", "APPROVED", "VERIFIED");
        insertMembership(jdbc, otherCompany, owner, "APPROVED");

        long company = insertCompany(jdbc, owner, "V38 중복오너회사", "3700000010", "PENDING_REVIEW", "PENDING");
        jdbc.update("update users set company_id = ? where id = ?", company, owner);

        // 이 조건 누락 시 부분 UNIQUE 위반으로 마이그레이션 자체가 실패(= 기동 실패)한다.
        migrateTo("38");

        assertThat(statusOf(jdbc, company)).isEqualTo("APPROVED");
        assertThat(membershipCount(jdbc, company, owner)).isZero();
        assertThat(membershipCount(jdbc, otherCompany, owner)).isEqualTo(1);
    }

    @Test
    void V38은_멱등하다_두번실행해도_결과가_같다() {
        migrateTo("36");
        JdbcTemplate jdbc = jdbc();

        long owner = insertUser(jdbc, "v38-idempotent@haja.test", "ADMIN", "ACTIVE");
        long company = insertCompany(jdbc, owner, "V38 멱등회사", "3700000011", "PENDING_REVIEW", "PENDING");
        jdbc.update("update users set company_id = ? where id = ?", company, owner);

        migrateTo("38");
        String firstSnapshot = snapshot(jdbc, company, owner);

        // 재실행이 "정말로 실행됐다"는 것을 증명하기 위한 대조 행 — 1차 적용 이후에 심은 미승격 회사다.
        // 재실행이 이 회사를 승격시키면 SQL 이 실제로 DB 에 닿았다는 뜻이고(공허한 통과 방지),
        // 그러면서도 위 company 의 스냅샷이 그대로면 멱등이다.
        long lateOwner = insertUser(jdbc, "v38-idempotent-late@haja.test", "ADMIN", "ACTIVE");
        long lateCompany = insertCompany(
                jdbc, lateOwner, "V38 후발회사", "3700000012", "PENDING_REVIEW", "PENDING");
        jdbc.update("update users set company_id = ? where id = ?", lateCompany, lateOwner);

        // Flyway 는 한 번만 실행하지만, prod baseline 스탬프 사고 이력(#531/#1311)이 있어 같은 SQL 을
        // 다시 돌려도 안전한지 직접 확인한다 — 재실행이 reviewed_at 을 갱신하거나 멤버십을 중복 생성하면
        // 여기서 스냅샷이 달라진다.
        jdbc.execute(migrationSql());

        // ① 이미 처리된 회사는 완전히 불변(멱등).
        assertThat(snapshot(jdbc, company, owner)).isEqualTo(firstSnapshot);
        assertThat(membershipCount(jdbc, company, owner)).isEqualTo(1);
        // ② 대조 행은 승격됐다 = 재실행이 실제로 일어났다.
        assertThat(statusOf(jdbc, lateCompany)).isEqualTo("APPROVED");
        assertThat(verificationOf(jdbc, lateCompany)).isEqualTo("VERIFIED");
        assertThat(membershipCount(jdbc, lateCompany, lateOwner)).isEqualTo(1);
    }

    @Test
    void 회사가없는DB에서는_V38이_아무것도_하지않는다() {
        // 신규 설치·CI 의 실제 모양(companies 0행) — no-op 으로 통과해야 한다.
        migrateTo("36");
        JdbcTemplate jdbc = jdbc();
        assertThat(jdbc.queryForObject("select count(*) from companies", Integer.class)).isZero();

        migrateTo("38");

        assertThat(jdbc.queryForObject("select count(*) from companies", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from company_memberships", Integer.class)).isZero();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String snapshot(JdbcTemplate jdbc, long companyId, long ownerId) {
        return jdbc.queryForObject("""
                select concat_ws('|',
                           c.status::text, c.verification_status::text,
                           c.reviewed_at::text, coalesce(c.reviewed_by::text, 'null'), c.verified_at::text,
                           u.company_id::text,
                           (select concat_ws(',', count(*)::text, min(m.status::text), min(m.approved_at)::text)
                              from company_memberships m
                             where m.company_id = c.id and m.user_id = u.id))
                  from companies c join users u on u.id = ?
                 where c.id = ?
                """, String.class, ownerId, companyId);
    }

    private long insertUser(JdbcTemplate jdbc, String email, String role, String status) {
        return jdbc.queryForObject("""
                insert into users (email, name, role, password_hash, status)
                values (?, 'V38 사용자', ?::role_type, 'test-password-hash', ?::user_status_type)
                returning id
                """, Long.class, email, role, status);
    }

    private long insertCompany(JdbcTemplate jdbc, long ownerUserId, String name, String brn,
                               String status, String verificationStatus) {
        return jdbc.queryForObject("""
                insert into companies (owner_user_id, name, business_registration_number,
                                       representative_name, address, business_registration_file_url,
                                       status, verification_status)
                values (?, ?, ?, '대표자', '서울시', '/test/v38.png',
                        ?::company_status_type, ?::business_verification_status_type)
                returning id
                """, Long.class, ownerUserId, name, brn, status, verificationStatus);
    }

    private void insertMembership(JdbcTemplate jdbc, long companyId, long userId, String status) {
        jdbc.update("""
                insert into company_memberships (company_id, user_id, status, approved_at, revoked_at)
                values (?, ?, ?::company_membership_status_type,
                        case when ? in ('APPROVED', 'REVOKED') then now() end,
                        case when ? = 'REVOKED' then now() end)
                """, companyId, userId, status, status, status);
    }

    private int membershipCount(JdbcTemplate jdbc, long companyId, long userId) {
        return jdbc.queryForObject(
                "select count(*) from company_memberships where company_id = ? and user_id = ?",
                Integer.class, companyId, userId);
    }

    private String ocrRawField(JdbcTemplate jdbc, long companyId, String key) {
        return jdbc.queryForObject(
                "select business_registration_ocr_raw->>? from companies where id = ?",
                String.class, key, companyId);
    }

    private String statusOf(JdbcTemplate jdbc, long companyId) {
        return jdbc.queryForObject("select status::text from companies where id = ?", String.class, companyId);
    }

    private String verificationOf(JdbcTemplate jdbc, long companyId) {
        return jdbc.queryForObject(
                "select verification_status::text from companies where id = ?", String.class, companyId);
    }

    private String migrationSql() {
        try (var in = new ClassPathResource(MIGRATION_RESOURCE).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "V38 마이그레이션 리소스를 읽지 못했다: " + MIGRATION_RESOURCE, e);
        }
    }

    private void migrateTo(String target) {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .target(target)
                .load()
                .migrate();
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    }
}
