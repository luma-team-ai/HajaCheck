package com.hajacheck.bizverify.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hajacheck.auth.entity.BusinessVerificationStatus;
import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.bizverify.config.PendingBusinessReverifyProperties;
import com.hajacheck.bizverify.service.NtsBusinessVerifyClient;
import com.hajacheck.bizverify.service.NtsVerificationOutcome;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

/**
 * PendingBusinessReverifyScheduler 단위테스트(#888). InspectionDueNotificationSchedulerTest 와 같이
 * 협력자는 Mockito mock 을 직접 생성자 주입하고(@InjectMocks 미사용), Company 는 mock 으로 대체해
 * getter 만 스텁한다.
 */
class PendingBusinessReverifySchedulerTest {

    private static final LocalDate START = LocalDate.of(2020, 1, 1);

    private CompanyRepository companyRepository;
    private NtsBusinessVerifyClient ntsBusinessVerifyClient;
    private PendingBusinessReverifyWriter writer;
    private PendingBusinessReverifyProperties properties;
    private PendingBusinessReverifyScheduler scheduler;

    @BeforeEach
    void setUp() {
        companyRepository = mock(CompanyRepository.class);
        ntsBusinessVerifyClient = mock(NtsBusinessVerifyClient.class);
        writer = mock(PendingBusinessReverifyWriter.class);
        properties = new PendingBusinessReverifyProperties();
        scheduler = new PendingBusinessReverifyScheduler(
                companyRepository, ntsBusinessVerifyClient, writer, properties);
    }

    private Company pendingCompany(long id, String brn, String rep) {
        Company company = mock(Company.class);
        lenient().when(company.getId()).thenReturn(id);
        lenient().when(company.getBusinessRegistrationNumber()).thenReturn(brn);
        lenient().when(company.getRepresentativeName()).thenReturn(rep);
        lenient().when(company.getBusinessStartDate()).thenReturn(START);
        return company;
    }

    private void stubTargets(List<Company> targets) {
        when(companyRepository.findByVerificationStatusAndBusinessStartDateIsNotNull(
                eq(BusinessVerificationStatus.PENDING), any(Pageable.class)))
                .thenReturn(targets);
    }

    @Test
    @DisplayName("enabled=false면 대상 조회·국세청 호출 없이 즉시 반환한다")
    void 킬스위치_꺼져있으면_호출없음() {
        properties.setEnabled(false);

        scheduler.reverifyPendingCompanies();

        verify(companyRepository, never())
                .findByVerificationStatusAndBusinessStartDateIsNotNull(any(), any());
        verify(ntsBusinessVerifyClient, never()).verifyRealtime(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("VERIFIED 결과는 writer.markVerified로 반영된다")
    void VERIFIED_반영() {
        Company company = pendingCompany(1L, "1234567890", "김민수");
        stubTargets(List.of(company));
        when(ntsBusinessVerifyClient.verifyRealtime(anyString(), anyString(), any()))
                .thenReturn(NtsVerificationOutcome.VERIFIED);

        scheduler.reverifyPendingCompanies();

        verify(writer).markVerified(1L);
        verify(writer, never()).markFailed(anyLong());
    }

    @Test
    @DisplayName("불일치/휴업/폐업/미등록 결과는 writer.markFailed로 FAILED 전이된다")
    void 불일치_휴업_폐업_미등록_FAILED_반영() {
        Company mismatch = pendingCompany(1L, "1111111111", "A");
        Company suspended = pendingCompany(2L, "2222222222", "B");
        Company closed = pendingCompany(3L, "3333333333", "C");
        Company notRegistered = pendingCompany(4L, "4444444444", "D");
        stubTargets(List.of(mismatch, suspended, closed, notRegistered));
        when(ntsBusinessVerifyClient.verifyRealtime(eq("1111111111"), anyString(), any()))
                .thenReturn(NtsVerificationOutcome.MISMATCH);
        when(ntsBusinessVerifyClient.verifyRealtime(eq("2222222222"), anyString(), any()))
                .thenReturn(NtsVerificationOutcome.SUSPENDED);
        when(ntsBusinessVerifyClient.verifyRealtime(eq("3333333333"), anyString(), any()))
                .thenReturn(NtsVerificationOutcome.CLOSED);
        when(ntsBusinessVerifyClient.verifyRealtime(eq("4444444444"), anyString(), any()))
                .thenReturn(NtsVerificationOutcome.NOT_REGISTERED);

        scheduler.reverifyPendingCompanies();

        verify(writer).markFailed(1L);
        verify(writer).markFailed(2L);
        verify(writer).markFailed(3L);
        verify(writer).markFailed(4L);
        verify(writer, never()).markVerified(anyLong());
    }

    @Test
    @DisplayName("SKIPPED(국세청 장애)면 아무 갱신도 하지 않고 PENDING을 유지한다")
    void SKIPPED_갱신없음_PENDING유지() {
        Company company = pendingCompany(1L, "1234567890", "김민수");
        stubTargets(List.of(company));
        when(ntsBusinessVerifyClient.verifyRealtime(anyString(), anyString(), any()))
                .thenReturn(NtsVerificationOutcome.SKIPPED);

        scheduler.reverifyPendingCompanies();

        verify(writer, never()).markVerified(anyLong());
        verify(writer, never()).markFailed(anyLong());
    }

    @Test
    @DisplayName("businessStartDate가 없는 회사를 제외하는 필터링은 리포지토리 쿼리에 위임된다")
    void businessStartDate없는회사는_리포지토리조회조건으로제외() {
        // 리포지토리가 이미 businessStartDate IS NOT NULL 로 필터링해 반환하므로(쿼리 메서드 계약),
        // 스케줄러는 빈 목록을 받으면 국세청을 전혀 호출하지 않는다는 것만 검증한다.
        stubTargets(List.of());

        scheduler.reverifyPendingCompanies();

        verify(ntsBusinessVerifyClient, never()).verifyRealtime(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("회차당 최대 처리 건수(Pageable)를 리포지토리 조회에 전달한다")
    void 최대처리건수_Pageable로전달() {
        properties.setMaxBatchSize(5);
        stubTargets(List.of());

        scheduler.reverifyPendingCompanies();

        verify(companyRepository).findByVerificationStatusAndBusinessStartDateIsNotNull(
                eq(BusinessVerificationStatus.PENDING),
                argThat((Pageable p) -> p.getPageSize() == 5));
    }

    @Test
    @DisplayName("1건에서 국세청 호출이 예외를 던져도 나머지 회사 처리는 계속된다")
    void 한건실패_나머지계속처리() {
        Company failing = pendingCompany(1L, "1111111111", "A");
        Company ok = pendingCompany(2L, "2222222222", "B");
        stubTargets(List.of(failing, ok));
        when(ntsBusinessVerifyClient.verifyRealtime(eq("1111111111"), anyString(), any()))
                .thenThrow(new RuntimeException("네트워크 오류"));
        when(ntsBusinessVerifyClient.verifyRealtime(eq("2222222222"), anyString(), any()))
                .thenReturn(NtsVerificationOutcome.VERIFIED);

        scheduler.reverifyPendingCompanies();

        verify(writer).markVerified(2L);
        verify(writer, never()).markFailed(1L);
    }
}
