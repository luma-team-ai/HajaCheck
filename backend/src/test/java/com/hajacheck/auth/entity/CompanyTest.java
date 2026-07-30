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
