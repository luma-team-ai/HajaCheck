package com.hajacheck.bizverify.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hajacheck.auth.entity.BusinessVerificationStatus;
import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.CompanyMembership;
import com.hajacheck.auth.entity.CompanyMembershipStatus;
import com.hajacheck.auth.repository.CompanyMembershipRepository;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.global.util.JsonValidator;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PendingBusinessReverifyWriter 단위테스트(#888, provenance·멤버십 회수는 #1324 P1) — 엔티티 상태 전이
 * 자체는 CompanyTest 가 검증하므로, 여기서는 조회된 회사에 올바른 전이가 적용되는지, 확정 불량 시 오너
 * 멤버십이 회수되는지, "소멸(조회 안 됨)" 방어를 검증한다.
 */
class PendingBusinessReverifyWriterTest {

    private static final long COMPANY_ID = 1L;
    private static final long OWNER_ID = 7L;

    private CompanyRepository companyRepository;
    private CompanyMembershipRepository companyMembershipRepository;
    private PendingBusinessReverifyWriter writer;

    @BeforeEach
    void setUp() {
        companyRepository = mock(CompanyRepository.class);
        companyMembershipRepository = mock(CompanyMembershipRepository.class);
        writer = new PendingBusinessReverifyWriter(companyRepository, companyMembershipRepository);
    }

    private Company pendingCompany() {
        return pendingCompany("{\"source\":\"MANUAL_INPUT\",\"ntsOutcome\":\"SKIPPED\"}");
    }

    private Company pendingCompany(String ocrRaw) {
        return Company.createPendingReview(
                OWNER_ID, "HajaCheck", "123-45-67890", "대표자", "서울", null,
                "https://files.example/registration.pdf", ocrRaw);
    }

    private void stubCompany(Company company) {
        when(companyRepository.findById(COMPANY_ID)).thenReturn(Optional.of(company));
    }

    private String ntsOutcomeOf(Company company) {
        return JsonValidator.readTextField(company.getBusinessRegistrationOcrRaw(), "ntsOutcome")
                .orElse(null);
    }

    @Test
    @DisplayName("markVerified는 조회된 회사를 VERIFIED로 전이한다")
    void markVerified_VERIFIED로전이() {
        Company company = pendingCompany();
        stubCompany(company);

        writer.markVerified(COMPANY_ID);

        assertThat(company.getVerificationStatus()).isEqualTo(BusinessVerificationStatus.VERIFIED);
    }

    @Test
    @DisplayName("markVerified는 provenance를 ntsOutcome=VERIFIED로 갱신해 다음 회차 대상에서 빠지게 한다(루프 종료)")
    void markVerified_provenance갱신으로_루프종료() {
        // 국세청 장애 구간에 가입해 SKIPPED 로 남아 있던 회사 — 재조회에 성공했다.
        Company company = pendingCompany();
        stubCompany(company);

        writer.markVerified(COMPANY_ID);

        // 대상 조회(findNtsReverifyTargets)가 화이트리스트(VERIFIED/LEGACY_VERIFIED)로 제외하는 값이 된다.
        assertThat(ntsOutcomeOf(company)).isEqualTo("VERIFIED");
        // 사용자 대면 "사업자 인증 완료" 배지도 함께 켜진다(provenance 판정).
        assertThat(company.isNtsVerified()).isTrue();
    }

    @Test
    @DisplayName("markVerified의 provenance 갱신은 기존 감사 키(source)를 보존한다")
    void markVerified_기존키보존() {
        Company company = pendingCompany();
        stubCompany(company);

        writer.markVerified(COMPANY_ID);

        assertThat(JsonValidator.readTextField(company.getBusinessRegistrationOcrRaw(), "source"))
                .contains("MANUAL_INPUT");
        assertThat(JsonValidator.readTextField(company.getBusinessRegistrationOcrRaw(), "ntsCheckedAt"))
                .isPresent();
    }

    @Test
    @DisplayName("markFailed는 조회된 회사를 FAILED로 전이한다")
    void markFailed_FAILED로전이() {
        Company company = pendingCompany();
        stubCompany(company);
        when(companyMembershipRepository.findByCompanyIdAndUserId(COMPANY_ID, OWNER_ID))
                .thenReturn(Optional.empty());

        writer.markFailed(COMPANY_ID);

        assertThat(company.getVerificationStatus()).isEqualTo(BusinessVerificationStatus.FAILED);
    }

    @Test
    @DisplayName("markFailed는 오너의 APPROVED 멤버십을 회수해 스코프 재개방 지뢰를 제거한다")
    void markFailed_오너멤버십회수() {
        Company company = pendingCompany();
        stubCompany(company);
        CompanyMembership ownerMembership = CompanyMembership.approvedOwner(COMPANY_ID, OWNER_ID);
        when(companyMembershipRepository.findByCompanyIdAndUserId(COMPANY_ID, OWNER_ID))
                .thenReturn(Optional.of(ownerMembership));

        writer.markFailed(COMPANY_ID);

        assertThat(ownerMembership.getStatus()).isEqualTo(CompanyMembershipStatus.REVOKED);
        assertThat(ownerMembership.getRevokedAt()).isNotNull();
        // 회수된 멤버십은 유효 판정에서 빠진다 = 회사 스코프가 닫힌다.
        assertThat(ownerMembership.isEffectiveAt(java.time.Instant.now())).isFalse();
    }

    @Test
    @DisplayName("markFailed는 이미 회수된 멤버십에는 상태 전이를 시도하지 않는다(재실행 안전)")
    void markFailed_이미회수된멤버십은건드리지않는다() {
        Company company = pendingCompany();
        stubCompany(company);
        CompanyMembership ownerMembership = CompanyMembership.approvedOwner(COMPANY_ID, OWNER_ID);
        ownerMembership.revoke();
        when(companyMembershipRepository.findByCompanyIdAndUserId(COMPANY_ID, OWNER_ID))
                .thenReturn(Optional.of(ownerMembership));

        writer.markFailed(COMPANY_ID);

        // revoke()는 APPROVED 에서만 허용되는 전이 — 가드가 없으면 여기서 예외로 배치 1건이 실패한다.
        assertThat(company.getVerificationStatus()).isEqualTo(BusinessVerificationStatus.FAILED);
        assertThat(ownerMembership.getStatus()).isEqualTo(CompanyMembershipStatus.REVOKED);
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
