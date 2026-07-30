package com.hajacheck.auth.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
