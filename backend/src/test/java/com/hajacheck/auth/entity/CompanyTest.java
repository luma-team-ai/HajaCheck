package com.hajacheck.auth.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hajacheck.global.util.JsonValidator;
import org.junit.jupiter.api.Test;

class CompanyTest {

    @Test
    void reviewDecision_canOnlyBeMadeFromPendingReview() {
        Company approved = company();
        approved.markBusinessVerified();
        approved.approve(10L);

        assertThat(approved.getStatus()).isEqualTo(CompanyStatus.APPROVED);
        assertThatThrownBy(() -> approved.reject(11L, "duplicate decision"))
                .isInstanceOf(IllegalStateException.class);

        Company rejected = company();
        rejected.reject(12L, "invalid registration");

        assertThat(rejected.getStatus()).isEqualTo(CompanyStatus.REJECTED);
        assertThatThrownBy(() -> rejected.approve(13L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void approve_requiresVerifiedBusinessRegistration() {
        Company company = company();

        assertThatThrownBy(() -> company.approve(10L))
                .isInstanceOf(IllegalStateException.class);
        assertThat(company.getStatus()).isEqualTo(CompanyStatus.PENDING_REVIEW);
    }

    @Test
    void autoApprove_진위확인상태와무관하게_APPROVED로전이하고_심사자는null() {
        Company company = company();

        // #1324 — approve(Long) 와 달리 VERIFIED 선행을 요구하지 않는다(진위 PENDING 상태에서 호출).
        company.autoApprove();

        assertThat(company.getStatus()).isEqualTo(CompanyStatus.APPROVED);
        // 사람 심사자 없음(시스템 자동승인) — companies.reviewed_by 는 nullable.
        assertThat(company.getReviewedBy()).isNull();
        assertThat(company.getReviewedAt()).isNotNull();
        assertThat(company.getRejectionReason()).isNull();
        // 진위확인 상태 승격은 autoApprove 의 책임이 아니다(markBusinessVerified 분리).
        assertThat(company.getVerificationStatus()).isEqualTo(BusinessVerificationStatus.PENDING);
    }

    @Test
    void autoApprove_반려된회사를_되살리지못한다() {
        Company rejected = company();
        rejected.reject(12L, "invalid registration");

        // 소급/자동 승인이 명시적 반려 이력을 덮으면 안 된다 — PENDING_REVIEW 에서만 전이 가능.
        assertThatThrownBy(rejected::autoApprove)
                .isInstanceOf(IllegalStateException.class);
        assertThat(rejected.getStatus()).isEqualTo(CompanyStatus.REJECTED);
        assertThat(rejected.getRejectionReason()).isEqualTo("invalid registration");
    }

    @Test
    void autoApprove_두번호출하면_예외() {
        Company company = company();
        company.autoApprove();

        // 멱등이 아니다 — 이미 승인된 회사의 reviewedAt 을 다시 덮어쓰지 않는다.
        assertThatThrownBy(company::autoApprove)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void approve_기존관리자승인은_VERIFIED선행가드를_유지한다() {
        Company company = company();
        company.autoApprove();

        // #1324 는 approve(Long) 의 가드를 느슨하게 풀지 않는다 — 자동승인은 별 경로다.
        // (이미 APPROVED 라 상태 가드에서 먼저 걸린다 = 중복 승인 차단.)
        assertThatThrownBy(() -> company.approve(10L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void markBusinessVerificationFailed_FAILED로전이하고_verifiedAt은건드리지않는다() {
        Company company = company();

        company.markBusinessVerificationFailed();

        assertThat(company.getVerificationStatus()).isEqualTo(BusinessVerificationStatus.FAILED);
        assertThat(company.getVerifiedAt()).isNull();
    }

    @Test
    void createPendingReview_OCR원본이유효한JSON이아니면예외() {
        assertThatThrownBy(() -> Company.createPendingReview(
                1L, "HajaCheck", "123-45-67890", "Owner", "Seoul", null,
                "https://files.example/registration.pdf", "not-json"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createPendingReview_공백OCR원본은null로정규화() {
        Company company = Company.createPendingReview(
                1L, "HajaCheck", "123-45-67890", "Owner", "Seoul", null,
                "https://files.example/registration.pdf", "   ");

        assertThat(company.getBusinessRegistrationOcrRaw()).isNull();
    }

    @Test
    void isNtsVerified_국세청성공과_레거시검증만_true() {
        // 신규 가입 경로: CompanySignupService.buildOcrRaw 가 실제 국세청 결과를 남긴다.
        assertThat(companyWithOcrRaw("{\"source\":\"MANUAL_INPUT\",\"ntsOutcome\":\"VERIFIED\"}")
                .isNtsVerified()).isTrue();
        // V38 이 스탬프한 레거시 진짜 검증분(#1324 이전 VERIFIED 는 국세청 성공으로만 찍혔다).
        assertThat(companyWithOcrRaw("{\"ntsOutcome\":\"LEGACY_VERIFIED\"}").isNtsVerified()).isTrue();
    }

    @Test
    void isNtsVerified_증명할수없으면_모두false() {
        // 국세청 장애·키 미설정으로 확인하지 못함(fail-open 으로 가입은 됐지만 검증된 건 아니다).
        assertThat(companyWithOcrRaw("{\"ntsOutcome\":\"SKIPPED\"}").isNtsVerified()).isFalse();
        // V38 소급 승인분 — 검증한 적이 없다.
        assertThat(companyWithOcrRaw("{\"ntsOutcome\":\"UNKNOWN_BACKFILL\"}").isNtsVerified()).isFalse();
        // 키 부재(직렬화 실패 fallback·V38 이전 데이터) → fail-safe false.
        assertThat(companyWithOcrRaw("{\"source\":\"MANUAL_INPUT\"}").isNtsVerified()).isFalse();
        // 컬럼 null.
        assertThat(companyWithOcrRaw(null).isNtsVerified()).isFalse();
        // 미래에 추가될 수 있는 미지의 라벨도 화이트리스트 밖이면 false(fail-open 금지).
        assertThat(companyWithOcrRaw("{\"ntsOutcome\":\"SOME_NEW_LABEL\"}").isNtsVerified()).isFalse();
    }

    @Test
    void isNtsVerified_verificationStatus가VERIFIED여도_provenance가없으면_false() {
        // #1324 P1 의 핵심 — 자동승인이 verification_status 를 전건 VERIFIED 로 만들기 때문에
        // 그 컬럼을 배지 근거로 쓰면 미검증 회사에 "사업자 인증 완료"라는 허위 표시가 나간다.
        Company company = companyWithOcrRaw("{\"ntsOutcome\":\"SKIPPED\"}");
        company.markBusinessVerified();
        company.autoApprove();

        assertThat(company.getVerificationStatus()).isEqualTo(BusinessVerificationStatus.VERIFIED);
        assertThat(company.getStatus()).isEqualTo(CompanyStatus.APPROVED);
        assertThat(company.isNtsVerified()).isFalse();
    }

    @Test
    void markBusinessVerified_는_provenance를_건드리지않는다() {
        // #1324 P1 — 가입 경로는 진위확인 결과와 무관하게 이 메서드를 호출한다. 여기서 ntsOutcome 을
        // 찍으면 국세청이 확인해 주지 않은 회사(SKIPPED)에 허위 provenance 가 박힌다.
        // 가입 경로 provenance 의 진실 소스는 CompanySignupService.buildOcrRaw(생성 시점 인자)다.
        Company company = companyWithOcrRaw("{\"source\":\"MANUAL_INPUT\",\"ntsOutcome\":\"SKIPPED\"}");

        company.markBusinessVerified();

        assertThat(company.getVerificationStatus()).isEqualTo(BusinessVerificationStatus.VERIFIED);
        assertThat(JsonValidator.readTextField(company.getBusinessRegistrationOcrRaw(), "ntsOutcome"))
                .contains("SKIPPED");
        assertThat(company.isNtsVerified()).isFalse();
    }

    @Test
    void markBusinessVerifiedByNts_는_provenance를_VERIFIED로_갱신하고_기존키를보존한다() {
        // #888 재검증 배치 전용 경로 — 국세청이 실제로 확인해 준 경우에만 호출된다.
        Company company = companyWithOcrRaw("{\"source\":\"MANUAL_INPUT\",\"ntsOutcome\":\"SKIPPED\"}");

        company.markBusinessVerifiedByNts();

        assertThat(company.getVerificationStatus()).isEqualTo(BusinessVerificationStatus.VERIFIED);
        assertThat(company.getVerifiedAt()).isNotNull();
        // 대상 조회(findNtsReverifyTargets)의 제외 화이트리스트에 들어가 다음 회차에서 빠진다(루프 종료).
        assertThat(JsonValidator.readTextField(company.getBusinessRegistrationOcrRaw(), "ntsOutcome"))
                .contains("VERIFIED");
        // 실제 조회 시각 키 규약은 가입 경로(buildOcrRaw)와 동일하다.
        assertThat(JsonValidator.readTextField(company.getBusinessRegistrationOcrRaw(), "ntsCheckedAt"))
                .isPresent();
        // 감사 키 병합 보존 — 통째 교체 금지(클래스 javadoc 경고).
        assertThat(JsonValidator.readTextField(company.getBusinessRegistrationOcrRaw(), "source"))
                .contains("MANUAL_INPUT");
        assertThat(company.isNtsVerified()).isTrue();
    }

    @Test
    void markBusinessVerifiedByNts_는_컬럼이null이어도_provenance를남긴다() {
        Company company = companyWithOcrRaw(null);

        company.markBusinessVerifiedByNts();

        assertThat(company.isNtsVerified()).isTrue();
    }

    @Test
    void isNtsVerified_깨진JSON이어도_예외를던지지않고_false() {
        // 조회 경로(마이페이지)에서 호출되므로 절대 500 을 만들면 안 된다. 값이 이상하면 "증명 불가".
        // (createPendingReview 의 JSON 검증을 우회해 컬럼에 직접 심는다 — 외부/수동 수정 상황 재현.)
        Company company = company();
        setOcrRaw(company, "{not-json");

        assertThat(company.isNtsVerified()).isFalse();
    }

    @Test
    void isNtsVerified_ntsOutcome이_문자열이아니면_false() {
        Company company = company();
        setOcrRaw(company, "{\"ntsOutcome\":{\"nested\":\"VERIFIED\"}}");

        assertThat(company.isNtsVerified()).isFalse();
    }

    // ── 플랫폼 관리자 무효화/복구 왕복(#1367) ──────────────────────────────────────────────────

    @Test
    void revokeBusinessVerificationByAdmin_FAILED로전이하고_배지도함께꺼진다() {
        // 자동승인(#1324)으로 스코프가 열린 회사 — 국세청이 실제로 확인해 준 상태(배지 켜짐)를 가정한다.
        Company company = companyWithOcrRaw("{\"source\":\"MANUAL_INPUT\",\"ntsOutcome\":\"VERIFIED\"}");
        company.markBusinessVerified();
        assertThat(company.isNtsVerified()).isTrue();

        company.revokeBusinessVerificationByAdmin("사칭 신고 접수", 77L);

        // 이 한 줄이 스코프 판정·DB 트리거의 VERIFIED 조건을 깨 전 구성원의 회사 스코프를 닫는다.
        assertThat(company.getVerificationStatus()).isEqualTo(BusinessVerificationStatus.FAILED);
        // 차단된 회사에 "사업자 인증 완료" 배지가 켜진 채 남으면 부정합 — ntsOutcome 을 반드시 덮는다.
        assertThat(company.isNtsVerified()).isFalse();
        assertThat(ocrField(company, "ntsOutcome")).isEqualTo("ADMIN_REVOKED");
    }

    @Test
    void revokeBusinessVerificationByAdmin_직전판정과_사유와시각을_병합보존한다() {
        Company company = companyWithOcrRaw("{\"source\":\"MANUAL_INPUT\",\"ntsOutcome\":\"SKIPPED\"}");

        company.revokeBusinessVerificationByAdmin("오등록 확인", 77L);

        // 덮기 직전 판정을 남겨야 무효화가 오판이었는지 사후에 판단할 수 있다(감사 기록 소실 방지).
        assertThat(ocrField(company, "ntsOutcomeBeforeRevoke")).isEqualTo("SKIPPED");
        assertThat(ocrField(company, "adminRevokeReason")).isEqualTo("오등록 확인");
        assertThat(ocrField(company, "adminRevokedAt")).isNotBlank();
        // 통째 교체 금지(클래스 javadoc) — 다른 주체가 써넣은 기존 감사 키는 그대로 남아야 한다.
        assertThat(ocrField(company, "source")).isEqualTo("MANUAL_INPUT");
    }

    @Test
    void revokeBusinessVerificationByAdmin_직전판정이없으면_해당키를쓰지않는다() {
        Company company = companyWithOcrRaw("{\"source\":\"MANUAL_INPUT\"}");

        company.revokeBusinessVerificationByAdmin("사유", 77L);

        // 없는 값을 "null" 문자열 등으로 지어내지 않는다.
        assertThat(JsonValidator.readTextField(company.getBusinessRegistrationOcrRaw(),
                "ntsOutcomeBeforeRevoke")).isEmpty();
    }

    @Test
    void revokeBusinessVerificationByAdmin_이미FAILED면_예외이고_멱등no_op이아니다() {
        Company company = companyWithOcrRaw("{\"ntsOutcome\":\"VERIFIED\"}");
        company.revokeBusinessVerificationByAdmin("최초 사유", 77L);

        // 두 번째 호출을 조용히 통과시키면 최초 사유·ntsOutcomeBeforeRevoke 가 덮여 감사 기록이 흐려진다.
        assertThatThrownBy(() -> company.revokeBusinessVerificationByAdmin("나중 사유", 77L))
                .isInstanceOf(IllegalStateException.class);
        assertThat(ocrField(company, "adminRevokeReason")).isEqualTo("최초 사유");
        assertThat(ocrField(company, "ntsOutcomeBeforeRevoke")).isEqualTo("VERIFIED");
    }

    /**
     * A-2 분기 ① — 관리자 자기 조치의 <b>순수 취소</b>는 직전 검증 상태로 즉시 복원한다.
     * PENDING 을 거치면 다음 배치 회차(하루 1회)까지 정상 회사가 멈춰 있어, 오조작 revoke 한 건이
     * 최대 하루 가까운 서비스 중단이 된다.
     */
    @Test
    void restore_관리자무효화의취소는_직전VERIFIED로_즉시복원한다() {
        Company company = companyWithOcrRaw("{\"ntsOutcome\":\"VERIFIED\"}");
        company.markBusinessVerified();
        company.revokeBusinessVerificationByAdmin("오조작", 77L);

        AdminRestoreMode mode = company.restoreBusinessVerificationByAdmin("오조작 취소", 88L);

        assertThat(mode).isEqualTo(AdminRestoreMode.RESTORED_TO_VERIFIED);
        assertThat(company.getVerificationStatus()).isEqualTo(BusinessVerificationStatus.VERIFIED);
        // ntsOutcome 도 무효화 직전 값으로 원복된다 — 되무르는 것이지 새 판정을 만드는 게 아니다.
        assertThat(ocrField(company, "ntsOutcome")).isEqualTo("VERIFIED");
        // 국세청이 실제로 확인해 준 상태로 돌아왔으므로 배지도 원래대로 켜진다.
        assertThat(company.isNtsVerified()).isTrue();
        assertThat(ocrField(company, "adminRestoreReason")).isEqualTo("오조작 취소");
        assertThat(ocrField(company, "adminRestoredBy")).isEqualTo("88");
    }

    @Test
    void restore_LEGACY_VERIFIED였던_무효화도_즉시복원대상이다() {
        // 화이트리스트(VERIFIED/LEGACY_VERIFIED)와 같은 집합으로 판정한다 — #1324 이전 진짜 검증분.
        Company company = companyWithOcrRaw("{\"ntsOutcome\":\"LEGACY_VERIFIED\"}");
        company.markBusinessVerified();
        company.revokeBusinessVerificationByAdmin("오조작", 77L);

        assertThat(company.restoreBusinessVerificationByAdmin("취소", 88L))
                .isEqualTo(AdminRestoreMode.RESTORED_TO_VERIFIED);
        assertThat(company.getVerificationStatus()).isEqualTo(BusinessVerificationStatus.VERIFIED);
        assertThat(ocrField(company, "ntsOutcome")).isEqualTo("LEGACY_VERIFIED");
    }

    /**
     * A-2 분기 ② — 국세청 인정 이력을 <b>증명할 수 없는</b> 무효화(직전이 SKIPPED 등)는 VERIFIED 로
     * 되돌리지 않는다. 관리자가 국세청 판정을 대신하는 경로가 되면 안 되기 때문이다.
     */
    @Test
    void restore_직전판정이_증명불가면_PENDING으로만_되돌린다() {
        Company company = companyWithOcrRaw("{\"ntsOutcome\":\"SKIPPED\"}");
        company.markBusinessVerified();
        company.revokeBusinessVerificationByAdmin("사칭 신고 접수", 77L);

        AdminRestoreMode mode = company.restoreBusinessVerificationByAdmin("오탐 확인", 88L);

        assertThat(mode).isEqualTo(AdminRestoreMode.RESTORED_TO_PENDING);
        assertThat(company.getVerificationStatus()).isEqualTo(BusinessVerificationStatus.PENDING);
        assertThat(ocrField(company, "ntsOutcome")).isEqualTo("ADMIN_RESTORED");
        assertThat(ocrField(company, "adminRestoreReason")).isEqualTo("오탐 확인");
        assertThat(ocrField(company, "adminRestoredAt")).isNotBlank();
        // 증명 불가 라벨이라 배지는 꺼진 채 유지된다 — 국세청이 확인해 주면 배치가 켠다.
        assertThat(company.isNtsVerified()).isFalse();
        // 무효화 감사 기록은 복구 후에도 남는다(병합).
        assertThat(ocrField(company, "adminRevokeReason")).isEqualTo("사칭 신고 접수");
        assertThat(ocrField(company, "ntsOutcomeBeforeRevoke")).isEqualTo("SKIPPED");
    }

    @Test
    void restoreBusinessVerificationByAdmin_FAILED가아니면_예외() {
        Company verified = companyWithOcrRaw("{\"ntsOutcome\":\"VERIFIED\"}");
        verified.markBusinessVerified();

        assertThatThrownBy(() -> verified.restoreBusinessVerificationByAdmin("사유", 88L))
                .isInstanceOf(IllegalStateException.class);
        assertThat(verified.getVerificationStatus()).isEqualTo(BusinessVerificationStatus.VERIFIED);
    }

    @Test
    void 배치강등FAILED는_관리자복구로_PENDING까지만_되돌아간다() {
        // 실사고 재현 — 배치(markBusinessVerificationFailed)가 만든 FAILED 는 재검증 대상에서 영구
        // 제외돼 자가치유가 없었다. 복구 경로가 그 FAILED 도 받아야 왕복이 완성된다.
        // 다만 관리자 조치가 아니므로(ntsOutcome != ADMIN_REVOKED) 즉시 복원 분기는 타지 않는다.
        Company company = companyWithOcrRaw("{\"ntsOutcome\":\"VERIFIED\"}");
        company.markBusinessVerificationFailed();

        AdminRestoreMode mode = company.restoreBusinessVerificationByAdmin("MISMATCH 오탐 소명 완료", 88L);

        assertThat(mode).isEqualTo(AdminRestoreMode.RESTORED_TO_PENDING);
        assertThat(company.getVerificationStatus()).isEqualTo(BusinessVerificationStatus.PENDING);
    }

    // ── 강제개방 override(#1367 P1-A) ────────────────────────────────────────────────────────

    @Test
    void override_VERIFIED로열되_배지는꺼진채유지되고_재검증표식이남는다() {
        // 대표자 변경으로 국세청이 계속 MISMATCH 를 주는 회사 — restore(PENDING)로는 영영 열리지 않는다.
        Company company = companyWithOcrRaw("{\"source\":\"MANUAL_INPUT\",\"ntsOutcome\":\"SKIPPED\"}");

        company.overrideBusinessVerificationByAdmin("등기부·현장 실물 확인 완료", 55L);

        assertThat(company.getVerificationStatus()).isEqualTo(BusinessVerificationStatus.VERIFIED);
        assertThat(ocrField(company, "ntsOutcome")).isEqualTo("ADMIN_OVERRIDE_VERIFIED");
        // ⚠️ 화이트리스트 밖이어야 한다 — 배지가 켜지면 "국세청이 확인해 줬다"는 허위 표시가 된다.
        assertThat(company.isNtsVerified()).isFalse();
        assertThat(company.isAdminOverridden()).isTrue();
        assertThat(ocrField(company, "ntsOutcomeBeforeOverride")).isEqualTo("SKIPPED");
        assertThat(ocrField(company, "adminOverrideReason")).isEqualTo("등기부·현장 실물 확인 완료");
        assertThat(ocrField(company, "adminOverriddenBy")).isEqualTo("55");
        assertThat(ocrField(company, "adminOverriddenAt")).isNotBlank();
        assertThat(ocrField(company, "source")).isEqualTo("MANUAL_INPUT");
        // verifiedAt 은 국세청 검증 성공 시각이므로 override 로 찍지 않는다.
        assertThat(company.getVerifiedAt()).isNull();
    }

    @Test
    void override_이미VERIFIED면_예외() {
        Company company = companyWithOcrRaw("{\"ntsOutcome\":\"VERIFIED\"}");
        company.markBusinessVerified();

        assertThatThrownBy(() -> company.overrideBusinessVerificationByAdmin("사유", 55L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void override_무효화된회사도_열수있다() {
        Company company = companyWithOcrRaw("{\"ntsOutcome\":\"VERIFIED\"}");
        company.revokeBusinessVerificationByAdmin("사칭 의심", 77L);

        company.overrideBusinessVerificationByAdmin("소명 완료", 55L);

        assertThat(company.getVerificationStatus()).isEqualTo(BusinessVerificationStatus.VERIFIED);
        assertThat(company.isAdminRevoked()).isFalse();
    }

    // ── 재검증 스탬프(#1367 P1-C / P2-3) ─────────────────────────────────────────────────────

    @Test
    void 시도스탬프는_ntsOutcome을_건드리지않는다() {
        // ntsOutcome 은 재검증 대상 집합(where 절)을 결정한다 — 스탬프가 그것을 바꾸면 통제가 깨진다.
        Company company = companyWithOcrRaw("{\"source\":\"MANUAL_INPUT\",\"ntsOutcome\":\"SKIPPED\"}");

        company.stampNtsReverifyAttempt();

        assertThat(company.ntsLastAttemptAt()).isPresent();
        assertThat(ocrField(company, "ntsOutcome")).isEqualTo("SKIPPED");
        assertThat(ocrField(company, "source")).isEqualTo("MANUAL_INPUT");
    }

    @Test
    void 경보스탬프는_판정라벨과시각을_남기되_ntsOutcome은유지한다() {
        // 자동 강등하지 않기로 한 판정을 인가 근거 키(ntsOutcome)에 쓰면 대상 집합이 흔들린다.
        Company company = companyWithOcrRaw("{\"ntsOutcome\":\"SKIPPED\"}");

        company.stampNtsReverifyAlert("MISMATCH");

        assertThat(company.ntsLastAlertOutcome()).contains("MISMATCH");
        assertThat(company.ntsLastAlertAt()).isPresent();
        // 경보도 시도이므로 라운드로빈 스탬프가 함께 찍힌다.
        assertThat(company.ntsLastAttemptAt()).isPresent();
        assertThat(ocrField(company, "ntsOutcome")).isEqualTo("SKIPPED");
    }

    @Test
    void 무효화와복구_모두_verifiedAt을_건드리지않는다() {
        Company company = companyWithOcrRaw("{\"ntsOutcome\":\"VERIFIED\"}");
        company.markBusinessVerified();
        java.time.Instant verifiedAt = company.getVerifiedAt();

        company.revokeBusinessVerificationByAdmin("사유", 77L);
        assertThat(company.getVerifiedAt()).isEqualTo(verifiedAt);

        company.restoreBusinessVerificationByAdmin("사유", 88L);
        // verifiedAt 은 "검증 성공 시각"으로 좁게 쓰인다 — 실패·복구 시각을 여기 재사용하면 의미가 오염된다
        // (markBusinessVerificationFailed javadoc 방침).
        assertThat(company.getVerifiedAt()).isEqualTo(verifiedAt);
    }

    @Test
    void 무효화는_ocrRaw가null이어도_provenance를_남긴다() {
        Company company = companyWithOcrRaw(null);

        company.revokeBusinessVerificationByAdmin("사유", 77L);

        assertThat(ocrField(company, "ntsOutcome")).isEqualTo("ADMIN_REVOKED");
        assertThat(company.isNtsVerified()).isFalse();
    }

    private static String ocrField(Company company, String field) {
        return JsonValidator.readTextField(company.getBusinessRegistrationOcrRaw(), field).orElse(null);
    }

    private Company companyWithOcrRaw(String ocrRaw) {
        return Company.createPendingReview(
                1L, "HajaCheck", "123-45-67890", "Owner", "Seoul", null,
                "https://files.example/registration.pdf", ocrRaw);
    }

    /** 유효성 검증을 우회해 컬럼 값을 직접 심는다(외부에서 손댄 jsonb 재현) — 테스트 전용. */
    private static void setOcrRaw(Company company, String raw) {
        try {
            java.lang.reflect.Field field =
                    Company.class.getDeclaredField("businessRegistrationOcrRaw");
            field.setAccessible(true);
            field.set(company, raw);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private Company company() {
        return Company.createPendingReview(
                1L,
                "HajaCheck",
                "123-45-67890",
                "Owner",
                "Seoul",
                null,
                "https://files.example/registration.pdf",
                "{\"source\":\"MANUAL_INPUT\"}");
    }
}
