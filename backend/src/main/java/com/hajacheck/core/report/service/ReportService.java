package com.hajacheck.core.report.service;

import com.hajacheck.core.ai.dto.ReportRequest;
import com.hajacheck.core.ai.dto.ReportResponse;
import com.hajacheck.core.ai.service.AiProxyService;
import com.hajacheck.core.defect.entity.Defect;
import com.hajacheck.core.defect.entity.DefectStatus;
import com.hajacheck.core.defect.repository.DefectRepository;
import com.hajacheck.core.facility.dto.FacilityResponse;
import com.hajacheck.core.facility.service.FacilityService;
import com.hajacheck.core.inspection.dto.InspectionResponse;
import com.hajacheck.core.inspection.service.InspectionService;
import com.hajacheck.core.report.dto.ReportDetailResponse;
import com.hajacheck.core.report.dto.ReportSummaryResponse;
import com.hajacheck.core.report.entity.GroundingCheckResult;
import com.hajacheck.core.report.entity.GroundingRequestContext;
import com.hajacheck.core.report.entity.Report;
import com.hajacheck.core.report.repository.ReportRepository;
import com.hajacheck.global.common.ApiResponse;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 점검 결과 기반 AI 보고서 생성·조회·편집·확정(#446 / HAJA-283).
 * grounding 왕복(캡처→AI 호출→기록) 은 GroundingRequestContext/GroundingReportRequestFactory/
 * GroundingReportContentSerializer/GroundingCheckResultFactory(report.service, #349/#334 산출물)를
 * 조립만 하고 새로 설계하지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReportService {

    // 확정 하자 범위 — DETECTED(AI 자동탐지 직후, 사람 검토 전)는 AI 보고서 입력에서 제외한다.
    private static final List<DefectStatus> CONFIRMED_DEFECT_STATUSES = List.of(
            DefectStatus.CONFIRMED, DefectStatus.ACTION_PENDING, DefectStatus.IN_PROGRESS, DefectStatus.RESOLVED);
    private static final String DEFAULT_ON_MISMATCH = "regenerate";
    // 내부 AI 서버가 이미 grounding_ok/근거 대조까지 마친 응답만 신뢰하므로(GroundingCheckResultFactory
    // 참고), 별도 경고 수집 파이프라인이 붙기 전까지는 항상 빈 배열로 기록한다(GroundingCheckResult가
    // passed=true일 때 비어있지 않은 경고와의 동시 존재를 막는 도메인 규칙과도 정합).
    private static final String NO_GROUNDING_WARNINGS = "[]";

    private final ReportRepository reportRepository;
    private final DefectRepository defectRepository;
    private final InspectionService inspectionService;
    private final FacilityService facilityService;
    private final AiProxyService aiProxyService;

    /**
     * 확정 하자를 근거로 AI 보고서 초안을 생성한다.
     * 확정 하자가 0건이어도 에러로 막지 않고 빈 목록으로 진행한다 — 결함이 없는 정상 점검도 유효한
     * 보고서 유스케이스이며(점검=이상없음 확인도 결과물이 필요), AI 서버 계약(ReportRequest.confirmedDefects
     * @NotEmpty)이 최소 1건을 요구하면 그 시점에 AI_REQUEST_REJECTED 등으로 자연히 드러난다.
     */
    @Transactional
    public ReportDetailResponse generateDraft(Long inspectionId, Long userId) {
        InspectionResponse inspection = inspectionService.getInspection(userId, inspectionId);
        FacilityResponse facility = facilityService.get(userId, inspection.facilityId());

        List<Defect> confirmedDefects = defectRepository.findByInspectionIdAndStatusInAndDeletedFalse(
                inspectionId, CONFIRMED_DEFECT_STATUSES);
        List<ReportRequest.ConfirmedDefect> confirmedDefectDtos = confirmedDefects.stream()
                .map(defect -> ConfirmedDefectTextFactory.from(defect, facility.address()))
                .toList();
        ReportRequest.FacilityInfo facilityInfo =
                new ReportRequest.FacilityInfo(facility.name(), facility.address());

        int nextVersion = nextVersion(inspectionId);
        GroundingRequestContext context = GroundingRequestContext.capture(inspectionId, nextVersion);
        ReportRequest request = GroundingReportRequestFactory.from(
                context, facilityInfo, confirmedDefectDtos, DEFAULT_ON_MISMATCH);

        ReportResponse aiReport = callAiServer(request);

        String contentJson = GroundingReportContentSerializer.serialize(aiReport);
        Report report = Report.draft(inspectionId, nextVersion, contentJson, userId);

        GroundingCheckResult result =
                GroundingCheckResultFactory.fromAiReport(context, aiReport, NO_GROUNDING_WARNINGS);
        report.recordGroundingResult(result, userId);

        return ReportDetailResponse.from(reportRepository.save(report));
    }

    public ReportDetailResponse getReport(Long reportId, Long userId) {
        return ReportDetailResponse.from(findOwnedReport(reportId, userId));
    }

    public List<ReportSummaryResponse> listReports(Long inspectionId, Long userId) {
        // 소유권 검증(IDOR 방지) — 미존재/타인소유 모두 InspectionService.getInspection() 이 통일 응답.
        inspectionService.getInspection(userId, inspectionId);
        return reportRepository.findByInspectionIdOrderByVersionDesc(inspectionId).stream()
                .map(ReportSummaryResponse::from)
                .toList();
    }

    @Transactional
    public ReportDetailResponse updateContent(Long reportId, String contentJson, Long userId) {
        Report report = findOwnedReport(reportId, userId);
        report.updateContent(contentJson, userId);
        return ReportDetailResponse.from(report);
    }

    @Transactional
    public ReportDetailResponse finalizeReport(Long reportId, String pdfUrl, Long userId) {
        Report report = findOwnedReport(reportId, userId);
        report.finalizeReport(pdfUrl, userId);
        return ReportDetailResponse.from(report);
    }

    private int nextVersion(Long inspectionId) {
        return reportRepository.findFirstByInspectionIdOrderByVersionDesc(inspectionId)
                .map(latest -> latest.getVersion() + 1)
                .orElse(1);
    }

    private ReportResponse callAiServer(ReportRequest request) {
        // AiProxyService.generateReport()는 연결/타임아웃/응답형식 실패를 이미 BusinessException으로
        // 던진다(AiProxyService 참고) — 여기서 잡는 것은 envelope 자체는 정상 수신했으나 AI 서버가
        // 보고서 생성을 논리적으로 거부한 경우(envelope.success()=false)뿐이다.
        ApiResponse<ReportResponse> response = aiProxyService.generateReport(request);
        if (!response.success() || response.data() == null) {
            throw new BusinessException(ErrorCode.REPORT_GENERATION_FAILED);
        }
        return response.data();
    }

    /**
     * 소유권 검증(IDOR 방지) — MediaService.getThumbnail() 패턴과 동일하게, 존재 여부 열거를 막기 위해
     * 미존재/타인소유 모두 REPORT_NOT_FOUND(404) 로 통일 응답한다.
     */
    private Report findOwnedReport(Long reportId, Long userId) {
        Report report = reportRepository.findById(reportId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND));
        try {
            inspectionService.getInspection(userId, report.getInspectionId());
        } catch (BusinessException e) {
            throw new BusinessException(ErrorCode.REPORT_NOT_FOUND);
        }
        return report;
    }
}
