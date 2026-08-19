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

import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.bizverify.config.PendingBusinessReverifyProperties;
import com.hajacheck.bizverify.service.NtsBusinessVerifyClient;
import com.hajacheck.bizverify.service.NtsVerificationOutcome;
import com.hajacheck.demo.service.DemoSeedService;
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
        lenient().when(company.getBusinessRegistrationOcrRaw()).thenReturn("{\"source\":\"MANUAL_INPUT\"}");
        return company;
    }

    /** BRN + provenance 표식이 모두 데모 시드 상수와 일치하는 "진짜 데모 회사" 목(이중 판정 양쪽 충족). */
    private Company demoCompany(long id) {
        Company company = pendingCompany(id, DemoSeedService.DEMO_BUSINESS_NUMBER, "데모 관리자");
        lenient().when(company.getBusinessRegistrationOcrRaw())
                .thenReturn("{\"" + DemoSeedService.DEMO_SEED_PROVENANCE_FIELD + "\":\""
                        + DemoSeedService.DEMO_SEED_PROVENANCE_SOURCE + "\"}");
        return company;
    }

    private void stubTargets(List<Company> targets) {
        when(companyRepository.findNtsReverifyTargets(any(Pageable.class))).thenReturn(targets);
    }

    @Test
    @DisplayName("enabled=false면 대상 조회·국세청 호출 없이 즉시 반환한다")
    void 킬스위치_꺼져있으면_호출없음() {
        properties.setEnabled(false);

        scheduler.reverifyPendingCompanies();

        verify(companyRepository, never()).findNtsReverifyTargets(any());
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
        verify(writer, never()).markFailed(anyLong(), any());
    }

    /**
     * #1367 정책 분리 — 이 테스트는 원래 4종(MISMATCH·SUSPENDED·CLOSED·NOT_REGISTERED)을 모두
     * {@code markFailed} 로 단언했으나, MISMATCH·SUSPENDED 단언을 "강등되지 않음"으로 뒤집었다.
     *
     * <p><b>왜 바뀌었나</b>: 대표자 변경·계절 휴업·비영리 고유번호증은 사기가 아니라 정상 사업 변동인데
     * 옛 정책은 그것을 무통보 서비스 중단으로 처리했다. 실제로 학회 회사 1건이 MISMATCH 로 자동 강등돼
     * 6일간 전 API 가 차단됐고, FAILED 는 재검증 대상에서 영구 제외돼 자가치유도 되지 않아 수동 SQL 로
     * 복구해야 했다. 자동 강등은 <b>확정 불량</b>(실재하지 않음·폐업)으로 좁히고, 사칭 대응은 사람 판단
     * (플랫폼 관리자 revoke API)이 담당한다.
     */
    @Test
    @DisplayName("#1367 폐업/미등록(확정 불량)만 판정 outcome과 함께 writer.markFailed로 FAILED 전이된다")
    void 폐업_미등록만_FAILED_반영() {
        Company closed = pendingCompany(3L, "3333333333", "C");
        Company notRegistered = pendingCompany(4L, "4444444444", "D");
        stubTargets(List.of(closed, notRegistered));
        when(ntsBusinessVerifyClient.verifyRealtime(eq("3333333333"), anyString(), any()))
                .thenReturn(NtsVerificationOutcome.CLOSED);
        when(ntsBusinessVerifyClient.verifyRealtime(eq("4444444444"), anyString(), any()))
                .thenReturn(NtsVerificationOutcome.NOT_REGISTERED);

        scheduler.reverifyPendingCompanies();

        verify(writer).markFailed(3L, NtsVerificationOutcome.CLOSED);
        verify(writer).markFailed(4L, NtsVerificationOutcome.NOT_REGISTERED);
        verify(writer, never()).markVerified(anyLong());
    }

    @Test
    @DisplayName("#1367 불일치/휴업은 자동 강등하지 않는다 — writer를 전혀 호출하지 않고 상태를 그대로 둔다")
    void 불일치_휴업은_강등되지않는다() {
        Company mismatch = pendingCompany(1L, "1111111111", "A");
        Company suspended = pendingCompany(2L, "2222222222", "B");
        stubTargets(List.of(mismatch, suspended));
        when(ntsBusinessVerifyClient.verifyRealtime(eq("1111111111"), anyString(), any()))
                .thenReturn(NtsVerificationOutcome.MISMATCH);
        when(ntsBusinessVerifyClient.verifyRealtime(eq("2222222222"), anyString(), any()))
                .thenReturn(NtsVerificationOutcome.SUSPENDED);

        scheduler.reverifyPendingCompanies();

        // 강등도 승격도 없다 = 회사 상태 무변경(다음 회차에 다시 잡혀 경보가 반복된다 — 의도된 동작).
        verify(writer, never()).markFailed(anyLong(), any());
        verify(writer, never()).markVerified(anyLong());
        // 엔티티 상태를 직접 바꾸는 경로가 없다는 것도 함께 고정한다(스케줄러는 writer 를 통해서만 쓴다).
        verify(mismatch, never()).markBusinessVerificationFailed();
        verify(suspended, never()).markBusinessVerificationFailed();
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
        verify(writer, never()).markFailed(anyLong(), any());
    }

    @Test
    @DisplayName("대상 선정(개업일자·provenance 조건) 자체는 리포지토리 쿼리에 위임된다")
    void 대상선정은_리포지토리조회조건으로위임() {
        // 리포지토리가 이미 businessStartDate IS NOT NULL + provenance 조건으로 필터링해 반환하므로
        // (findNtsReverifyTargets 계약 — 실제 조건 검증은 CompanyRepositoryTest),
        // 스케줄러는 빈 목록을 받으면 국세청을 전혀 호출하지 않는다는 것만 검증한다.
        stubTargets(List.of());

        scheduler.reverifyPendingCompanies();

        verify(ntsBusinessVerifyClient, never()).verifyRealtime(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("회차당 최대 처리 건수(Pageable)를 리포지토리 조회에 전달하고, 정렬은 SQL에 맡겨 Sort를 싣지 않는다")
    void 최대처리건수_Pageable로전달() {
        properties.setMaxBatchSize(5);
        stubTargets(List.of());

        scheduler.reverifyPendingCompanies();

        // 네이티브 쿼리는 동적 정렬(Sort)을 보장하지 않으므로 order by 는 SQL 에 고정돼 있다.
        verify(companyRepository).findNtsReverifyTargets(
                argThat((Pageable p) -> p.getPageSize() == 5 && p.getSort().isUnsorted()));
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
        verify(writer, never()).markFailed(eq(1L), any());
    }

    @Test
    @DisplayName("#1648 데모 시드 회사는 국세청 호출·writer 갱신 없이 스킵되고, 비데모 회사는 기존대로 처리된다")
    void 데모_회사는_국세청_호출없이_스킵되고_비데모_회사는_그대로_처리된다() {
        Company demo = demoCompany(1L);
        Company normal = pendingCompany(2L, "1234567890", "김민수");
        stubTargets(List.of(demo, normal));
        when(ntsBusinessVerifyClient.verifyRealtime(eq("1234567890"), anyString(), any()))
                .thenReturn(NtsVerificationOutcome.VERIFIED);

        scheduler.reverifyPendingCompanies();

        // 데모 회사는 국세청 클라이언트를 아예 타지 않는다(실존 불가 BRN이라 호출하면 항상 확정 불량).
        verify(ntsBusinessVerifyClient, never())
                .verifyRealtime(eq(DemoSeedService.DEMO_BUSINESS_NUMBER), anyString(), any());
        verify(writer, never()).markVerified(1L);
        verify(writer, never()).markFailed(eq(1L), any());
        // 비데모 회사는 기존대로 국세청 호출·VERIFIED 반영이 이뤄진다.
        verify(ntsBusinessVerifyClient).verifyRealtime(eq("1234567890"), anyString(), any());
        verify(writer).markVerified(2L);
    }

    @Test
    @DisplayName("#1648 BRN·provenance 이중 판정 — 한쪽만 일치하면 데모로 인정하지 않고 정상 처리한다")
    void 이중판정_한쪽만_일치하면_스킵하지_않는다() {
        // BRN은 데모 상수와 일치하지만 provenance 표식이 없다(실사용 회사가 우연히 같은 BRN을 못 쓰는
        // 상수값이지만, 이중 판정 자체를 고정하는 회귀 테스트로 남긴다).
        Company brnOnly = pendingCompany(1L, DemoSeedService.DEMO_BUSINESS_NUMBER, "A");
        // provenance 표식은 있지만 BRN이 데모 상수와 다르다(실사용 회사에 표식만 잘못 심긴 경우 방어).
        Company markerOnly = pendingCompany(2L, "5555555555", "B");
        when(markerOnly.getBusinessRegistrationOcrRaw())
                .thenReturn("{\"" + DemoSeedService.DEMO_SEED_PROVENANCE_FIELD + "\":\""
                        + DemoSeedService.DEMO_SEED_PROVENANCE_SOURCE + "\"}");
        stubTargets(List.of(brnOnly, markerOnly));
        when(ntsBusinessVerifyClient.verifyRealtime(anyString(), anyString(), any()))
                .thenReturn(NtsVerificationOutcome.VERIFIED);

        scheduler.reverifyPendingCompanies();

        // 둘 다 데모로 인정되지 않아 정상적으로 국세청 호출·VERIFIED 반영된다.
        verify(ntsBusinessVerifyClient).verifyRealtime(eq(DemoSeedService.DEMO_BUSINESS_NUMBER), anyString(), any());
        verify(ntsBusinessVerifyClient).verifyRealtime(eq("5555555555"), anyString(), any());
        verify(writer).markVerified(1L);
        verify(writer).markVerified(2L);
    }
}
