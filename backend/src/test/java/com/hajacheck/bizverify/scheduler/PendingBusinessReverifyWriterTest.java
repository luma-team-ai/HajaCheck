package com.hajacheck.bizverify.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hajacheck.auth.entity.BusinessVerificationStatus;
import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.repository.CompanyRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PendingBusinessReverifyWriter 단위테스트(#888) — 엔티티 상태 전이 자체는 CompanyTest 가 검증하므로,
 * 여기서는 조회된 회사에 올바른 전이 메서드가 호출되는지와 "소멸(조회 안 됨)" 방어만 검증한다.
 */
class PendingBusinessReverifyWriterTest {

    private CompanyRepository companyRepository;
    private PendingBusinessReverifyWriter writer;

    @BeforeEach
    void setUp() {
        companyRepository = mock(CompanyRepository.class);
        writer = new PendingBusinessReverifyWriter(companyRepository);
    }

    private Company pendingCompany() {
        return Company.createPendingReview(
                1L, "HajaCheck", "123-45-67890", "대표자", "서울", null,
                "https://files.example/registration.pdf", "{\"source\":\"MANUAL_INPUT\"}");
    }

    @Test
    @DisplayName("markVerified는 조회된 회사를 VERIFIED로 전이한다")
    void markVerified_VERIFIED로전이() {
        Company company = pendingCompany();
        when(companyRepository.findById(1L)).thenReturn(Optional.of(company));

        writer.markVerified(1L);

        assertThat(company.getVerificationStatus()).isEqualTo(BusinessVerificationStatus.VERIFIED);
    }

    @Test
    @DisplayName("markFailed는 조회된 회사를 FAILED로 전이한다")
    void markFailed_FAILED로전이() {
        Company company = pendingCompany();
        when(companyRepository.findById(1L)).thenReturn(Optional.of(company));

        writer.markFailed(1L);

        assertThat(company.getVerificationStatus()).isEqualTo(BusinessVerificationStatus.FAILED);
    }

    @Test
    @DisplayName("대상 회사가 조회되지 않아도(소멸) 예외 없이 무시한다")
    void 회사소멸_예외없이무시() {
        when(companyRepository.findById(999L)).thenReturn(Optional.empty());

        writer.markVerified(999L);
        writer.markFailed(999L);
        // 예외 없이 통과하면 성공 — 별도 assertion 불필요(방어 동작 자체가 검증 대상).
    }
}
