package com.hajacheck.bizverify.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hajacheck.auth.entity.BusinessVerificationStatus;
import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.repository.CompanyMembershipRepository;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.bizverify.service.NtsVerificationOutcome;
import com.hajacheck.global.util.JsonValidator;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PendingBusinessReverifyWriter 단위테스트(#888, provenance 기록은 #1324 P1) — 엔티티 상태 전이 자체는
 * CompanyTest 가 검증하므로, 여기서는 조회된 회사에 올바른 전이가 적용되는지, 확정 불량 시 멤버십을
 * <b>회수하지 않는다는 의도적 선택</b>(비가역 — 복구는 #1367 의 관리자 restore API), "소멸(조회 안 됨)"
 * 방어, 그리고 <b>관리자 조치와의 우선순위</b>(#1367 P1-B)를 검증한다.
 */
class PendingBusinessReverifyWriterTest {

    private static final long COMPANY_ID = 1L;
    private static final long OWNER_ID = 7L;

    private CompanyRepository companyRepository;
    private PendingBusinessReverifyWriter writer;

    @BeforeEach
    void setUp() {
        companyRepository = mock(CompanyRepository.class);
        writer = new PendingBusinessReverifyWriter(companyRepository);
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
        when(companyRepository.findByIdForUpdate(COMPANY_ID)).thenReturn(Optional.of(company));
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

        writer.markFailed(COMPANY_ID, NtsVerificationOutcome.CLOSED);

        assertThat(company.getVerificationStatus()).isEqualTo(BusinessVerificationStatus.FAILED);
    }

    @Test
    @DisplayName("markFailed는 멤버십을 회수하지 않는다 — 의도적 선택(비가역·복구 경로 부재, 후속 #1367)")
    void markFailed_멤버십은회수하지않는다() {
        // FAILED 전이만으로 스코프 판정(existsEffectiveApprovedMembership 의 VERIFIED 조건)과 동일 불변식의
        // DB 트리거가 전 구성원의 회사 스코프를 닫는다 → 되돌릴 수 없는 멤버십 회수를 추가하지 않는다.
        // (스코프가 실제로 닫히는지는 CompanyMembershipRepositoryTest 의 실 DB 통합 테스트가 실증한다.)
        Company company = pendingCompany();
        stubCompany(company);

        writer.markFailed(COMPANY_ID, NtsVerificationOutcome.MISMATCH);

        assertThat(company.getVerificationStatus()).isEqualTo(BusinessVerificationStatus.FAILED);
        // 멤버십 리포지토리를 협력자로 갖지 않는다 = 어떤 멤버십 행도 건드릴 수 없다(회귀 방지 고정).
        assertThat(PendingBusinessReverifyWriter.class.getDeclaredFields())
                .noneMatch(field -> CompanyMembershipRepository.class.isAssignableFrom(field.getType()));
    }

    /**
     * P1-B 회귀 방지(#1367) — 배치가 <b>관리자 킬스위치를 조용히 덮어쓰던</b> 경로를 막는다.
     *
     * <p>배치는 회차 시작(T0)에 대상 목록을 한 번에 읽고 건별로 처리하므로, 그 사이(T1)에 관리자가
     * revoke 한 회사가 T2 에 이 메서드로 들어온다. 상태 검사가 없으면 VERIFIED 로 되살아나고, 그 회사는
     * {@code ntsOutcome=VERIFIED} 가 되어 <b>재검증 대상에서 영구 이탈 + 인증 배지 점등 + 무경보</b>가
     * 되므로 사고가 무증상으로 묻힌다. 가드를 지우면 이 테스트가 실패해야 한다.
     */
    @Test
    @DisplayName("#1367 P1-B 관리자 무효화(ADMIN_REVOKED) 회사는 배치 VERIFIED 반영을 건너뛴다 — 사람 판단 우선")
    void markVerified_관리자무효화회사는_덮어쓰지않는다() {
        Company company = pendingCompany();
        company.revokeBusinessVerificationByAdmin("사칭 신고 접수", 99L);
        stubCompany(company);

        writer.markVerified(COMPANY_ID);

        // 무효화가 그대로 유지된다(= 회사 스코프가 계속 닫혀 있다).
        assertThat(company.getVerificationStatus()).isEqualTo(BusinessVerificationStatus.FAILED);
        assertThat(ntsOutcomeOf(company)).isEqualTo("ADMIN_REVOKED");
        assertThat(company.isNtsVerified()).isFalse();
        // 건너뛴 회사도 대기열 앞자리를 고정 점유하지 않도록 시도 스탬프는 남긴다(P1-C).
        assertThat(company.ntsLastAttemptAt()).isPresent();
    }

    @Test
    @DisplayName("#1367 P1-B 잠금 없는 findById 로 읽지 않는다 — 쓰기락 없이는 관리자 조치를 덮을 수 있다")
    void markVerified_행을_잠그고읽는다() {
        Company company = pendingCompany();
        stubCompany(company);

        writer.markVerified(COMPANY_ID);

        verify(companyRepository).findByIdForUpdate(COMPANY_ID);
        verify(companyRepository, never()).findById(anyLong());
    }

    @Test
    @DisplayName("#1367 markFailed 는 override 회사도 재차단한다 — 자동 재차단이 override 의 안전장치다")
    void markFailed_override회사도_확정불량이면_재차단한다() {
        Company company = pendingCompany();
        company.overrideBusinessVerificationByAdmin("실물 확인", 55L);
        stubCompany(company);

        writer.markFailed(COMPANY_ID, NtsVerificationOutcome.CLOSED);

        // markVerified 와 달리 상태 가드를 두지 않는다 — 막으면 override 가 영구 개방이 된다.
        assertThat(company.getVerificationStatus()).isEqualTo(BusinessVerificationStatus.FAILED);
    }

    @Test
    @DisplayName("#1367 P1-C 상태를 바꾸지 않는 경로도 시도 스탬프를 남겨 대기열을 순환시킨다")
    void stampAttempt_와_stampAlert() {
        Company company = pendingCompany();
        stubCompany(company);

        writer.stampAttempt(COMPANY_ID);
        assertThat(company.ntsLastAttemptAt()).isPresent();

        writer.stampAlert(COMPANY_ID, NtsVerificationOutcome.MISMATCH);
        assertThat(company.ntsLastAlertOutcome()).contains("MISMATCH");
        // 경보 기록이 대상 집합을 바꾸면 안 된다(ntsOutcome 불변).
        assertThat(ntsOutcomeOf(company)).isEqualTo("SKIPPED");
        assertThat(company.getVerificationStatus()).isEqualTo(BusinessVerificationStatus.PENDING);
    }

    @Test
    @DisplayName("대상 회사가 조회되지 않아도(소멸) 예외 없이 무시한다")
    void 회사소멸_예외없이무시() {
        when(companyRepository.findByIdForUpdate(999L)).thenReturn(Optional.empty());

        writer.markVerified(999L);
        writer.markFailed(999L, NtsVerificationOutcome.SUSPENDED);
        // 예외 없이 통과하면 성공 — 별도 assertion 불필요(방어 동작 자체가 검증 대상).
    }
}
