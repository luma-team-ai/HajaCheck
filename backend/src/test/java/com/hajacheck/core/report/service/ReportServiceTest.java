package com.hajacheck.core.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hajacheck.core.ai.dto.ReportRequest;
import com.hajacheck.core.ai.dto.ReportResponse;
import com.hajacheck.core.ai.service.AiProxyService;
import com.hajacheck.core.defect.entity.Defect;
import com.hajacheck.core.defect.entity.DefectGrade;
import com.hajacheck.core.defect.entity.DefectStatus;
import com.hajacheck.core.defect.entity.DefectType;
import com.hajacheck.core.defect.repository.DefectRepository;
import com.hajacheck.core.facility.dto.FacilityResponse;
import com.hajacheck.core.facility.service.FacilityService;
import com.hajacheck.core.inspection.dto.InspectionResponse;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import com.hajacheck.core.inspection.service.InspectionService;
import com.hajacheck.core.report.dto.ReportDetailResponse;
import com.hajacheck.core.report.dto.ReportSummaryResponse;
import com.hajacheck.core.report.entity.Report;
import com.hajacheck.core.report.repository.ReportRepository;
import com.hajacheck.global.common.ApiResponse;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReportServiceTest {

    @Mock
    private ReportRepository reportRepository;
    @Mock
    private DefectRepository defectRepository;
    @Mock
    private InspectionService inspectionService;
    @Mock
    private FacilityService facilityService;
    @Mock
    private AiProxyService aiProxyService;

    @InjectMocks
    private ReportService reportService;

    private static InspectionResponse inspection(Long facilityId) {
        return new InspectionResponse(1L, facilityId, 100L, 100L, 1,
                LocalDate.now(), InspectionStatus.CREATED, LocalDateTime.now());
    }

    private static FacilityResponse facility() {
        return new FacilityResponse(10L, 100L, "테스트빌딩", "BUILDING", "서울시 강남구",
                null, null, null, null, null, null, LocalDateTime.now(), LocalDateTime.now(),
                List.of(), null, null, null);
    }

    private static ReportResponse aiReport() {
        return new ReportResponse(
                new ReportResponse.Overview("목적", "요약", "범위"),
                new ReportResponse.Summary("양호", 0, java.util.Map.of(), List.of()),
                new ReportResponse.Detail(List.of()),
                new ReportResponse.Recommendation(List.of(), List.of()),
                true);
    }

    /**
     * 실제 AI 서버는 요청에 실어 보낸 상관관계 값(grounding_request_id/inspection_id/report_version)을
     * 응답에 그대로 되돌려주고, content_hash는 자신이 반환한 본문 기준으로 채운다(GroundingCheckResultFactory
     * .fromAiReport()가 이 값들을 캡처된 GroundingRequestContext와 대조한다) — 목 응답도 동일 계약을 지켜야
     * fromVerifiedAiResponse()의 상관관계 검증을 통과한다.
     */
    private static ReportResponse aiReportMatching(ReportRequest request) {
        ReportResponse base = aiReport();
        String contentJson = GroundingReportContentSerializer.serialize(base);
        com.hajacheck.core.report.entity.GroundingRequestContext context =
                new com.hajacheck.core.report.entity.GroundingRequestContext(
                        request.groundingRequestId(), request.inspectionId(), request.reportVersion());
        com.hajacheck.core.report.entity.GroundingCheckTarget target =
                com.hajacheck.core.report.entity.GroundingCheckTarget.capture(context, contentJson);
        return new ReportResponse(
                base.overview(), base.summary(), base.detail(), base.recommendation(), base.groundingOk(),
                target.groundingRequestId(), target.inspectionId(), target.reportVersion(), target.contentHash());
    }

    /**
     * 슬로우-AI 커넥션 풀 고갈 회귀 방지(PR #455 P1-1) — generateDraft 는 AI 서버 동기 호출을 포함하므로
     * 트랜잭션 밖(Propagation.NOT_SUPPORTED)에서 실행되어야 한다. 클래스 기본값 @Transactional(readOnly=true)
     * 를 상속해 AI 왕복 동안 DB 커넥션을 점유하면 풀 고갈 위험이 있어, 애노테이션 자체를 리플렉션으로 확정한다.
     * (풀 통합 슬로우-AI 시나리오는 Testcontainers 의존이라 이 단위 테스트로 계약만 고정.)
     */
    @Test
    void generateDraft_트랜잭션밖실행_NOT_SUPPORTED() throws NoSuchMethodException {
        Method method = ReportService.class.getMethod("generateDraft", Long.class, Long.class);
        Transactional transactional = method.getAnnotation(Transactional.class);

        assertThat(transactional).as("generateDraft 는 @Transactional 애노테이션을 명시해야 한다").isNotNull();
        assertThat(transactional.propagation())
                .as("AI 동기 호출이 DB 커넥션을 트랜잭션에 묶지 않도록 NOT_SUPPORTED 여야 한다")
                .isEqualTo(Propagation.NOT_SUPPORTED);
    }

    @Test
    void generateDraft_확정하자_초안생성_버전1() {
        when(inspectionService.getInspection(100L, 1L)).thenReturn(inspection(10L));
        when(facilityService.get(100L, 10L)).thenReturn(facility());
        when(defectRepository.findByInspectionIdAndStatusInAndDeletedFalse(anyLong(), any()))
                .thenReturn(List.of());
        when(reportRepository.findFirstByInspectionIdOrderByVersionDesc(1L)).thenReturn(Optional.empty());
        when(aiProxyService.generateReport(anyLong(), any())).thenAnswer(inv -> ApiResponse.ok(aiReportMatching(inv.getArgument(1))));
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ReportDetailResponse response = reportService.generateDraft(1L, 100L);

        assertThat(response.version()).isEqualTo(1);
        assertThat(response.inspectionId()).isEqualTo(1L);
        assertThat(response.groundingCheckPassed()).isTrue();

        ArgumentCaptor<ReportRequest> captor = ArgumentCaptor.forClass(ReportRequest.class);
        verify(aiProxyService).generateReport(anyLong(), captor.capture());
        assertThat(captor.getValue().reportVersion()).isEqualTo(1);
        assertThat(captor.getValue().confirmedDefects()).isEmpty();
    }

    @Test
    void generateDraft_확정하자를AI요청형식으로변환() {
        when(inspectionService.getInspection(100L, 1L)).thenReturn(inspection(10L));
        when(facilityService.get(100L, 10L)).thenReturn(facility());
        Defect defect = Defect.builder()
                .inspectionId(1L)
                .type(DefectType.CRACK)
                .confidence(0.9)
                .grade(DefectGrade.C)
                .status(DefectStatus.CONFIRMED)
                .crackWidthMm(3.0)
                .crackLengthMm(20.0)
                .build();
        when(defectRepository.findByInspectionIdAndStatusInAndDeletedFalse(anyLong(), any()))
                .thenReturn(List.of(defect));
        when(reportRepository.findFirstByInspectionIdOrderByVersionDesc(1L)).thenReturn(Optional.empty());
        when(aiProxyService.generateReport(anyLong(), any())).thenAnswer(inv -> ApiResponse.ok(aiReportMatching(inv.getArgument(1))));
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        reportService.generateDraft(1L, 100L);

        ArgumentCaptor<ReportRequest> captor = ArgumentCaptor.forClass(ReportRequest.class);
        verify(aiProxyService).generateReport(anyLong(), captor.capture());
        ReportRequest.ConfirmedDefect confirmedDefect = captor.getValue().confirmedDefects().get(0);
        assertThat(confirmedDefect.defectType()).isEqualTo("균열");
        assertThat(confirmedDefect.location()).isEqualTo("서울시 강남구");
        assertThat(confirmedDefect.severityGrade()).isEqualTo("C");
    }

    @Test
    void generateDraft_기존버전이있으면다음버전으로증가() {
        when(inspectionService.getInspection(100L, 1L)).thenReturn(inspection(10L));
        when(facilityService.get(100L, 10L)).thenReturn(facility());
        when(defectRepository.findByInspectionIdAndStatusInAndDeletedFalse(anyLong(), any()))
                .thenReturn(List.of());
        Report existing = Report.draft(1L, 2, "{}", 100L);
        when(reportRepository.findFirstByInspectionIdOrderByVersionDesc(1L)).thenReturn(Optional.of(existing));
        when(aiProxyService.generateReport(anyLong(), any())).thenAnswer(inv -> ApiResponse.ok(aiReportMatching(inv.getArgument(1))));
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ReportDetailResponse response = reportService.generateDraft(1L, 100L);

        assertThat(response.version()).isEqualTo(3);
    }

    @Test
    void generateDraft_AI응답실패_REPORT_GENERATION_FAILED() {
        when(inspectionService.getInspection(100L, 1L)).thenReturn(inspection(10L));
        when(facilityService.get(100L, 10L)).thenReturn(facility());
        when(defectRepository.findByInspectionIdAndStatusInAndDeletedFalse(anyLong(), any()))
                .thenReturn(List.of());
        when(reportRepository.findFirstByInspectionIdOrderByVersionDesc(1L)).thenReturn(Optional.empty());
        when(aiProxyService.generateReport(anyLong(), any())).thenReturn(ApiResponse.fail("AI_ERR", "실패"));

        assertThatThrownBy(() -> reportService.generateDraft(1L, 100L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.REPORT_GENERATION_FAILED));
        verify(reportRepository, never()).save(any());
    }

    @Test
    void generateDraft_타인소유점검_예외전파() {
        doThrow(new BusinessException(ErrorCode.FACILITY_NOT_FOUND))
                .when(inspectionService).getInspection(999L, 1L);

        assertThatThrownBy(() -> reportService.generateDraft(1L, 999L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.FACILITY_NOT_FOUND));
        verify(aiProxyService, never()).generateReport(anyLong(), any());
    }

    @Test
    void updateContent_수정후grounding필드를null로리셋() {
        Report report = Report.draft(1L, 1, "{\"a\":1}", 100L);
        when(reportRepository.findById(5L)).thenReturn(Optional.of(report));
        when(inspectionService.getInspection(100L, 1L)).thenReturn(inspection(10L));
        report.recordGroundingResult(
                com.hajacheck.core.report.entity.GroundingCheckResultTestFactory.passed(
                        com.hajacheck.core.report.entity.GroundingCheckTarget.capture(
                                report.captureGroundingRequestContext(), report.getContentJson()),
                        null),
                100L);
        assertThat(report.getGroundingCheckPassed()).isTrue();

        ReportDetailResponse response = reportService.updateContent(5L, "{\"a\":2}", 100L);

        assertThat(response.groundingCheckPassed()).isNull();
    }

    @Test
    void updateContent_FINALIZED상태에서시도하면예외() {
        Report report = Report.draft(1L, 1, "{}", 100L);
        report.recordGroundingResult(
                com.hajacheck.core.report.entity.GroundingCheckResultTestFactory.passed(
                        com.hajacheck.core.report.entity.GroundingCheckTarget.capture(
                                report.captureGroundingRequestContext(), report.getContentJson()),
                        null),
                100L);
        report.finalizeReport("https://files.example/r.pdf", 100L);
        when(reportRepository.findById(5L)).thenReturn(Optional.of(report));
        when(inspectionService.getInspection(100L, 1L)).thenReturn(inspection(10L));

        assertThatThrownBy(() -> reportService.updateContent(5L, "{\"changed\":true}", 100L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void getReport_존재하지않으면REPORT_NOT_FOUND() {
        when(reportRepository.findById(5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reportService.getReport(5L, 100L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.REPORT_NOT_FOUND));
    }

    @Test
    void getReport_타인소유_존재하지않는id와동일하게REPORT_NOT_FOUND() {
        Report report = Report.draft(1L, 1, "{}", 100L);
        when(reportRepository.findById(5L)).thenReturn(Optional.of(report));
        doThrow(new BusinessException(ErrorCode.FACILITY_NOT_FOUND))
                .when(inspectionService).getInspection(999L, 1L);

        assertThatThrownBy(() -> reportService.getReport(5L, 999L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.REPORT_NOT_FOUND));
    }

    @Test
    void listReports_소유권검증후버전목록을최신순으로반환() {
        when(inspectionService.getInspection(100L, 1L)).thenReturn(inspection(10L));
        Report v1 = Report.draft(1L, 1, "{}", 100L);
        Report v2 = Report.draft(1L, 2, "{}", 100L);
        when(reportRepository.findByInspectionIdOrderByVersionDesc(1L)).thenReturn(List.of(v2, v1));

        List<ReportSummaryResponse> result = reportService.listReports(1L, 100L);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).version()).isEqualTo(2);
    }

    @Test
    void finalizeReport_근거검증통과후PDF와확정상태기록() {
        Report report = Report.draft(1L, 1, "{}", 100L);
        report.recordGroundingResult(
                com.hajacheck.core.report.entity.GroundingCheckResultTestFactory.passed(
                        com.hajacheck.core.report.entity.GroundingCheckTarget.capture(
                                report.captureGroundingRequestContext(), report.getContentJson()),
                        null),
                100L);
        when(reportRepository.findById(5L)).thenReturn(Optional.of(report));
        when(inspectionService.getInspection(100L, 1L)).thenReturn(inspection(10L));

        ReportDetailResponse response = reportService.finalizeReport(5L, "/api/reports/5/pdf/r.pdf", 100L);

        assertThat(response.status()).isEqualTo(com.hajacheck.core.report.entity.ReportStatus.FINALIZED);
        assertThat(response.pdfUrl()).isEqualTo("/api/reports/5/pdf/r.pdf");
    }

    @Test
    void finalizeReport_다른보고서용pdfUrl이면REPORT_PDF_URL_INVALID() {
        Report report = Report.draft(1L, 1, "{}", 100L);
        report.recordGroundingResult(
                com.hajacheck.core.report.entity.GroundingCheckResultTestFactory.passed(
                        com.hajacheck.core.report.entity.GroundingCheckTarget.capture(
                                report.captureGroundingRequestContext(), report.getContentJson()),
                        null),
                100L);
        when(reportRepository.findById(5L)).thenReturn(Optional.of(report));
        when(inspectionService.getInspection(100L, 1L)).thenReturn(inspection(10L));

        assertThatThrownBy(() -> reportService.finalizeReport(5L, "/api/reports/999/pdf/r.pdf", 100L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.REPORT_PDF_URL_INVALID);
    }

    @Test
    void finalizeReport_임의문자열pdfUrl이면REPORT_PDF_URL_INVALID() {
        Report report = Report.draft(1L, 1, "{}", 100L);
        report.recordGroundingResult(
                com.hajacheck.core.report.entity.GroundingCheckResultTestFactory.passed(
                        com.hajacheck.core.report.entity.GroundingCheckTarget.capture(
                                report.captureGroundingRequestContext(), report.getContentJson()),
                        null),
                100L);
        when(reportRepository.findById(5L)).thenReturn(Optional.of(report));
        when(inspectionService.getInspection(100L, 1L)).thenReturn(inspection(10L));

        assertThatThrownBy(() -> reportService.finalizeReport(5L, "https://evil.example/r.pdf", 100L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.REPORT_PDF_URL_INVALID);
    }
}
