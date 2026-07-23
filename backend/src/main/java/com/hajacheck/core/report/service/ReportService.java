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
import org.springframework.transaction.annotation.Propagation;
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
    // AI 서버 동기 호출(callAiServer → RestClient)이 지연되면 DB 커넥션이 트랜잭션에 묶여 풀 고갈로 이어진다.
    // NOT_SUPPORTED 로 이 메서드 전체를 트랜잭션 밖에서 실행해(클래스 기본값 readOnly=true 도 상속하지 않음),
    // AI 왕복 동안 커넥션을 잡지 않는다. nextVersion 조회(findFirst...)와 save 는 SimpleJpaRepository 의
    // 각 메서드가 자체 @Transactional 을 걸어주므로(활성 트랜잭션이 없으면 각자 짧게 시작) 별도 처리가 필요 없다.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ReportDetailResponse generateDraft(Long inspectionId, Long companyId, Long userId) {
        InspectionResponse inspection = inspectionService.getInspection(companyId, inspectionId);
        FacilityResponse facility = facilityService.get(companyId, inspection.facilityId());

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

        ReportResponse aiReport = callAiServer(userId, request);

        String contentJson = GroundingReportContentSerializer.serialize(aiReport);
        Report report = Report.draft(inspectionId, nextVersion, contentJson, userId);

        GroundingCheckResult result =
                GroundingCheckResultFactory.fromAiReport(context, aiReport, NO_GROUNDING_WARNINGS);
        report.recordGroundingResult(result, userId);

        return ReportDetailResponse.from(reportRepository.save(report));
    }

    public ReportDetailResponse getReport(Long reportId, Long companyId) {
        return ReportDetailResponse.from(findCompanyReport(reportId, companyId));
    }

    public List<ReportSummaryResponse> listReports(Long inspectionId, Long companyId) {
        // 소유권 검증(IDOR 방지) — 미존재/타인소유 모두 InspectionService.getInspection() 이 통일 응답.
        inspectionService.getInspection(companyId, inspectionId);
        return reportRepository.findByInspectionIdOrderByVersionDesc(inspectionId).stream()
                .map(ReportSummaryResponse::from)
                .toList();
    }

    @Transactional
    public ReportDetailResponse updateContent(
            Long reportId, String contentJson, Long companyId, Long editedByUserId) {
        Report report = findCompanyReport(reportId, companyId);
        report.updateContent(contentJson, editedByUserId);
        return ReportDetailResponse.from(report);
    }

    @Transactional
    public ReportDetailResponse finalizeReport(
            Long reportId, String pdfUrl, Long companyId, Long editedByUserId) {
        Report report = findCompanyReport(reportId, companyId);
        requireOwnPdfUrl(reportId, pdfUrl);
        report.finalizeReport(pdfUrl, editedByUserId);
        return ReportDetailResponse.from(report);
    }

    /**
     * pdfUrl이 이 보고서의 업로드 엔드포인트(/api/reports/{id}/pdf/{storageKey})를 가리키는지 확인한다
     * (#455 P2-2) — 임의 문자열이나 타 보고서의 pdfUrl을 finalize에 그대로 실어 확정하는 것을 차단한다.
     */
    private void requireOwnPdfUrl(Long reportId, String pdfUrl) {
        String expectedPrefix = "/api/reports/%d/pdf/".formatted(reportId);
        if (pdfUrl == null || !pdfUrl.startsWith(expectedPrefix) || pdfUrl.length() == expectedPrefix.length()) {
            throw new BusinessException(ErrorCode.REPORT_PDF_URL_INVALID);
        }
    }

    private int nextVersion(Long inspectionId) {
        return reportRepository.findFirstByInspectionIdOrderByVersionDesc(inspectionId)
                .map(latest -> latest.getVersion() + 1)
                .orElse(1);
    }

    private ReportResponse callAiServer(Long userId, ReportRequest request) {
        // AiProxyService.generateReport()는 연결/타임아웃/응답형식 실패를 이미 BusinessException으로
        // 던진다(AiProxyService 참고) — 여기서 잡는 것은 envelope 자체는 정상 수신했으나 AI 서버가
        // 보고서 생성을 논리적으로 거부한 경우(envelope.success()=false)뿐이다.
        // userId 는 generateDraft 가 principal 에서 받은 값 — 사용자 축 rate-limit 키로 전달한다.
        ApiResponse<ReportResponse> response = aiProxyService.generateReport(userId, request);
        if (!response.success() || response.data() == null) {
            throw new BusinessException(ErrorCode.REPORT_GENERATION_FAILED);
        }
        return response.data();
    }

    /**
     * 소유권 검증(IDOR 방지) — MediaService.getThumbnail() 패턴과 동일하게, 존재 여부 열거를 막기 위해
     * 미존재/타인소유 모두 REPORT_NOT_FOUND(404) 로 통일 응답한다.
     */
    private Report findCompanyReport(Long reportId, Long companyId) {
        Report report = reportRepository.findById(reportId)
                .orElseThrow(() -> new BusinessException(ErrorCode.REPORT_NOT_FOUND));
        try {
            inspectionService.getInspection(companyId, report.getInspectionId());
        } catch (BusinessException e) {
            throw new BusinessException(ErrorCode.REPORT_NOT_FOUND);
        }
        return report;
    }
}
