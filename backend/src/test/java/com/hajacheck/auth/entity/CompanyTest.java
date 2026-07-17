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
    void createPendingReview_OCR원본이유효한JSON이아니면예외() {
        assertThatThrownBy(() -> Company.createPendingReview(
                1L, "HajaCheck", "123-45-67890", "Owner", "Seoul", null,
                "https://files.example/registration.pdf", "not-json"))
                .isInstanceOf(IllegalArgumentException.class);
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
