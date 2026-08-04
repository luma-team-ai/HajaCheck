package com.hajacheck.core.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.service.CompanyScopeGuard;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.UserRepository;
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
import com.hajacheck.core.inspection.entity.InspectionType;
import com.hajacheck.core.inspection.service.InspectionService;
import com.hajacheck.core.media.entity.Media;
import com.hajacheck.core.media.entity.MediaFileType;
import com.hajacheck.core.media.repository.MediaRepository;
import com.hajacheck.core.report.dto.ReportDetailResponse;
import com.hajacheck.core.report.dto.ReportSummaryResponse;
import com.hajacheck.core.report.entity.Report;
import com.hajacheck.core.report.repository.ReportRepository;
import com.hajacheck.global.common.ApiResponse;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.DomainValidationException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.core.report.support.ReportPdfStorage;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
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
    @Mock
    private CompanyScopeGuard companyScopeGuard;
    @Mock
    private ReportPdfStorage reportPdfStorage;
    @Mock
    private CompanyRepository companyRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private MediaRepository mediaRepository;

    @InjectMocks
    private ReportService reportService;

    private static InspectionResponse inspection(Long facilityId) {
        return new InspectionResponse(1L, facilityId, 100L, 100L, 1,
                LocalDate.now(), InspectionType.REGULAR, InspectionStatus.CREATED, LocalDateTime.now());
    }

    private static InspectionResponse inspection(Long facilityId, InspectionStatus status) {
        return new InspectionResponse(1L, facilityId, 100L, 100L, 1,
                LocalDate.now(), InspectionType.REGULAR, status, LocalDateTime.now());
    }

    private static FacilityResponse facility() {
        return facility("서울시 강남구");
    }

    private static FacilityResponse facility(String address) {
        return new FacilityResponse(10L, "테스트빌딩", "BUILDING", address,
                null, null, null, null, null, null, LocalDateTime.now(), LocalDateTime.now(),
                null, null, null, null, null, null);
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
        Method method = ReportService.class.getMethod(
                "generateDraft", Long.class, Long.class, Long.class);
        Transactional transactional = method.getAnnotation(Transactional.class);

        assertThat(transactional).as("generateDraft 는 @Transactional 애노테이션을 명시해야 한다").isNotNull();
        assertThat(transactional.propagation())
                .as("AI 동기 호출이 DB 커넥션을 트랜잭션에 묶지 않도록 NOT_SUPPORTED 여야 한다")
                .isEqualTo(Propagation.NOT_SUPPORTED);
    }

    @Test
    void generateDraft_확정하자_초안생성_버전1() {
        when(inspectionService.getInspection(200L, 100L, 1L)).thenReturn(inspection(10L));
        when(facilityService.get(200L, 100L, 10L)).thenReturn(facility());
        when(defectRepository.findByInspectionIdAndStatusInAndDeletedFalse(anyLong(), any()))
                .thenReturn(List.of());
        when(reportRepository.findFirstByInspectionIdOrderByVersionDesc(1L)).thenReturn(Optional.empty());
        when(aiProxyService.generateReport(anyLong(), any())).thenAnswer(inv -> ApiResponse.ok(aiReportMatching(inv.getArgument(1))));
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ReportDetailResponse response = reportService.generateDraft(1L, 100L, 200L);

        assertThat(response.version()).isEqualTo(1);
        assertThat(response.inspectionId()).isEqualTo(1L);
        assertThat(response.groundingCheckPassed()).isTrue();
        verify(companyScopeGuard).requireEffectiveMembership(200L, 100L);
        verify(inspectionService).getInspection(200L, 100L, 1L);
        verify(facilityService).get(200L, 100L, 10L);

        ArgumentCaptor<ReportRequest> captor = ArgumentCaptor.forClass(ReportRequest.class);
        verify(aiProxyService).generateReport(anyLong(), captor.capture());
        assertThat(captor.getValue().reportVersion()).isEqualTo(1);
        assertThat(captor.getValue().confirmedDefects()).isEmpty();
    }

    @Test
    void generateDraft_선택옵션을저장Content에반영한다() {
        when(inspectionService.getInspection(200L, 100L, 1L)).thenReturn(inspection(10L));
        when(facilityService.get(200L, 100L, 10L)).thenReturn(facility());
        when(defectRepository.findByInspectionIdAndStatusInAndDeletedFalse(anyLong(), any()))
                .thenReturn(List.of());
        when(reportRepository.findFirstByInspectionIdOrderByVersionDesc(1L)).thenReturn(Optional.empty());
        when(aiProxyService.generateReport(anyLong(), any())).thenAnswer(inv -> ApiResponse.ok(aiReportMatching(inv.getArgument(1))));
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ReportDetailResponse response = reportService.generateDraft(
                1L, 100L, 200L, Set.of("overview", "summary"), false);

        assertThat(response.groundingCheckPassed()).isTrue();
        assertThat(response.content().get("overview").get("purpose").asText()).isNotBlank();
        assertThat(response.content().get("summary").get("overall_opinion").asText()).isNotBlank();
        assertThat(response.content().get("summary").get("total_count").asInt()).isEqualTo(0);
        assertThat(response.content().get("detail").get("items")).isEmpty();
        assertThat(response.content().get("recommendation").get("items")).isEmpty();
        assertThat(response.content().get("reportOptions").get("includePhoto").asBoolean()).isFalse();
        assertThat(response.content().get("reportOptions").get("sections").toString())
                .contains("overview", "summary")
                .doesNotContain("opinion");
    }

    @Test
    void generateDraft_기본옵션을받아도grounding통과상태를유지한다() {
        when(inspectionService.getInspection(200L, 100L, 1L)).thenReturn(inspection(10L));
        when(facilityService.get(200L, 100L, 10L)).thenReturn(facility());
        when(defectRepository.findByInspectionIdAndStatusInAndDeletedFalse(anyLong(), any()))
                .thenReturn(List.of());
        when(reportRepository.findFirstByInspectionIdOrderByVersionDesc(1L)).thenReturn(Optional.empty());
        when(aiProxyService.generateReport(anyLong(), any())).thenAnswer(inv -> ApiResponse.ok(aiReportMatching(inv.getArgument(1))));
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ReportDetailResponse response = reportService.generateDraft(
                1L, 100L, 200L, Set.of("overview", "summary", "details", "recommendation"), true);

        assertThat(response.groundingCheckPassed()).isTrue();
        assertThat(response.content().get("reportOptions").get("includePhoto").asBoolean()).isTrue();
    }

    @Test
    void generateDraft_빈섹션은저장하지않고거부한다() {
        when(inspectionService.getInspection(200L, 100L, 1L)).thenReturn(inspection(10L));
        when(facilityService.get(200L, 100L, 10L)).thenReturn(facility());
        when(defectRepository.findByInspectionIdAndStatusInAndDeletedFalse(anyLong(), any()))
                .thenReturn(List.of());
        when(reportRepository.findFirstByInspectionIdOrderByVersionDesc(1L)).thenReturn(Optional.empty());
        when(aiProxyService.generateReport(anyLong(), any())).thenAnswer(inv -> ApiResponse.ok(aiReportMatching(inv.getArgument(1))));

        assertThatThrownBy(() -> reportService.generateDraft(1L, 100L, 200L, Set.of(), true))
                .isInstanceOf(DomainValidationException.class)
                .hasMessageContaining("최소 1개 섹션");
        verify(reportRepository, never()).save(any());
    }

    @Test
    void generateDraft_확정하자를AI요청형식으로변환() {
        when(inspectionService.getInspection(200L, 100L, 1L)).thenReturn(inspection(10L));
        when(facilityService.get(200L, 100L, 10L)).thenReturn(facility());
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

        reportService.generateDraft(1L, 100L, 200L);

        ArgumentCaptor<ReportRequest> captor = ArgumentCaptor.forClass(ReportRequest.class);
        verify(aiProxyService).generateReport(anyLong(), captor.capture());
        ReportRequest.ConfirmedDefect confirmedDefect = captor.getValue().confirmedDefects().get(0);
        assertThat(confirmedDefect.defectType()).isEqualTo("균열");
        assertThat(confirmedDefect.location()).isEqualTo("서울시 강남구");
        assertThat(confirmedDefect.severityGrade()).isEqualTo("C");
    }

    @Test
    void generateDraft_기존버전이있으면다음버전으로증가() {
        when(inspectionService.getInspection(200L, 100L, 1L)).thenReturn(inspection(10L));
        when(facilityService.get(200L, 100L, 10L)).thenReturn(facility());
        when(defectRepository.findByInspectionIdAndStatusInAndDeletedFalse(anyLong(), any()))
                .thenReturn(List.of());
        Report existing = Report.draft(1L, 2, "{}", 100L);
        when(reportRepository.findFirstByInspectionIdOrderByVersionDesc(1L)).thenReturn(Optional.of(existing));
        when(aiProxyService.generateReport(anyLong(), any())).thenAnswer(inv -> ApiResponse.ok(aiReportMatching(inv.getArgument(1))));
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ReportDetailResponse response = reportService.generateDraft(1L, 100L, 200L);

        assertThat(response.version()).isEqualTo(3);
    }

    @Test
    void generateDraft_AI응답실패_REPORT_GENERATION_FAILED() {
        when(inspectionService.getInspection(200L, 100L, 1L)).thenReturn(inspection(10L));
        when(facilityService.get(200L, 100L, 10L)).thenReturn(facility());
        when(defectRepository.findByInspectionIdAndStatusInAndDeletedFalse(anyLong(), any()))
                .thenReturn(List.of());
        when(reportRepository.findFirstByInspectionIdOrderByVersionDesc(1L)).thenReturn(Optional.empty());
        when(aiProxyService.generateReport(anyLong(), any())).thenReturn(ApiResponse.fail("AI_ERR", "실패"));

        assertThatThrownBy(() -> reportService.generateDraft(1L, 100L, 200L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.REPORT_GENERATION_FAILED));
        verify(reportRepository, never()).save(any());
    }

    @Test
    void generateDraft_타인소유점검_예외전파() {
        doThrow(new BusinessException(ErrorCode.FACILITY_NOT_FOUND))
                .when(inspectionService).getInspection(200L, 999L, 1L);

        assertThatThrownBy(() -> reportService.generateDraft(1L, 999L, 200L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.FACILITY_NOT_FOUND));
        verify(aiProxyService, never()).generateReport(anyLong(), any());
    }

    // #1479 — 시설물 주소가 비어 있으면 ai-server 호출(422) 전에 명확한 사유로 사전 차단해야 한다.
    // "AI 서버가 요청을 거부했습니다"라는 애매한 AI_REQUEST_REJECTED 대신 FACILITY_ADDRESS_MISSING이어야 한다.
    @Test
    void generateDraft_시설물주소없음_FACILITY_ADDRESS_MISSING() {
        when(inspectionService.getInspection(200L, 100L, 1L)).thenReturn(inspection(10L));
        when(facilityService.get(200L, 100L, 10L)).thenReturn(facility(""));

        assertThatThrownBy(() -> reportService.generateDraft(1L, 100L, 200L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.FACILITY_ADDRESS_MISSING));
        verifyNoInteractions(aiProxyService);
        verify(reportRepository, never()).save(any());
    }

    // 공백만 있는 주소도 blank로 취급해 동일하게 차단되는지 확인.
    @Test
    void generateDraft_시설물주소공백_FACILITY_ADDRESS_MISSING() {
        when(inspectionService.getInspection(200L, 100L, 1L)).thenReturn(inspection(10L));
        when(facilityService.get(200L, 100L, 10L)).thenReturn(facility("   "));

        assertThatThrownBy(() -> reportService.generateDraft(1L, 100L, 200L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.FACILITY_ADDRESS_MISSING));
        verifyNoInteractions(aiProxyService);
    }

    @Test
    void updateContent_수정후grounding필드를null로리셋() {
        Report report = Report.draft(1L, 1, "{\"a\":1}", 100L);
        when(reportRepository.findById(5L)).thenReturn(Optional.of(report));
        when(inspectionService.getInspection(200L, 100L, 1L)).thenReturn(inspection(10L));
        report.recordGroundingResult(
                com.hajacheck.core.report.entity.GroundingCheckResultTestFactory.passed(
                        com.hajacheck.core.report.entity.GroundingCheckTarget.capture(
                                report.captureGroundingRequestContext(), report.getContentJson()),
                        null),
                100L);
        assertThat(report.getGroundingCheckPassed()).isTrue();

        ReportDetailResponse response = reportService.updateContent(5L, "{\"a\":2}", 100L, 200L);

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
        when(inspectionService.getInspection(200L, 100L, 1L)).thenReturn(inspection(10L));

        assertThatThrownBy(() -> reportService.updateContent(5L, "{\"changed\":true}", 100L, 200L))
                .isInstanceOf(IllegalStateException.class);
    }

    private static Defect confirmedDefect(DefectType type, DefectGrade grade) {
        return Defect.builder()
                .inspectionId(1L)
                .type(type)
                .confidence(0.9)
                .grade(grade)
                .status(DefectStatus.CONFIRMED)
                .build();
    }

    private static String contentJsonWithDetailItems(String... typeGradePairs) {
        List<ReportResponse.DetailItem> items = new java.util.ArrayList<>();
        for (int i = 0; i < typeGradePairs.length; i += 2) {
            items.add(new ReportResponse.DetailItem(
                    null, typeGradePairs[i], "위치", typeGradePairs[i + 1], "설명", "원인"));
        }
        ReportResponse aiReport = new ReportResponse(
                new ReportResponse.Overview("목적", "요약", "범위"),
                new ReportResponse.Summary("양호", items.size(), java.util.Map.of(), List.of()),
                new ReportResponse.Detail(items),
                new ReportResponse.Recommendation(List.of(), List.of()),
                true);
        return GroundingReportContentSerializer.serialize(aiReport);
    }

    private static String contentJsonWithoutDetailsSection() {
        ReportResponse aiReport = new ReportResponse(
                new ReportResponse.Overview("목적", "요약", "범위"),
                new ReportResponse.Summary("양호", 1, java.util.Map.of("C", 1), List.of("균열 발견")),
                new ReportResponse.Detail(List.of(
                        new ReportResponse.DetailItem(null, "균열", "위치", "C", "설명", "원인"))),
                new ReportResponse.Recommendation(List.of(), List.of()),
                true);
        return GroundingReportContentSerializer.serialize(
                aiReport, Set.of("overview", "summary", "recommendation"), true);
    }

    private static String contentJsonWithForgedOptionsAndDetailItems(String... typeGradePairs) {
        String contentJson = contentJsonWithDetailItems(typeGradePairs);
        return contentJson.substring(0, contentJson.length() - 1)
                + ",\"reportOptions\":{\"sections\":[\"overview\",\"summary\",\"recommendation\"],\"includePhoto\":true}}";
    }

    @Test
    void recheckGrounding_유형등급일치_grounding통과로기록() {
        Report report = Report.draft(1L, 1, contentJsonWithDetailItems("균열", "C"), 100L);
        when(reportRepository.findById(5L)).thenReturn(Optional.of(report));
        when(inspectionService.getInspection(100L, 500L, 1L)).thenReturn(inspection(10L));
        when(defectRepository.findByInspectionIdAndStatusInAndDeletedFalse(anyLong(), any()))
                .thenReturn(List.of(confirmedDefect(DefectType.CRACK, DefectGrade.C)));

        ReportDetailResponse response = reportService.recheckGrounding(5L, 500L, 100L);

        assertThat(response.groundingCheckPassed()).isTrue();
        assertThat(report.getGroundingWarnings()).isEqualTo("[]");
        verify(defectRepository, times(1)).findByInspectionIdAndStatusInAndDeletedFalse(anyLong(), any());
    }

    @Test
    void recheckGrounding_details섹션제외보고서는상세비교를건너뛰고확정가능하다() {
        Report report = Report.draft(1L, 1, contentJsonWithoutDetailsSection(), 100L);
        when(reportRepository.findById(5L)).thenReturn(Optional.of(report));
        when(inspectionService.getInspection(100L, 500L, 1L)).thenReturn(inspection(10L));
        when(inspectionService.getInspection(200L, 500L, 1L)).thenReturn(inspection(10L));
        when(defectRepository.findByInspectionIdAndStatusInAndDeletedFalse(anyLong(), any()))
                .thenReturn(List.of(confirmedDefect(DefectType.CRACK, DefectGrade.C)));

        ReportDetailResponse recheckResponse = reportService.recheckGrounding(5L, 500L, 100L);
        ReportDetailResponse finalizeResponse = reportService.finalizeReport(
                5L, "/api/reports/5/pdf/r.pdf", 500L, 200L);

        assertThat(recheckResponse.groundingCheckPassed()).isTrue();
        assertThat(finalizeResponse.status()).isEqualTo(com.hajacheck.core.report.entity.ReportStatus.FINALIZED);
    }

    @Test
    void updateContent_details제외로생성된보고서의빈상세옵션은보존한다() {
        Report report = Report.draft(1L, 1, contentJsonWithoutDetailsSection(), 100L);
        when(reportRepository.findById(5L)).thenReturn(Optional.of(report));
        when(inspectionService.getInspection(200L, 500L, 1L)).thenReturn(inspection(10L));
        when(inspectionService.getInspection(100L, 500L, 1L)).thenReturn(inspection(10L));
        when(defectRepository.findByInspectionIdAndStatusInAndDeletedFalse(anyLong(), any()))
                .thenReturn(List.of(confirmedDefect(DefectType.CRACK, DefectGrade.C)));

        ReportDetailResponse updateResponse = reportService.updateContent(
                5L, contentJsonWithoutDetailsSection(), 500L, 200L);
        ReportDetailResponse recheckResponse = reportService.recheckGrounding(5L, 500L, 100L);

        assertThat(updateResponse.content().has("reportOptions")).isTrue();
        assertThat(recheckResponse.groundingCheckPassed()).isTrue();
    }

    @Test
    void recheckGrounding_reportOptions를조작해도detailItems가있으면실제하자와비교한다() {
        Report report = Report.draft(
                1L, 1, contentJsonWithForgedOptionsAndDetailItems("박리·박락", "B"), 100L);
        when(reportRepository.findById(5L)).thenReturn(Optional.of(report));
        when(inspectionService.getInspection(100L, 500L, 1L)).thenReturn(inspection(10L));
        when(inspectionService.getInspection(200L, 500L, 1L)).thenReturn(inspection(10L));
        when(defectRepository.findByInspectionIdAndStatusInAndDeletedFalse(anyLong(), any()))
                .thenReturn(List.of(confirmedDefect(DefectType.CRACK, DefectGrade.C)));

        ReportDetailResponse recheckResponse = reportService.recheckGrounding(5L, 500L, 100L);

        assertThat(recheckResponse.groundingCheckPassed()).isFalse();
        assertThatThrownBy(() -> reportService.finalizeReport(5L, "/api/reports/5/pdf/r.pdf", 500L, 200L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("근거 검증");
    }

    @Test
    void recheckGrounding_details포함생성후Patch로상세를비우면불일치로판정한다() {
        Report report = Report.draft(1L, 1, contentJsonWithDetailItems("균열", "C"), 100L);
        when(reportRepository.findById(5L)).thenReturn(Optional.of(report));
        when(inspectionService.getInspection(200L, 500L, 1L)).thenReturn(inspection(10L));
        when(inspectionService.getInspection(100L, 500L, 1L)).thenReturn(inspection(10L));
        when(defectRepository.findByInspectionIdAndStatusInAndDeletedFalse(anyLong(), any()))
                .thenReturn(List.of(confirmedDefect(DefectType.CRACK, DefectGrade.C)));

        ReportDetailResponse updateResponse = reportService.updateContent(
                5L, contentJsonWithoutDetailsSection(), 500L, 200L);
        ReportDetailResponse recheckResponse = reportService.recheckGrounding(5L, 500L, 100L);

        assertThat(updateResponse.content().has("reportOptions")).isFalse();
        assertThat(recheckResponse.groundingCheckPassed()).isFalse();
        assertThatThrownBy(() -> reportService.finalizeReport(5L, "/api/reports/5/pdf/r.pdf", 500L, 200L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("근거 검증");
    }

    @Test
    void recheckGrounding_순서가달라도멀티셋일치하면통과() {
        Report report = Report.draft(1L, 1, contentJsonWithDetailItems("박리·박락", "B", "균열", "C"), 100L);
        when(reportRepository.findById(5L)).thenReturn(Optional.of(report));
        when(inspectionService.getInspection(100L, 500L, 1L)).thenReturn(inspection(10L));
        when(defectRepository.findByInspectionIdAndStatusInAndDeletedFalse(anyLong(), any()))
                .thenReturn(List.of(
                        confirmedDefect(DefectType.CRACK, DefectGrade.C),
                        confirmedDefect(DefectType.SPALLING, DefectGrade.B)));

        ReportDetailResponse response = reportService.recheckGrounding(5L, 500L, 100L);

        assertThat(response.groundingCheckPassed()).isTrue();
    }

    @Test
    void recheckGrounding_등급만달라도불일치() {
        Report report = Report.draft(1L, 1, contentJsonWithDetailItems("균열", "B"), 100L);
        when(reportRepository.findById(5L)).thenReturn(Optional.of(report));
        when(inspectionService.getInspection(100L, 500L, 1L)).thenReturn(inspection(10L));
        when(defectRepository.findByInspectionIdAndStatusInAndDeletedFalse(anyLong(), any()))
                .thenReturn(List.of(confirmedDefect(DefectType.CRACK, DefectGrade.C)));

        ReportDetailResponse response = reportService.recheckGrounding(5L, 500L, 100L);

        assertThat(response.groundingCheckPassed()).isFalse();
        assertThat(report.getGroundingWarnings()).contains("일치하지 않습니다");
    }

    @Test
    void recheckGrounding_유형만달라도불일치() {
        Report report = Report.draft(1L, 1, contentJsonWithDetailItems("박리·박락", "C"), 100L);
        when(reportRepository.findById(5L)).thenReturn(Optional.of(report));
        when(inspectionService.getInspection(100L, 500L, 1L)).thenReturn(inspection(10L));
        when(defectRepository.findByInspectionIdAndStatusInAndDeletedFalse(anyLong(), any()))
                .thenReturn(List.of(confirmedDefect(DefectType.CRACK, DefectGrade.C)));

        ReportDetailResponse response = reportService.recheckGrounding(5L, 500L, 100L);

        assertThat(response.groundingCheckPassed()).isFalse();
    }

    @Test
    void recheckGrounding_개수가달라도불일치() {
        Report report = Report.draft(1L, 1, contentJsonWithDetailItems("균열", "C"), 100L);
        when(reportRepository.findById(5L)).thenReturn(Optional.of(report));
        when(inspectionService.getInspection(100L, 500L, 1L)).thenReturn(inspection(10L));
        when(defectRepository.findByInspectionIdAndStatusInAndDeletedFalse(anyLong(), any()))
                .thenReturn(List.of(
                        confirmedDefect(DefectType.CRACK, DefectGrade.C),
                        confirmedDefect(DefectType.SPALLING, DefectGrade.B)));

        ReportDetailResponse response = reportService.recheckGrounding(5L, 500L, 100L);

        assertThat(response.groundingCheckPassed()).isFalse();
    }

    @Test
    void recheckGrounding_타인소유_REPORT_NOT_FOUND() {
        Report report = Report.draft(1L, 1, contentJsonWithDetailItems("균열", "C"), 100L);
        when(reportRepository.findById(5L)).thenReturn(Optional.of(report));
        doThrow(new BusinessException(ErrorCode.FACILITY_NOT_FOUND))
                .when(inspectionService).getInspection(999L, 500L, 1L);

        assertThatThrownBy(() -> reportService.recheckGrounding(5L, 500L, 999L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.REPORT_NOT_FOUND));
        verify(defectRepository, never()).findByInspectionIdAndStatusInAndDeletedFalse(anyLong(), any());
    }

    @Test
    void recheckGrounding_FINALIZED상태에서시도하면예외() {
        Report report = Report.draft(1L, 1, contentJsonWithDetailItems("균열", "C"), 100L);
        report.recordGroundingResult(
                com.hajacheck.core.report.entity.GroundingCheckResultTestFactory.passed(
                        com.hajacheck.core.report.entity.GroundingCheckTarget.capture(
                                report.captureGroundingRequestContext(), report.getContentJson()),
                        null),
                100L);
        report.finalizeReport("/api/reports/5/pdf/r.pdf", 100L);
        when(reportRepository.findById(5L)).thenReturn(Optional.of(report));
        when(inspectionService.getInspection(100L, 500L, 1L)).thenReturn(inspection(10L));

        assertThatThrownBy(() -> reportService.recheckGrounding(5L, 500L, 100L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void getReport_존재하지않으면REPORT_NOT_FOUND() {
        when(reportRepository.findById(5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reportService.getReport(5L, 200L, 100L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.REPORT_NOT_FOUND));
    }

    @Test
    void getReport_타인소유_존재하지않는id와동일하게REPORT_NOT_FOUND() {
        Report report = Report.draft(1L, 1, "{}", 100L);
        when(reportRepository.findById(5L)).thenReturn(Optional.of(report));
        doThrow(new BusinessException(ErrorCode.FACILITY_NOT_FOUND))
                .when(inspectionService).getInspection(200L, 999L, 1L);

        assertThatThrownBy(() -> reportService.getReport(5L, 200L, 999L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.REPORT_NOT_FOUND));
    }

    @Test
    void cloneReport_원본content를다음버전DRAFT로복제하고검증필드는초기화() {
        Report source = Report.draft(1L, 2, "{\"overview\":{\"purpose\":\"copy\"}}", 100L);
        source.recordGroundingResult(
                com.hajacheck.core.report.entity.GroundingCheckResultTestFactory.passed(
                        com.hajacheck.core.report.entity.GroundingCheckTarget.capture(
                                source.captureGroundingRequestContext(), source.getContentJson()),
                        null),
                100L);
        source.finalizeReport("/api/reports/5/pdf/source.pdf", 100L);
        when(reportRepository.findById(5L)).thenReturn(Optional.of(source));
        when(inspectionService.getInspection(200L, 100L, 1L)).thenReturn(inspection(10L));
        when(reportRepository.findFirstByInspectionIdOrderByVersionDesc(1L)).thenReturn(Optional.of(source));
        when(reportRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        ReportDetailResponse response = reportService.cloneReport(5L, 100L, 200L);

        assertThat(response.inspectionId()).isEqualTo(1L);
        assertThat(response.version()).isEqualTo(3);
        assertThat(response.status()).isEqualTo(com.hajacheck.core.report.entity.ReportStatus.DRAFT);
        assertThat(response.content().path("overview").path("purpose").asText()).isEqualTo("copy");
        assertThat(response.groundingCheckPassed()).isNull();
        assertThat(response.pdfUrl()).isNull();

        ArgumentCaptor<Report> captor = ArgumentCaptor.forClass(Report.class);
        verify(reportRepository).saveAndFlush(captor.capture());
        Report clone = captor.getValue();
        assertThat(clone.getCreatedBy()).isEqualTo(200L);
        assertThat(clone.getEditedBy()).isNull();
        assertThat(clone.getGroundingWarnings()).isNull();
    }

    @Test
    void cloneReport_타인소유_REPORT_NOT_FOUND() {
        Report report = Report.draft(1L, 1, "{}", 100L);
        when(reportRepository.findById(5L)).thenReturn(Optional.of(report));
        doThrow(new BusinessException(ErrorCode.FACILITY_NOT_FOUND))
                .when(inspectionService).getInspection(200L, 999L, 1L);

        assertThatThrownBy(() -> reportService.cloneReport(5L, 999L, 200L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.REPORT_NOT_FOUND));
        verify(reportRepository, never()).saveAndFlush(any());
        verifyNoInteractions(defectRepository, aiProxyService);
    }

    @Test
    void deleteDraftReport_DRAFT보고서를softDelete한다() {
        Report report = Report.draft(1L, 1, "{}", 100L);
        when(reportRepository.findById(5L)).thenReturn(Optional.of(report));
        when(inspectionService.getInspection(200L, 100L, 1L)).thenReturn(inspection(10L));

        reportService.deleteDraftReport(5L, 100L, 200L);

        assertThat(report.getDeletedAt()).isNotNull();
        assertThat(report.getEditedBy()).isEqualTo(200L);
    }

    @Test
    void deleteDraftReport_FINALIZED보고서는INVALID_STATE_TRANSITION() {
        Report report = Report.draft(1L, 1, "{}", 100L);
        report.recordGroundingResult(
                com.hajacheck.core.report.entity.GroundingCheckResultTestFactory.passed(
                        com.hajacheck.core.report.entity.GroundingCheckTarget.capture(
                                report.captureGroundingRequestContext(), report.getContentJson()),
                        null),
                100L);
        report.finalizeReport("/api/reports/5/pdf/r.pdf", 100L);
        when(reportRepository.findById(5L)).thenReturn(Optional.of(report));
        when(inspectionService.getInspection(200L, 100L, 1L)).thenReturn(inspection(10L));

        assertThatThrownBy(() -> reportService.deleteDraftReport(5L, 100L, 200L))
                .isInstanceOf(IllegalStateException.class);
        assertThat(report.getDeletedAt()).isNull();
    }

    @Test
    void getReport_softDeleted보고서는REPORT_NOT_FOUND() {
        Report report = Report.draft(1L, 1, "{}", 100L);
        report.markDeleted(100L);
        when(reportRepository.findById(5L)).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> reportService.getReport(5L, 200L, 100L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.REPORT_NOT_FOUND));
        verify(inspectionService, never()).getInspection(anyLong(), anyLong(), anyLong());
    }

    @Test
    void getReport_기존데이터로보고서Context를함께반환한다() {
        Report report = Report.draft(1L, 1, "{}", 100L);
        Defect defect = Defect.builder()
                .inspectionId(1L)
                .mediaId(9L)
                .type(DefectType.CRACK)
                .confidence(0.92)
                .grade(DefectGrade.C)
                .status(DefectStatus.CONFIRMED)
                .location("교량 하부 익명 위치")
                .crackWidthMm(0.3)
                .crackLengthMm(1200.0)
                .build();
        Media media = Media.builder()
                .inspectionId(1L)
                .fileType(MediaFileType.IMAGE)
                .originalUrl("stored-original")
                .thumbnailUrl("stored-thumbnail")
                .detailUrl("stored-detail")
                .mimeSignatureVerified(true)
                .build();
        when(reportRepository.findById(5L)).thenReturn(Optional.of(report));
        when(inspectionService.getInspection(200L, 100L, 1L)).thenReturn(inspection(10L));
        when(facilityService.get(200L, 100L, 10L)).thenReturn(facility());
        when(defectRepository.findByInspectionIdAndStatusInAndDeletedFalse(1L, List.of(
                DefectStatus.CONFIRMED, DefectStatus.IN_PROGRESS, DefectStatus.RESOLVED)))
                .thenReturn(List.of(defect));
        when(mediaRepository.findByInspectionIdOrderByIdAsc(1L)).thenReturn(List.of(media));

        ReportDetailResponse response = reportService.getReport(5L, 200L, 100L);

        assertThat(response.context()).isNotNull();
        assertThat(response.context().facility().name()).isEqualTo("테스트빌딩");
        assertThat(response.context().inspection().roundNo()).isEqualTo(1);
        assertThat(response.context().defects()).hasSize(1);
        assertThat(response.context().defects().get(0).typeLabel()).isEqualTo("균열");
        assertThat(response.context().defects().get(0).location()).isEqualTo("교량 하부 익명 위치");
        assertThat(response.context().media()).hasSize(1);
        assertThat(response.context().media().get(0).thumbnailUrl()).isNull();
    }

    @Test
    void listReports_소유권검증후버전목록을최신순으로반환() {
        when(inspectionService.getInspection(200L, 100L, 1L)).thenReturn(inspection(10L));
        Report v1 = Report.draft(1L, 1, "{}", 100L);
        Report v2 = Report.draft(1L, 2, "{}", 100L);
        when(reportRepository.findByInspectionIdAndDeletedAtIsNullOrderByVersionDesc(1L)).thenReturn(List.of(v2, v1));
        User author = User.builder().name("김기준").build();
        ReflectionTestUtils.setField(author, "id", 100L);
        when(userRepository.findAllById(List.of(100L))).thenReturn(List.of(author));

        List<ReportSummaryResponse> result = reportService.listReports(1L, 200L, 100L);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).version()).isEqualTo(2);
        // 작성자 조회가 배선돼 있어야 프론트가 "알 수 없음"으로 폴백하지 않는다.
        assertThat(result.get(0).createdByName()).isEqualTo("김기준");
        assertThat(result.get(1).createdByName()).isEqualTo("김기준");
    }

    @Test
    void listReports_작성자를찾을수없으면createdByName이null이다() {
        when(inspectionService.getInspection(200L, 100L, 1L)).thenReturn(inspection(10L));
        Report v1 = Report.draft(1L, 1, "{}", 999L);
        when(reportRepository.findByInspectionIdAndDeletedAtIsNullOrderByVersionDesc(1L)).thenReturn(List.of(v1));
        when(userRepository.findAllById(List.of(999L))).thenReturn(List.of());

        List<ReportSummaryResponse> result = reportService.listReports(1L, 200L, 100L);

        assertThat(result.get(0).createdByName()).isNull();
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
        when(inspectionService.getInspection(200L, 100L, 1L)).thenReturn(inspection(10L));

        ReportDetailResponse response = reportService.finalizeReport(5L, "/api/reports/5/pdf/r.pdf", 100L, 200L);

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
        when(inspectionService.getInspection(200L, 100L, 1L)).thenReturn(inspection(10L));

        assertThatThrownBy(() -> reportService.finalizeReport(5L, "/api/reports/999/pdf/r.pdf", 100L, 200L))
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
        when(inspectionService.getInspection(200L, 100L, 1L)).thenReturn(inspection(10L));

        assertThatThrownBy(() -> reportService.finalizeReport(5L, "https://evil.example/r.pdf", 100L, 200L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.REPORT_PDF_URL_INVALID);
    }

    @Test
    void finalizeReport_업로드되지않은storageKey로finalize시도시거부됨() {
        Report report = Report.draft(1L, 1, "{}", 100L);
        report.recordGroundingResult(
                com.hajacheck.core.report.entity.GroundingCheckResultTestFactory.passed(
                        com.hajacheck.core.report.entity.GroundingCheckTarget.capture(
                                report.captureGroundingRequestContext(), report.getContentJson()),
                        null),
                100L);
        when(reportRepository.findById(5L)).thenReturn(Optional.of(report));
        when(inspectionService.getInspection(200L, 100L, 1L)).thenReturn(inspection(10L));
        doThrow(new BusinessException(ErrorCode.FILE_NOT_FOUND))
                .when(reportPdfStorage).load(5L, "nonexistent.pdf");

        assertThatThrownBy(() -> reportService.finalizeReport(5L, "/api/reports/5/pdf/nonexistent.pdf", 100L, 200L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FILE_NOT_FOUND);
    }

    // ── 보고서 확정 시 회차 완료 전이(팀 테스트 피드백, 2026-08-01) ──
    // REVIEWED/REPORTED 둘 다 상태 머신엔 도착 상태로 정의돼 있었지만 실제로 전이시키는 코드가
    // 없어, 검수를 끝내고 보고서까지 만들어도 회차가 ANALYZED에 영원히 머물던 문제.

    @Test
    void finalizeReport_회차가ANALYZED면_REVIEWED거쳐REPORTED로전이한다() {
        Report report = Report.draft(1L, 1, "{}", 100L);
        report.recordGroundingResult(
                com.hajacheck.core.report.entity.GroundingCheckResultTestFactory.passed(
                        com.hajacheck.core.report.entity.GroundingCheckTarget.capture(
                                report.captureGroundingRequestContext(), report.getContentJson()),
                        null),
                100L);
        when(reportRepository.findById(5L)).thenReturn(Optional.of(report));
        when(inspectionService.getInspection(200L, 100L, 1L))
                .thenReturn(inspection(10L, InspectionStatus.ANALYZED));

        reportService.finalizeReport(5L, "/api/reports/5/pdf/r.pdf", 100L, 200L);

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(inspectionService);
        inOrder.verify(inspectionService).advanceStatus(200L, 100L, 1L, InspectionStatus.REVIEWED);
        inOrder.verify(inspectionService).advanceStatus(200L, 100L, 1L, InspectionStatus.REPORTED);
    }

    @Test
    void finalizeReport_회차가REVIEWED면_REVIEWED전이없이바로REPORTED로전이한다() {
        Report report = Report.draft(1L, 1, "{}", 100L);
        report.recordGroundingResult(
                com.hajacheck.core.report.entity.GroundingCheckResultTestFactory.passed(
                        com.hajacheck.core.report.entity.GroundingCheckTarget.capture(
                                report.captureGroundingRequestContext(), report.getContentJson()),
                        null),
                100L);
        when(reportRepository.findById(5L)).thenReturn(Optional.of(report));
        when(inspectionService.getInspection(200L, 100L, 1L))
                .thenReturn(inspection(10L, InspectionStatus.REVIEWED));

        reportService.finalizeReport(5L, "/api/reports/5/pdf/r.pdf", 100L, 200L);

        verify(inspectionService, never()).advanceStatus(200L, 100L, 1L, InspectionStatus.REVIEWED);
        verify(inspectionService).advanceStatus(200L, 100L, 1L, InspectionStatus.REPORTED);
    }

    @Test
    void finalizeReport_회차가이미REPORTED면_전이를시도하지않는다() {
        // REPORTED는 상태 머신상 종단(더 이상 어디로도 전이 불가)이라, 같은 회차의 다른 보고서
        // 버전을 재확정하는 경우 재전이를 시도하면 DomainStateTransitionException이 난다.
        Report report = Report.draft(1L, 2, "{}", 100L);
        report.recordGroundingResult(
                com.hajacheck.core.report.entity.GroundingCheckResultTestFactory.passed(
                        com.hajacheck.core.report.entity.GroundingCheckTarget.capture(
                                report.captureGroundingRequestContext(), report.getContentJson()),
                        null),
                100L);
        when(reportRepository.findById(5L)).thenReturn(Optional.of(report));
        when(inspectionService.getInspection(200L, 100L, 1L))
                .thenReturn(inspection(10L, InspectionStatus.REPORTED));

        reportService.finalizeReport(5L, "/api/reports/5/pdf/r.pdf", 100L, 200L);

        verify(inspectionService, never()).advanceStatus(any(), any(), any(), any());
    }

    @Test
    void finalizeReport_회차가CREATED_UPLOADING_ANALYZING이면_상태전이없이확정만성공한다() {
        // PR머신 리뷰 P1 — generateDraft()가 회차 상태를 전혀 검증하지 않아(확정 하자 0건이어도
        // 초안 생성 허용) 이 세 상태에서도 finalize 호출이 실제로 도달 가능하다. REPORTED로 가는
        // 허용 전이 소스가 아닌 상태에서 무조건 전이를 시도하면 DomainStateTransitionException으로
        // finalize 트랜잭션 전체가 롤백된다 — 보고서 확정 자체는 이 상태들에서도 항상 성공해야 한다.
        InspectionStatus[] statuses = {
            InspectionStatus.CREATED, InspectionStatus.UPLOADING, InspectionStatus.ANALYZING,
        };
        for (int i = 0; i < statuses.length; i++) {
            long reportId = 50L + i;
            Report report = Report.draft(1L, 1, "{}", 100L);
            report.recordGroundingResult(
                    com.hajacheck.core.report.entity.GroundingCheckResultTestFactory.passed(
                            com.hajacheck.core.report.entity.GroundingCheckTarget.capture(
                                    report.captureGroundingRequestContext(), report.getContentJson()),
                            null),
                    100L);
            when(reportRepository.findById(reportId)).thenReturn(Optional.of(report));
            when(inspectionService.getInspection(200L, 100L, 1L)).thenReturn(inspection(10L, statuses[i]));

            ReportDetailResponse response = reportService.finalizeReport(
                    reportId, "/api/reports/" + reportId + "/pdf/r.pdf", 100L, 200L);

            assertThat(response.status()).isEqualTo(com.hajacheck.core.report.entity.ReportStatus.FINALIZED);
        }
        verify(inspectionService, never()).advanceStatus(any(), any(), any(), any());
    }

    @Test
    void getReport_무소속사용자_FORBIDDEN을404로변환하지않는다() {
        doThrow(new BusinessException(ErrorCode.FORBIDDEN))
                .when(companyScopeGuard).requireEffectiveMembership(200L, null);

        assertThatThrownBy(() -> reportService.getReport(5L, 200L, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.FORBIDDEN));
        verify(reportRepository, never()).findById(anyLong());
    }

    @Test
    void getReport_검증중FORBIDDEN도404로변환하지않는다() {
        Report report = Report.draft(1L, 1, "{}", 200L);
        when(reportRepository.findById(5L)).thenReturn(Optional.of(report));
        doThrow(new BusinessException(ErrorCode.FORBIDDEN))
                .when(inspectionService).getInspection(200L, 100L, 1L);

        assertThatThrownBy(() -> reportService.getReport(5L, 200L, 100L))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.FORBIDDEN));
    }
}
