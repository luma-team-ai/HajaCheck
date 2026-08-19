package com.hajacheck.core.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
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
import com.hajacheck.core.media.entity.MediaPurpose;
import com.hajacheck.core.media.repository.MediaRepository;
import com.hajacheck.core.report.dto.ReportDefectSyncResponse;
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
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
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
    @Mock
    private ReportFinalizationValidator reportFinalizationValidator;

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

    /** #1702 리뷰 P1 — 회차 스냅샷 시점 검증용(같은 점검이 서로 다른 회차로 관측되는 상황 모형화). */
    private static InspectionResponse inspectionWithRound(Long facilityId, int roundNo) {
        return new InspectionResponse(1L, facilityId, 100L, 100L, roundNo,
                LocalDate.now(), InspectionType.REGULAR, InspectionStatus.CREATED, LocalDateTime.now());
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
        // #1702 리뷰 P1 — 초입 1회 + persist 직전 회차 재조회 1회. AI 왕복 중 회차가 재정렬될 수 있어
        // 스냅샷은 반드시 INSERT 직전 값으로 찍는다(generateDraft_AI왕복중회차가밀리면... 참고).
        verify(inspectionService, times(2)).getInspection(200L, 100L, 1L);
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
        assertThat(confirmedDefect.location()).isEqualTo("위치 미입력");
        assertThat(confirmedDefect.severityGrade()).isEqualTo("C");
    }

    @Test
    void 보고서_생성시_하자별_실제위치를_AI요청에_전달한다() {
        when(inspectionService.getInspection(200L, 100L, 1L)).thenReturn(inspection(10L));
        when(facilityService.get(200L, 100L, 10L)).thenReturn(facility());
        Defect defect1 = Defect.builder()
                .inspectionId(1L)
                .type(DefectType.CRACK)
                .confidence(0.9)
                .grade(DefectGrade.C)
                .status(DefectStatus.CONFIRMED)
                .location("지하 1층 동측 기둥")
                .build();
        Defect defect2 = Defect.builder()
                .inspectionId(1L)
                .type(DefectType.SPALLING)
                .confidence(0.8)
                .grade(DefectGrade.B)
                .status(DefectStatus.CONFIRMED)
                .location("옥상 난간 남측")
                .build();
        Defect defect3 = Defect.builder()
                .inspectionId(1L)
                .type(DefectType.LEAK_EFFLORESCENCE)
                .confidence(0.8)
                .status(DefectStatus.CONFIRMED)
                .build();

        when(defectRepository.findByInspectionIdAndStatusInAndDeletedFalse(anyLong(), any()))
                .thenReturn(List.of(defect1, defect2, defect3));
        when(reportRepository.findFirstByInspectionIdOrderByVersionDesc(1L)).thenReturn(Optional.empty());
        when(aiProxyService.generateReport(anyLong(), any())).thenAnswer(inv -> ApiResponse.ok(aiReportMatching(inv.getArgument(1))));
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        reportService.generateDraft(1L, 100L, 200L);

        ArgumentCaptor<ReportRequest> captor = ArgumentCaptor.forClass(ReportRequest.class);
        verify(aiProxyService).generateReport(anyLong(), captor.capture());
        List<ReportRequest.ConfirmedDefect> confirmedDefects = captor.getValue().confirmedDefects();
        assertThat(confirmedDefects.get(0).location()).isEqualTo("지하 1층 동측 기둥");
        assertThat(confirmedDefects.get(1).location()).isEqualTo("옥상 난간 남측");
        assertThat(confirmedDefects.get(2).location()).isEqualTo("위치 미입력");
    }

    @Test
    void generateDraft_기존버전이있으면다음버전으로증가() {
        when(inspectionService.getInspection(200L, 100L, 1L)).thenReturn(inspection(10L));
        when(facilityService.get(200L, 100L, 10L)).thenReturn(facility());
        when(defectRepository.findByInspectionIdAndStatusInAndDeletedFalse(anyLong(), any()))
                .thenReturn(List.of());
        Report existing = Report.draft(1L, 1, 2, "{}", 100L);
        when(reportRepository.findFirstByInspectionIdOrderByVersionDesc(1L)).thenReturn(Optional.of(existing));
        when(aiProxyService.generateReport(anyLong(), any())).thenAnswer(inv -> ApiResponse.ok(aiReportMatching(inv.getArgument(1))));
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ReportDetailResponse response = reportService.generateDraft(1L, 100L, 200L);

        assertThat(response.version()).isEqualTo(3);
    }

    @Test
    void generateDraft_버전채번경합시1회재시도후성공() {
        // #1653 P3 — nextVersion() 조회와 INSERT 사이에 동시 요청이 끼어들어 uk_reports_inspection_version
        // 유니크 제약을 위반하면, 이미 grounding 검증을 마친 이 인스턴스를 버리지 않고 새로 배정된
        // 버전으로 1회 재시도한다(AI 재호출 없음 — aiProxyService는 1회만 호출돼야 한다).
        when(inspectionService.getInspection(200L, 100L, 1L)).thenReturn(inspection(10L));
        when(facilityService.get(200L, 100L, 10L)).thenReturn(facility());
        when(defectRepository.findByInspectionIdAndStatusInAndDeletedFalse(anyLong(), any()))
                .thenReturn(List.of());
        Report concurrentWinner = Report.draft(1L, 1, 1, "{}", 999L);
        when(reportRepository.findFirstByInspectionIdOrderByVersionDesc(1L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(concurrentWinner));
        when(aiProxyService.generateReport(anyLong(), any()))
                .thenAnswer(inv -> ApiResponse.ok(aiReportMatching(inv.getArgument(1))));
        when(reportRepository.save(any()))
                .thenThrow(new DataIntegrityViolationException("dup",
                        new ConstraintViolationException(
                                "dup", new SQLException("dup"), "uk_reports_inspection_version")))
                .thenAnswer(inv -> inv.getArgument(0));

        ReportDetailResponse response = reportService.generateDraft(1L, 100L, 200L);

        assertThat(response.version()).isEqualTo(2);
        assertThat(response.groundingCheckPassed()).isTrue();
        verify(reportRepository, times(2)).save(any());
        verify(aiProxyService, times(1)).generateReport(anyLong(), any());
    }

    @Test
    void generateDraft_버전채번경합이아닌무결성위반은그대로전파() {
        when(inspectionService.getInspection(200L, 100L, 1L)).thenReturn(inspection(10L));
        when(facilityService.get(200L, 100L, 10L)).thenReturn(facility());
        when(defectRepository.findByInspectionIdAndStatusInAndDeletedFalse(anyLong(), any()))
                .thenReturn(List.of());
        when(reportRepository.findFirstByInspectionIdOrderByVersionDesc(1L)).thenReturn(Optional.empty());
        when(aiProxyService.generateReport(anyLong(), any()))
                .thenAnswer(inv -> ApiResponse.ok(aiReportMatching(inv.getArgument(1))));
        DataIntegrityViolationException other = new DataIntegrityViolationException("other",
                new ConstraintViolationException("other", new SQLException("other"), "some_other_constraint"));
        when(reportRepository.save(any())).thenThrow(other);

        assertThatThrownBy(() -> reportService.generateDraft(1L, 100L, 200L))
                .isSameAs(other);
        verify(reportRepository, times(1)).save(any());
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
        Report report = Report.draft(1L, 1, 1, "{\"a\":1}", 100L);
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
        Report report = Report.draft(1L, 1, 1, "{}", 100L);
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

    @Test
    void listCompanyReports_등급분포는확정하자상태로필터링된신규메서드를사용한다() {
        // #1653 P2 — 목록 등급 분포가 status 무관 전체를 세는 옛 메서드(countGroupByInspectionIdAndGrade,
        // DETECTED 포함)를 그대로 쓰면 안 된다. 보고서에 실제로 실리는 확정 하자만 세는 신규 메서드로
        // 전환됐는지 배선을 확인한다(실제 status 필터 동작 자체는 DefectRepositoryTest에서 검증).
        // CompanyReportListItemResponse.from()이 report.getInspection().getFacility()를 직접 읽으므로
        // (실제 쿼리는 join fetch로 채움) 단위 테스트에서는 그 관계를 리플렉션으로 직접 채운다
        // (MyInspectionsServiceTest의 기존 패턴과 동일).
        com.hajacheck.core.facility.entity.Facility facility =
                com.hajacheck.core.facility.entity.Facility.builder()
                        .companyId(100L).name("테스트빌딩").type("BUILDING").build();
        com.hajacheck.core.inspection.entity.Inspection inspection =
                com.hajacheck.core.inspection.entity.Inspection.builder()
                        .facilityId(10L).createdBy(200L).assignedInspectorId(200L).roundNo(1)
                        .inspectionDate(LocalDate.now()).status(InspectionStatus.ANALYZED).build();
        ReflectionTestUtils.setField(inspection, "id", 1L);
        ReflectionTestUtils.setField(inspection, "facility", facility);
        Report report = Report.draft(1L, 1, 1, "{}", 100L);
        ReflectionTestUtils.setField(report, "inspection", inspection);
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(0, 10);
        org.springframework.data.domain.Page<Report> page =
                new org.springframework.data.domain.PageImpl<>(List.of(report), pageable, 1);
        when(reportRepository.findCompanyPage(eq(100L), any(), anyLong(), anyInt(), any(), any(), eq(pageable)))
                .thenReturn(page);
        when(defectRepository.countGroupByInspectionIdAndGradeAndStatusIn(any(), any())).thenReturn(List.of());

        reportService.listCompanyReports(200L, 100L, null, null, null, "", "ALL", pageable);

        verify(defectRepository).countGroupByInspectionIdAndGradeAndStatusIn(
                List.of(1L), List.of(DefectStatus.CONFIRMED, DefectStatus.IN_PROGRESS, DefectStatus.RESOLVED));
        verify(defectRepository, never()).countGroupByInspectionIdAndGrade(any());
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
        Report report = Report.draft(1L, 1, 1, contentJsonWithDetailItems("균열", "C"), 100L);
        when(reportRepository.findById(5L)).thenReturn(Optional.of(report));
        when(inspectionService.getInspection(100L, 500L, 1L)).thenReturn(inspection(10L));
        when(defectRepository.findByInspectionIdAndStatusInAndDeletedFalse(anyLong(), any()))
                .thenReturn(List.of(confirmedDefect(DefectType.CRACK, DefectGrade.C)));

        ReportDefectSyncResponse response = reportService.recheckGrounding(5L, 500L, 100L);

        assertThat(response.groundingCheckPassed()).isTrue();
        assertThat(report.getGroundingWarnings()).isEqualTo("[]");
        verify(defectRepository, times(1)).findByInspectionIdAndStatusInAndDeletedFalse(anyLong(), any());
    }

    @Test
    void recheckGrounding_details섹션제외보고서는상세비교를건너뛰고확정가능하다() {
        Report report = Report.draft(1L, 1, 1, contentJsonWithoutDetailsSection(), 100L);
        when(reportRepository.findById(5L)).thenReturn(Optional.of(report));
        when(inspectionService.getInspection(100L, 500L, 1L)).thenReturn(inspection(10L));
        when(inspectionService.getInspection(200L, 500L, 1L)).thenReturn(inspection(10L));
        when(defectRepository.findByInspectionIdAndStatusInAndDeletedFalse(anyLong(), any()))
                .thenReturn(List.of(confirmedDefect(DefectType.CRACK, DefectGrade.C)));

        ReportDefectSyncResponse recheckResponse = reportService.recheckGrounding(5L, 500L, 100L);
        ReportDetailResponse finalizeResponse = reportService.finalizeReport(
                5L, "/api/reports/5/pdf/r.pdf", 500L, 200L);

        assertThat(recheckResponse.groundingCheckPassed()).isTrue();
        assertThat(finalizeResponse.status()).isEqualTo(com.hajacheck.core.report.entity.ReportStatus.FINALIZED);
    }

    @Test
    void updateContent_details제외로생성된보고서의빈상세옵션은보존한다() {
        Report report = Report.draft(1L, 1, 1, contentJsonWithoutDetailsSection(), 100L);
        when(reportRepository.findById(5L)).thenReturn(Optional.of(report));
        when(inspectionService.getInspection(200L, 500L, 1L)).thenReturn(inspection(10L));
        when(inspectionService.getInspection(100L, 500L, 1L)).thenReturn(inspection(10L));
        when(defectRepository.findByInspectionIdAndStatusInAndDeletedFalse(anyLong(), any()))
                .thenReturn(List.of(confirmedDefect(DefectType.CRACK, DefectGrade.C)));

        ReportDetailResponse updateResponse = reportService.updateContent(
                5L, contentJsonWithoutDetailsSection(), 500L, 200L);
        ReportDefectSyncResponse recheckResponse = reportService.recheckGrounding(5L, 500L, 100L);

        assertThat(updateResponse.content().has("reportOptions")).isTrue();
        assertThat(recheckResponse.groundingCheckPassed()).isTrue();
    }

    @Test
    void recheckGrounding_reportOptions를조작해도detailItems가있으면실제하자와비교한다() {
        Report report = Report.draft(
                1L, 1, 1, contentJsonWithForgedOptionsAndDetailItems("박리·박락", "B"), 100L);
        when(reportRepository.findById(5L)).thenReturn(Optional.of(report));
        when(inspectionService.getInspection(100L, 500L, 1L)).thenReturn(inspection(10L));
        when(inspectionService.getInspection(200L, 500L, 1L)).thenReturn(inspection(10L));
        when(defectRepository.findByInspectionIdAndStatusInAndDeletedFalse(anyLong(), any()))
                .thenReturn(List.of(confirmedDefect(DefectType.CRACK, DefectGrade.C)));

        ReportDefectSyncResponse recheckResponse = reportService.recheckGrounding(5L, 500L, 100L);

        assertThat(recheckResponse.groundingCheckPassed()).isFalse();
        assertThatThrownBy(() -> reportService.finalizeReport(5L, "/api/reports/5/pdf/r.pdf", 500L, 200L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("근거 검증");
    }

    @Test
    void recheckGrounding_details포함생성후Patch로상세를비우면불일치로판정한다() {
        Report report = Report.draft(1L, 1, 1, contentJsonWithDetailItems("균열", "C"), 100L);
        when(reportRepository.findById(5L)).thenReturn(Optional.of(report));
        when(inspectionService.getInspection(200L, 500L, 1L)).thenReturn(inspection(10L));
        when(inspectionService.getInspection(100L, 500L, 1L)).thenReturn(inspection(10L));
        when(defectRepository.findByInspectionIdAndStatusInAndDeletedFalse(anyLong(), any()))
                .thenReturn(List.of(confirmedDefect(DefectType.CRACK, DefectGrade.C)));

        ReportDetailResponse updateResponse = reportService.updateContent(
                5L, contentJsonWithoutDetailsSection(), 500L, 200L);
        ReportDefectSyncResponse recheckResponse = reportService.recheckGrounding(5L, 500L, 100L);

        assertThat(updateResponse.content().has("reportOptions")).isFalse();
        assertThat(recheckResponse.groundingCheckPassed()).isFalse();
        assertThatThrownBy(() -> reportService.finalizeReport(5L, "/api/reports/5/pdf/r.pdf", 500L, 200L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("근거 검증");
    }

    @Test
    void recheckGrounding_순서가달라도멀티셋일치하면통과() {
        Report report = Report.draft(1L, 1, 1, contentJsonWithDetailItems("박리·박락", "B", "균열", "C"), 100L);
        when(reportRepository.findById(5L)).thenReturn(Optional.of(report));
        when(inspectionService.getInspection(100L, 500L, 1L)).thenReturn(inspection(10L));
        when(defectRepository.findByInspectionIdAndStatusInAndDeletedFalse(anyLong(), any()))
                .thenReturn(List.of(
                        confirmedDefect(DefectType.CRACK, DefectGrade.C),
                        confirmedDefect(DefectType.SPALLING, DefectGrade.B)));

        ReportDefectSyncResponse response = reportService.recheckGrounding(5L, 500L, 100L);

        assertThat(response.groundingCheckPassed()).isTrue();
    }

    @Test
    void recheckGrounding_등급만달라도불일치() {
        Report report = Report.draft(1L, 1, 1, contentJsonWithDetailItems("균열", "B"), 100L);
        when(reportRepository.findById(5L)).thenReturn(Optional.of(report));
        when(inspectionService.getInspection(100L, 500L, 1L)).thenReturn(inspection(10L));
        when(defectRepository.findByInspectionIdAndStatusInAndDeletedFalse(anyLong(), any()))
                .thenReturn(List.of(confirmedDefect(DefectType.CRACK, DefectGrade.C)));

        ReportDefectSyncResponse response = reportService.recheckGrounding(5L, 500L, 100L);

        assertThat(response.groundingCheckPassed()).isFalse();
        assertThat(report.getGroundingWarnings()).contains("일치하지 않습니다");
    }

    @Test
    void recheckGrounding_유형만달라도불일치() {
        Report report = Report.draft(1L, 1, 1, contentJsonWithDetailItems("박리·박락", "C"), 100L);
        when(reportRepository.findById(5L)).thenReturn(Optional.of(report));
        when(inspectionService.getInspection(100L, 500L, 1L)).thenReturn(inspection(10L));
        when(defectRepository.findByInspectionIdAndStatusInAndDeletedFalse(anyLong(), any()))
                .thenReturn(List.of(confirmedDefect(DefectType.CRACK, DefectGrade.C)));

        ReportDefectSyncResponse response = reportService.recheckGrounding(5L, 500L, 100L);

        assertThat(response.groundingCheckPassed()).isFalse();
    }

    @Test
    void recheckGrounding_개수가달라도불일치() {
        Report report = Report.draft(1L, 1, 1, contentJsonWithDetailItems("균열", "C"), 100L);
        when(reportRepository.findById(5L)).thenReturn(Optional.of(report));
        when(inspectionService.getInspection(100L, 500L, 1L)).thenReturn(inspection(10L));
        when(defectRepository.findByInspectionIdAndStatusInAndDeletedFalse(anyLong(), any()))
                .thenReturn(List.of(
                        confirmedDefect(DefectType.CRACK, DefectGrade.C),
                        confirmedDefect(DefectType.SPALLING, DefectGrade.B)));

        ReportDefectSyncResponse response = reportService.recheckGrounding(5L, 500L, 100L);

        assertThat(response.groundingCheckPassed()).isFalse();
    }

    @Test
    void recheckGrounding_타인소유_REPORT_NOT_FOUND() {
        Report report = Report.draft(1L, 1, 1, contentJsonWithDetailItems("균열", "C"), 100L);
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
        Report report = Report.draft(1L, 1, 1, contentJsonWithDetailItems("균열", "C"), 100L);
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

    private static Defect confirmedDefectWithId(Long id, DefectType type, DefectGrade grade) {
        Defect defect = confirmedDefect(type, grade);
        ReflectionTestUtils.setField(defect, "id", id);
        return defect;
    }

    private static String contentJsonWithDetailItem(Long defectId, String type, String grade) {
        ReportResponse.DetailItem item =
                new ReportResponse.DetailItem(defectId, type, "위치", grade, "기존 설명", "기존 원인");
        ReportResponse aiReport = new ReportResponse(
                new ReportResponse.Overview("목적", "요약", "범위"),
                new ReportResponse.Summary("양호", 1, java.util.Map.of(), List.of()),
                new ReportResponse.Detail(List.of(item)),
                new ReportResponse.Recommendation(List.of(), List.of()),
                true);
        return GroundingReportContentSerializer.serialize(aiReport);
    }

    @Test
    void resyncDefects_새확정하자를추가하고서술은비워둔다() {
        // #1653 P2 — resync-defects는 새로 확정된 하자를 구조 필드만 채워 추가한다. 서술(description/
        // cause)은 AI 재호출 없이는 채울 수 없으므로 빈 문자열로 두고 사용자가 직접 작성해야 한다.
        Report report = Report.draft(1L, 1, 1, contentJsonWithDetailItems(), 100L);
        when(reportRepository.findById(5L)).thenReturn(Optional.of(report));
        when(inspectionService.getInspection(200L, 500L, 1L)).thenReturn(inspection(10L));
        when(defectRepository.findByInspectionIdAndStatusInAndDeletedFalse(anyLong(), any()))
                .thenReturn(List.of(confirmedDefectWithId(1L, DefectType.CRACK, DefectGrade.C)));

        ReportDefectSyncResponse response = reportService.resyncDefects(5L, 500L, 200L);

        JsonNode items = response.content().path("detail").path("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).path("defect_id").asLong()).isEqualTo(1L);
        assertThat(items.get(0).path("description").asText()).isEmpty();
        assertThat(items.get(0).path("cause").asText()).isEmpty();
        assertThat(response.diff().missingDefects()).hasSize(1);
        assertThat(response.diff().missingDefects().get(0).defectId()).isEqualTo(1L);
        assertThat(response.diff().extraItems()).isEmpty();
        // resync는 본문을 바꾸므로 updateContent와 동일하게 grounding 판정이 초기화된다.
        assertThat(response.groundingCheckPassed()).isNull();
    }

    @Test
    void resyncDefects_여전히확정된항목은서술을보존한다() {
        Report report = Report.draft(1L, 1, 1, contentJsonWithDetailItem(1L, "균열", "C"), 100L);
        when(reportRepository.findById(5L)).thenReturn(Optional.of(report));
        when(inspectionService.getInspection(200L, 500L, 1L)).thenReturn(inspection(10L));
        when(defectRepository.findByInspectionIdAndStatusInAndDeletedFalse(anyLong(), any()))
                .thenReturn(List.of(confirmedDefectWithId(1L, DefectType.CRACK, DefectGrade.C)));

        ReportDefectSyncResponse response = reportService.resyncDefects(5L, 500L, 200L);

        JsonNode items = response.content().path("detail").path("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).path("description").asText()).isEqualTo("기존 설명");
        assertThat(items.get(0).path("cause").asText()).isEqualTo("기존 원인");
        assertThat(response.diff().missingDefects()).isEmpty();
        assertThat(response.diff().extraItems()).isEmpty();
    }

    @Test
    void resyncDefects_더이상확정하자가아닌항목은제거한다() {
        Report report = Report.draft(1L, 1, 1, contentJsonWithDetailItem(1L, "균열", "C"), 100L);
        when(reportRepository.findById(5L)).thenReturn(Optional.of(report));
        when(inspectionService.getInspection(200L, 500L, 1L)).thenReturn(inspection(10L));
        when(defectRepository.findByInspectionIdAndStatusInAndDeletedFalse(anyLong(), any()))
                .thenReturn(List.of());

        ReportDefectSyncResponse response = reportService.resyncDefects(5L, 500L, 200L);

        assertThat(response.content().path("detail").path("items")).isEmpty();
        assertThat(response.diff().extraItems()).hasSize(1);
        assertThat(response.diff().extraItems().get(0).defectId()).isEqualTo(1L);
        assertThat(response.diff().missingDefects()).isEmpty();
    }

    @Test
    void resyncDefects_defectId없는레거시항목은삭제하지않고보존하며diff에unmatched로노출한다() {
        // PR머신 리뷰 P1 — defectId가 없는 항목(2026-08-02 이전 저장분 등 구버전 콘텐츠)을 비교가
        // 안 된다는 이유로 잉여 취급해 지우면 검수자가 직접 쓴 서술이 무경고로 사라진다. 확정 하자가
        // 0건이라(잉여로 오판되기 가장 쉬운 조건) 이 항목이 정말 "제거 대상이 아니라 보존 대상"인지를
        // extraItems(제거)가 아니라 unmatchedItems(보존)로 판정하는지 검증한다.
        Report report = Report.draft(1L, 1, 1, contentJsonWithDetailItems("균열", "C"), 100L);
        when(reportRepository.findById(5L)).thenReturn(Optional.of(report));
        when(inspectionService.getInspection(200L, 500L, 1L)).thenReturn(inspection(10L));
        when(defectRepository.findByInspectionIdAndStatusInAndDeletedFalse(anyLong(), any()))
                .thenReturn(List.of());

        ReportDefectSyncResponse response = reportService.resyncDefects(5L, 500L, 200L);

        JsonNode items = response.content().path("detail").path("items");
        assertThat(items).hasSize(1); // 삭제되지 않고 그대로 보존됨
        assertThat(items.get(0).path("defect_type").asText()).isEqualTo("균열");
        assertThat(items.get(0).path("description").asText()).isEqualTo("설명"); // 서술도 그대로 보존
        assertThat(response.diff().extraItems()).isEmpty(); // defectId가 없어 "잉여"로 잘못 잡히지 않음
        assertThat(response.diff().missingDefects()).isEmpty();
        assertThat(response.diff().unmatchedItems()).hasSize(1);
        assertThat(response.diff().unmatchedItems().get(0).defectType()).isEqualTo("균열");
        assertThat(response.diff().unmatchedItems().get(0).severityGrade()).isEqualTo("C");
    }

    @Test
    void recheckGrounding_defectId없는레거시항목은diff의unmatchedItems로노출된다() {
        Report report = Report.draft(1L, 1, 1, contentJsonWithDetailItems("균열", "C"), 100L);
        when(reportRepository.findById(5L)).thenReturn(Optional.of(report));
        when(inspectionService.getInspection(100L, 500L, 1L)).thenReturn(inspection(10L));
        when(defectRepository.findByInspectionIdAndStatusInAndDeletedFalse(anyLong(), any()))
                .thenReturn(List.of(confirmedDefect(DefectType.CRACK, DefectGrade.C)));

        ReportDefectSyncResponse response = reportService.recheckGrounding(5L, 500L, 100L);

        // grounding-recheck는 진단만 하므로 본문은 바뀌지 않는다.
        assertThat(response.content().path("detail").path("items")).hasSize(1);
        assertThat(response.diff().extraItems()).isEmpty();
        assertThat(response.diff().unmatchedItems()).hasSize(1);
        assertThat(response.diff().unmatchedItems().get(0).defectType()).isEqualTo("균열");
    }

    @Test
    void resyncDefects_타인소유_REPORT_NOT_FOUND() {
        Report report = Report.draft(1L, 1, 1, contentJsonWithDetailItems(), 100L);
        when(reportRepository.findById(5L)).thenReturn(Optional.of(report));
        doThrow(new BusinessException(ErrorCode.FACILITY_NOT_FOUND))
                .when(inspectionService).getInspection(999L, 500L, 1L);

        assertThatThrownBy(() -> reportService.resyncDefects(5L, 500L, 999L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.REPORT_NOT_FOUND));
        verify(defectRepository, never()).findByInspectionIdAndStatusInAndDeletedFalse(anyLong(), any());
    }

    @Test
    void resyncDefects_FINALIZED상태에서시도하면예외() {
        Report report = Report.draft(1L, 1, 1, contentJsonWithDetailItems("균열", "C"), 100L);
        report.recordGroundingResult(
                com.hajacheck.core.report.entity.GroundingCheckResultTestFactory.passed(
                        com.hajacheck.core.report.entity.GroundingCheckTarget.capture(
                                report.captureGroundingRequestContext(), report.getContentJson()),
                        null),
                100L);
        report.finalizeReport("/api/reports/5/pdf/r.pdf", 100L);
        when(reportRepository.findById(5L)).thenReturn(Optional.of(report));
        when(inspectionService.getInspection(100L, 500L, 1L)).thenReturn(inspection(10L));
        when(defectRepository.findByInspectionIdAndStatusInAndDeletedFalse(anyLong(), any()))
                .thenReturn(List.of(confirmedDefect(DefectType.CRACK, DefectGrade.C)));

        assertThatThrownBy(() -> reportService.resyncDefects(5L, 500L, 100L))
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
        Report report = Report.draft(1L, 1, 1, "{}", 100L);
        when(reportRepository.findById(5L)).thenReturn(Optional.of(report));
        doThrow(new BusinessException(ErrorCode.FACILITY_NOT_FOUND))
                .when(inspectionService).getInspection(200L, 999L, 1L);

        assertThatThrownBy(() -> reportService.getReport(5L, 200L, 999L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.REPORT_NOT_FOUND));
    }

    @Test
    void cloneReport_원본content를다음버전DRAFT로복제하고검증필드는초기화() {
        Report source = Report.draft(1L, 1, 2, "{\"overview\":{\"purpose\":\"copy\"}}", 100L);
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

    /**
     * cloneReport 버전 채번 경합 재시도(#1653 P3)를 트랜잭션 밖에서 안전하게 수행하려면 generateDraft와
     * 동일하게 NOT_SUPPORTED여야 한다 — 실패한 INSERT 직후 PostgreSQL 트랜잭션이 abort 상태로 고정되면
     * 같은 트랜잭션 안에서는 재조회·재저장이 모두 실패하기 때문이다(saveWithVersionConflictRetry 참고).
     */
    @Test
    void cloneReport_트랜잭션밖실행_NOT_SUPPORTED() throws NoSuchMethodException {
        Method method = ReportService.class.getMethod("cloneReport", Long.class, Long.class, Long.class);
        Transactional transactional = method.getAnnotation(Transactional.class);

        assertThat(transactional).as("cloneReport 는 @Transactional 애노테이션을 명시해야 한다").isNotNull();
        assertThat(transactional.propagation())
                .as("버전 채번 경합 재시도가 abort된 트랜잭션에 갇히지 않도록 NOT_SUPPORTED 여야 한다")
                .isEqualTo(Propagation.NOT_SUPPORTED);
    }

    @Test
    void cloneReport_버전채번경합시1회재시도후성공() {
        Report source = Report.draft(1L, 1, 2, "{\"overview\":{\"purpose\":\"copy\"}}", 100L);
        when(reportRepository.findById(5L)).thenReturn(Optional.of(source));
        when(inspectionService.getInspection(200L, 100L, 1L)).thenReturn(inspection(10L));
        Report concurrentWinner = Report.draft(1L, 1, 3, "{}", 999L);
        when(reportRepository.findFirstByInspectionIdOrderByVersionDesc(1L))
                .thenReturn(Optional.of(source))
                .thenReturn(Optional.of(concurrentWinner));
        when(reportRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("dup",
                        new ConstraintViolationException(
                                "dup", new SQLException("dup"), "uk_reports_inspection_version")))
                .thenAnswer(inv -> inv.getArgument(0));

        ReportDetailResponse response = reportService.cloneReport(5L, 100L, 200L);

        assertThat(response.version()).isEqualTo(4);
        verify(reportRepository, times(2)).saveAndFlush(any());
    }

    @Test
    void cloneReport_타인소유_REPORT_NOT_FOUND() {
        Report report = Report.draft(1L, 1, 1, "{}", 100L);
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
        Report report = Report.draft(1L, 1, 1, "{}", 100L);
        when(reportRepository.findById(5L)).thenReturn(Optional.of(report));
        when(inspectionService.getInspection(200L, 100L, 1L)).thenReturn(inspection(10L));

        reportService.deleteDraftReport(5L, 100L, 200L);

        assertThat(report.getDeletedAt()).isNotNull();
        assertThat(report.getEditedBy()).isEqualTo(200L);
    }

    @Test
    void deleteDraftReport_FINALIZED보고서는INVALID_STATE_TRANSITION() {
        Report report = Report.draft(1L, 1, 1, "{}", 100L);
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
        Report report = Report.draft(1L, 1, 1, "{}", 100L);
        report.markDeleted(100L);
        when(reportRepository.findById(5L)).thenReturn(Optional.of(report));

        assertThatThrownBy(() -> reportService.getReport(5L, 200L, 100L))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.REPORT_NOT_FOUND));
        verify(inspectionService, never()).getInspection(anyLong(), anyLong(), anyLong());
    }

    @Test
    void getReport_기존데이터로보고서Context를함께반환한다() {
        Report report = Report.draft(1L, 1, 1, "{}", 100L);
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
                .areaRatio(0.005)
                .areaMm2(45.5)
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
        when(mediaRepository.findByInspectionIdAndPurposeOrderByIdAsc(1L, MediaPurpose.INSPECTION_SOURCE))
                .thenReturn(List.of(media));

        ReportDetailResponse response = reportService.getReport(5L, 200L, 100L);

        assertThat(response.context()).isNotNull();
        assertThat(response.context().facility().name()).isEqualTo("테스트빌딩");
        assertThat(response.context().inspection().roundNo()).isEqualTo(1);
        assertThat(response.context().defects()).hasSize(1);
        assertThat(response.context().defects().get(0).typeLabel()).isEqualTo("균열");
        assertThat(response.context().defects().get(0).location()).isEqualTo("교량 하부 익명 위치");
        assertThat(response.context().defects().get(0).areaMm2()).isEqualTo(45.5);
        assertThat(response.context().media()).hasSize(1);
        assertThat(response.context().media().get(0).thumbnailUrl()).isNull();
    }

    /**
     * #1641 P3 방어적 위생 — 보고서 컨텍스트의 media 목록은 실제로 ReportDetailResponse.context().media()
     * 로 그대로 노출된다(lookup map이 아니라 직렬화 대상). 조치 후 사진(DEFECT_ACTION)이 이 목록에
     * 섞이지 않는지, 그리고 서비스가 purpose 필터 없는 옛 메서드로 되돌아가지 않는지 함께 고정한다.
     */
    @Test
    void getReport_조치후사진은보고서media목록에서제외된다() {
        Report report = Report.draft(1L, 1, 1, "{}", 100L);
        when(reportRepository.findById(5L)).thenReturn(Optional.of(report));
        when(inspectionService.getInspection(200L, 100L, 1L)).thenReturn(inspection(10L));
        when(facilityService.get(200L, 100L, 10L)).thenReturn(facility());
        when(defectRepository.findByInspectionIdAndStatusInAndDeletedFalse(anyLong(), any()))
                .thenReturn(List.of());
        // 리포지토리가 실제로 필터링한 결과를 흉내낸다(원본만 반환) — 실 필터 로직 자체는
        // MediaRepositoryTest가 실 PG로 검증한다.
        Media source = Media.builder()
                .inspectionId(1L)
                .fileType(MediaFileType.IMAGE)
                .originalUrl("stored-source")
                .mimeSignatureVerified(true)
                .build();
        when(mediaRepository.findByInspectionIdAndPurposeOrderByIdAsc(1L, MediaPurpose.INSPECTION_SOURCE))
                .thenReturn(List.of(source));

        ReportDetailResponse response = reportService.getReport(5L, 200L, 100L);

        assertThat(response.context().media()).hasSize(1);
        verify(mediaRepository, never()).findByInspectionIdOrderByIdAsc(anyLong());
    }

    @Test
    void listReports_소유권검증후버전목록을최신순으로반환() {
        when(inspectionService.getInspection(200L, 100L, 1L)).thenReturn(inspection(10L));
        Report v1 = Report.draft(1L, 1, 1, "{}", 100L);
        Report v2 = Report.draft(1L, 1, 2, "{}", 100L);
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
        Report v1 = Report.draft(1L, 1, 1, "{}", 999L);
        when(reportRepository.findByInspectionIdAndDeletedAtIsNullOrderByVersionDesc(1L)).thenReturn(List.of(v1));
        when(userRepository.findAllById(List.of(999L))).thenReturn(List.of());

        List<ReportSummaryResponse> result = reportService.listReports(1L, 200L, 100L);

        assertThat(result.get(0).createdByName()).isNull();
    }

    @Test
    void finalizeReport_근거검증통과후PDF와확정상태기록() {
        Report report = Report.draft(1L, 1, 1, "{}", 100L);
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
    void finalizeReport_이미FINALIZED이고pdfUrl있으면_재확정없이현재상태를반환한다() {
        // #1653 P2 — 확정 응답 유실(멱등성) 재현: 클라이언트가 성공 응답을 못 받고 재시도해도
        // 재확정을 시도하지 않고 현재 확정 상태를 그대로 반환한다(재검증·회차 상태전이 등 부수효과 없음).
        Report report = Report.draft(1L, 1, 1, "{}", 100L);
        report.recordGroundingResult(
                com.hajacheck.core.report.entity.GroundingCheckResultTestFactory.passed(
                        com.hajacheck.core.report.entity.GroundingCheckTarget.capture(
                                report.captureGroundingRequestContext(), report.getContentJson()),
                        null),
                100L);
        report.finalizeReport("/api/reports/5/pdf/r.pdf", 100L);
        when(reportRepository.findById(5L)).thenReturn(Optional.of(report));
        when(inspectionService.getInspection(200L, 100L, 1L)).thenReturn(inspection(10L));

        ReportDetailResponse response = reportService.finalizeReport(5L, "/api/reports/5/pdf/other.pdf", 100L, 200L);

        assertThat(response.status()).isEqualTo(com.hajacheck.core.report.entity.ReportStatus.FINALIZED);
        // 기존 확정 pdfUrl을 그대로 유지 — 재확정을 시도하지 않았다는 증거.
        assertThat(response.pdfUrl()).isEqualTo("/api/reports/5/pdf/r.pdf");
        // PDF 로드·본문 검증·grounding 재검증·회차 상태전이 등 재확정 부수효과가 전혀 일어나지 않는다.
        verifyNoInteractions(reportPdfStorage, reportFinalizationValidator);
        verify(inspectionService, never()).advanceStatus(any(), any(), any(), any());
    }

    @Test
    void finalizeReport_grounding재검증은상시수행_stale통과판정이있어도불일치면거부() {
        // #1653 P1 — finalize가 저장된 groundingCheckPassed=true(stale)를 그대로 신뢰하면 안 된다.
        // 보고서 자체는 편집되지 않았지만(그래서 groundingCheckPassed는 여전히 true) 그 사이 하자
        // 등급이 수정돼(C→B) 확정 하자 목록과 본문(detail.items)이 더 이상 일치하지 않는 상황.
        Report report = Report.draft(1L, 1, 1, contentJsonWithDetailItems("균열", "C"), 100L);
        report.recordGroundingResult(
                com.hajacheck.core.report.entity.GroundingCheckResultTestFactory.passed(
                        com.hajacheck.core.report.entity.GroundingCheckTarget.capture(
                                report.captureGroundingRequestContext(), report.getContentJson()),
                        null),
                100L);
        assertThat(report.getGroundingCheckPassed()).isTrue(); // stale true 상태에서 출발
        when(reportRepository.findById(5L)).thenReturn(Optional.of(report));
        when(inspectionService.getInspection(200L, 100L, 1L)).thenReturn(inspection(10L));
        when(defectRepository.findByInspectionIdAndStatusInAndDeletedFalse(anyLong(), any()))
                .thenReturn(List.of(confirmedDefect(DefectType.CRACK, DefectGrade.B)));

        assertThatThrownBy(() -> reportService.finalizeReport(5L, "/api/reports/5/pdf/r.pdf", 100L, 200L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("근거 검증");
        assertThat(report.getStatus()).isEqualTo(com.hajacheck.core.report.entity.ReportStatus.DRAFT);
    }

    @Test
    void finalizeReport_grounding재검증은상시수행_최신확정하자와일치하면성공() {
        Report report = Report.draft(1L, 1, 1, contentJsonWithDetailItems("균열", "C"), 100L);
        report.recordGroundingResult(
                com.hajacheck.core.report.entity.GroundingCheckResultTestFactory.passed(
                        com.hajacheck.core.report.entity.GroundingCheckTarget.capture(
                                report.captureGroundingRequestContext(), report.getContentJson()),
                        null),
                100L);
        when(reportRepository.findById(5L)).thenReturn(Optional.of(report));
        when(inspectionService.getInspection(200L, 100L, 1L)).thenReturn(inspection(10L));
        when(defectRepository.findByInspectionIdAndStatusInAndDeletedFalse(anyLong(), any()))
                .thenReturn(List.of(confirmedDefect(DefectType.CRACK, DefectGrade.C)));

        ReportDetailResponse response = reportService.finalizeReport(5L, "/api/reports/5/pdf/r.pdf", 100L, 200L);

        assertThat(response.status()).isEqualTo(com.hajacheck.core.report.entity.ReportStatus.FINALIZED);
        verify(defectRepository, times(1)).findByInspectionIdAndStatusInAndDeletedFalse(anyLong(), any());
    }

    @Test
    void finalizeReport_다른보고서용pdfUrl이면REPORT_PDF_URL_INVALID() {
        Report report = Report.draft(1L, 1, 1, "{}", 100L);
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
        Report report = Report.draft(1L, 1, 1, "{}", 100L);
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
        Report report = Report.draft(1L, 1, 1, "{}", 100L);
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

    // ── 회차 재정렬(#1702) 리뷰 P1 회귀선 ──

    /**
     * 락 순서 역전(ABBA) 데드락 회귀선 — {@code InspectionService.createInspection}(소급 회차)은
     * facilities → inspections → reports 순으로 락을 잡는다. {@code finalizeReport}가 그 반대
     * (reports → inspections → facilities) 순으로 잡으면 같은 시설물에 두 요청이 동시에 올 때 순환이
     * 완성돼 PostgreSQL이 한쪽을 40P01로 abort시킨다.
     *
     * <p>그래서 finalize는 <b>첫 쓰기보다 먼저</b> 시설물 행을 잠가 순서를 하나로 통일해야 한다. 여기서는
     * 그 선취가 회차 상태 전이(= inspections 쓰기)보다 앞서는지를 호출 순서로 고정한다 — 이 순서가
     * 뒤집히면(예: markInspectionReported 직전으로 옮기면) 그 사이 auto-flush가 reports 락을 먼저 잡아
     * 회귀가 되살아난다.
     */
    @Test
    void finalizeReport_시설물행을_회차전이보다먼저잠근다_락순서역전회귀() {
        Report report = Report.draft(1L, 1, 1, "{}", 100L);
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

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(facilityService, inspectionService);
        inOrder.verify(facilityService).lockForUpdate(10L);
        inOrder.verify(inspectionService).advanceStatus(200L, 100L, 1L, InspectionStatus.REVIEWED);
        inOrder.verify(facilityService).recalculateNextInspectionDueAt(200L, 100L, 10L, LocalDate.now());
    }

    /**
     * 회차 스냅샷 TOCTOU 회귀선 — generateDraft는 NOT_SUPPORTED(트랜잭션 없음)라 AI 왕복(수 초~수 분)
     * 동안 보호가 없다. 그 창에서 소급 회차가 생겨 재정렬되면, 아직 INSERT되지 않은 이 보고서는
     * syncDraftRoundNoToInspection의 대상이 될 수 없어 옛 회차가 그대로 굳는다(확정 시 틀린 회차가 PDF
     * 표지에 인쇄됨). 그래서 스냅샷은 초입 값이 아니라 <b>persist 직전에 다시 읽은 값</b>이어야 한다.
     */
    @Test
    void generateDraft_AI왕복중회차가밀리면_persist직전회차로스냅샷한다() {
        when(inspectionService.getInspection(200L, 100L, 1L))
                .thenReturn(inspectionWithRound(10L, 2))   // 초입 조회 — AI 호출 전
                .thenReturn(inspectionWithRound(10L, 3));  // persist 직전 재조회 — 그 사이 소급 회차로 +1 밀림
        when(facilityService.get(200L, 100L, 10L)).thenReturn(facility());
        when(defectRepository.findByInspectionIdAndStatusInAndDeletedFalse(anyLong(), any()))
                .thenReturn(List.of());
        when(reportRepository.findFirstByInspectionIdOrderByVersionDesc(1L)).thenReturn(Optional.empty());
        when(aiProxyService.generateReport(anyLong(), any()))
                .thenAnswer(inv -> ApiResponse.ok(aiReportMatching(inv.getArgument(1))));
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        reportService.generateDraft(1L, 100L, 200L);

        ArgumentCaptor<Report> captor = ArgumentCaptor.forClass(Report.class);
        verify(reportRepository).save(captor.capture());
        assertThat(captor.getValue().getRoundNo())
                .as("AI 왕복 전 값(2)이 아니라 persist 직전 재조회 값(3)이 찍혀야 한다")
                .isEqualTo(3);
    }

    @Test
    void cloneReport_persist직전회차로스냅샷한다() {
        Report source = Report.draft(1L, 1, 2, "{\"overview\":{\"purpose\":\"copy\"}}", 100L);
        when(reportRepository.findById(5L)).thenReturn(Optional.of(source));
        when(inspectionService.getInspection(200L, 100L, 1L))
                .thenReturn(inspectionWithRound(10L, 2))
                .thenReturn(inspectionWithRound(10L, 4));
        when(reportRepository.findFirstByInspectionIdOrderByVersionDesc(1L)).thenReturn(Optional.of(source));
        when(reportRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        reportService.cloneReport(5L, 100L, 200L);

        ArgumentCaptor<Report> captor = ArgumentCaptor.forClass(Report.class);
        verify(reportRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getRoundNo()).isEqualTo(4);
    }

    /**
     * 버전 채번 경합 재시도는 첫 시도 실패만큼 시간이 더 흐른 뒤라, 처음 찍어 둔 회차를 재사용하면
     * 스냅샷이 어긋날 창이 오히려 넓어진다 — 재시도 시 버전과 함께 회차도 다시 읽어 찍는지 고정한다.
     */
    @Test
    void cloneReport_버전경합재시도시_회차도다시읽어찍는다() {
        Report source = Report.draft(1L, 1, 2, "{\"overview\":{\"purpose\":\"copy\"}}", 100L);
        when(reportRepository.findById(5L)).thenReturn(Optional.of(source));
        when(inspectionService.getInspection(200L, 100L, 1L))
                .thenReturn(inspectionWithRound(10L, 2))   // findCompanyReportWithInspection
                .thenReturn(inspectionWithRound(10L, 2))   // 첫 persist 직전
                .thenReturn(inspectionWithRound(10L, 5));  // 재시도 직전 — 그 사이 또 밀림
        Report concurrentWinner = Report.draft(1L, 2, 3, "{}", 999L);
        when(reportRepository.findFirstByInspectionIdOrderByVersionDesc(1L))
                .thenReturn(Optional.of(source))
                .thenReturn(Optional.of(concurrentWinner));
        when(reportRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("dup",
                        new ConstraintViolationException(
                                "dup", new SQLException("dup"), "uk_reports_inspection_version")))
                .thenAnswer(inv -> inv.getArgument(0));

        ReportDetailResponse response = reportService.cloneReport(5L, 100L, 200L);

        assertThat(response.version()).isEqualTo(4);
        ArgumentCaptor<Report> captor = ArgumentCaptor.forClass(Report.class);
        verify(reportRepository, times(2)).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getRoundNo()).isEqualTo(5);
    }

    // ── 보고서 확정 시 회차 완료 전이(팀 테스트 피드백, 2026-08-01) ──
    // REVIEWED/REPORTED 둘 다 상태 머신엔 도착 상태로 정의돼 있었지만 실제로 전이시키는 코드가
    // 없어, 검수를 끝내고 보고서까지 만들어도 회차가 ANALYZED에 영원히 머물던 문제.

    @Test
    void finalizeReport_회차가ANALYZED면_REVIEWED거쳐REPORTED로전이한다() {
        Report report = Report.draft(1L, 1, 1, "{}", 100L);
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
        // #1497/HAJA-656 — REPORTED 전이 시 그 회차의 점검일(inspection(10L,..)이 세팅한 LocalDate.now())
        // 기준으로 시설물의 다음 점검일을 재계산해야 한다.
        verify(facilityService).recalculateNextInspectionDueAt(200L, 100L, 10L, LocalDate.now());
    }

    @Test
    void finalizeReport_회차가REVIEWED면_REVIEWED전이없이바로REPORTED로전이한다() {
        Report report = Report.draft(1L, 1, 1, "{}", 100L);
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
        // #1497/HAJA-656
        verify(facilityService).recalculateNextInspectionDueAt(200L, 100L, 10L, LocalDate.now());
    }

    @Test
    void finalizeReport_회차가이미REPORTED면_전이를시도하지않는다() {
        // REPORTED는 상태 머신상 종단(더 이상 어디로도 전이 불가)이라, 같은 회차의 다른 보고서
        // 버전을 재확정하는 경우 재전이를 시도하면 DomainStateTransitionException이 난다.
        Report report = Report.draft(1L, 1, 2, "{}", 100L);
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
        // #1497/HAJA-656 — REPORTED로 실제 전이되지 않으므로(이미 종단 상태) 재계산도 호출되면 안 된다.
        verify(facilityService, never()).recalculateNextInspectionDueAt(any(), any(), any(), any());
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
            Report report = Report.draft(1L, 1, 1, "{}", 100L);
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
        // #1497/HAJA-656
        verify(facilityService, never()).recalculateNextInspectionDueAt(any(), any(), any(), any());
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
        Report report = Report.draft(1L, 1, 1, "{}", 200L);
        when(reportRepository.findById(5L)).thenReturn(Optional.of(report));
        doThrow(new BusinessException(ErrorCode.FORBIDDEN))
                .when(inspectionService).getInspection(200L, 100L, 1L);

        assertThatThrownBy(() -> reportService.getReport(5L, 200L, 100L))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getErrorCode())
                        .isEqualTo(ErrorCode.FORBIDDEN));
    }
}
