package com.hajacheck.core.report.controller;

import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.core.report.dto.FinalizeReportRequest;
import com.hajacheck.core.report.dto.GenerateDraftRequest;
import com.hajacheck.core.report.dto.ReportDetailResponse;
import com.hajacheck.core.report.dto.ReportPdfResponse;
import com.hajacheck.core.report.dto.ReportSummaryResponse;
import com.hajacheck.core.report.dto.CompanyReportListItemResponse;
import com.hajacheck.core.report.dto.CompanyReportSummaryResponse;
import com.hajacheck.core.report.entity.ReportStatus;
import com.hajacheck.core.report.dto.UpdateReportContentRequest;
import com.hajacheck.core.report.service.ReportService;
import com.hajacheck.core.report.support.ReportPdfStorage;
import com.hajacheck.global.common.ApiResponse;
import com.hajacheck.global.common.PageResponse;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 점검 결과 기반 보고서 생성·조회·편집·확정 API(#446 / HAJA-283). 소유권 검증은 전부 서비스 계층
 * (ReportService → InspectionService.getInspection)에 위임한다. 회사 스코프와 변경 액터 사용자 식별자는
 * 각각 {@link LoginUser#getCompanyId()}와 {@link LoginUser#getUserId()}에서 분리해 전달한다.
 */
@Tag(name = "Report", description = "보고서 API")
@RestController
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;
    private final ReportPdfStorage reportPdfStorage;

    @Operation(summary = "회사 보고서 목록", description = "현재 회사의 보고서를 필터·페이지·정렬 조건으로 조회한다")
    @GetMapping("/api/reports")
    public ResponseEntity<ApiResponse<PageResponse<CompanyReportListItemResponse>>> listCompanyReports(
            @AuthenticationPrincipal LoginUser loginUser,
            @RequestParam(required = false) Long facilityId,
            @RequestParam(required = false) Integer roundNo,
            @RequestParam(required = false) ReportStatus status,
            @RequestParam(required = false, defaultValue = "") String query,
            @RequestParam(required = false, defaultValue = "ALL") String period,
            @ParameterObject @PageableDefault(size = 10, sort = "updatedAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(reportService.listCompanyReports(
                loginUser.getUserId(), loginUser.getCompanyId(), facilityId, roundNo, status, query, period,
                pageable)));
    }

    @Operation(summary = "회사 보고서 요약", description = "회사 보고서 KPI를 조회한다")
    @GetMapping("/api/reports/summary")
    public ResponseEntity<ApiResponse<CompanyReportSummaryResponse>> companyReportsSummary(
            @AuthenticationPrincipal LoginUser loginUser) {
        return ResponseEntity.ok(ApiResponse.ok(reportService.companyReportsSummary(
                loginUser.getUserId(), loginUser.getCompanyId())));
    }

    @Operation(summary = "보고서 초안 생성", description = "점검의 확정 하자를 근거로 AI 보고서 초안을 생성한다")
    @PostMapping("/api/inspections/{inspectionId}/reports")
    public ResponseEntity<ApiResponse<ReportDetailResponse>> generateDraft(
            @PathVariable Long inspectionId,
            @Valid @RequestBody(required = false) GenerateDraftRequest request,
            @AuthenticationPrincipal LoginUser loginUser) {
        Set<String> sections = request != null ? request.sections() : null;
        Boolean includePhoto = request != null ? request.includePhoto() : null;
        ReportDetailResponse response = reportService.generateDraft(
                inspectionId, loginUser.getCompanyId(), loginUser.getUserId(),
                sections, includePhoto);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @Operation(summary = "보고서 버전 목록", description = "점검에 속한 보고서 버전을 최신순으로 조회한다")
    @GetMapping("/api/inspections/{inspectionId}/reports")
    public ResponseEntity<ApiResponse<List<ReportSummaryResponse>>> listReports(
            @PathVariable Long inspectionId, @AuthenticationPrincipal LoginUser loginUser) {
        List<ReportSummaryResponse> response = reportService.listReports(
                inspectionId, loginUser.getUserId(), loginUser.getCompanyId());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @Operation(summary = "보고서 상세 조회", description = "보고서 단건을 콘텐츠(JSON)와 함께 조회한다")
    @GetMapping("/api/reports/{id}")
    public ResponseEntity<ApiResponse<ReportDetailResponse>> getReport(
            @PathVariable Long id, @AuthenticationPrincipal LoginUser loginUser) {
        ReportDetailResponse response =
                reportService.getReport(id, loginUser.getUserId(), loginUser.getCompanyId());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @Operation(summary = "보고서 복제", description = "기존 보고서 content를 복제해 같은 점검의 다음 버전 DRAFT를 생성한다")
    @PostMapping("/api/reports/{id}/clone")
    public ResponseEntity<ApiResponse<ReportDetailResponse>> cloneReport(
            @PathVariable Long id, @AuthenticationPrincipal LoginUser loginUser) {
        ReportDetailResponse response =
                reportService.cloneReport(id, loginUser.getCompanyId(), loginUser.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @Operation(summary = "보고서 본문 수정", description = "DRAFT 상태 보고서의 본문(JSON)을 수정한다 — 수정 시 grounding 판정은 초기화된다")
    @PatchMapping("/api/reports/{id}")
    public ResponseEntity<ApiResponse<ReportDetailResponse>> updateContent(
            @PathVariable Long id,
            @Valid @RequestBody UpdateReportContentRequest request,
            @AuthenticationPrincipal LoginUser loginUser) {
        ReportDetailResponse response =
                reportService.updateContent(
                        id, request.contentJson(), loginUser.getCompanyId(), loginUser.getUserId());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @Operation(summary = "보고서 근거 재검증(구조 검증)",
            description = "AI 서버 재호출 없이 본문(detail.items)과 확정 하자 목록을 유형+등급 기준으로 대조해 "
                    + "grounding 판정을 복구한다 — 편집(PATCH) 후 확정이 막힌 DRAFT 보고서에만 사용한다")
    @PostMapping("/api/reports/{id}/grounding-recheck")
    public ResponseEntity<ApiResponse<ReportDetailResponse>> recheckGrounding(
            @PathVariable Long id, @AuthenticationPrincipal LoginUser loginUser) {
        ReportDetailResponse response =
                reportService.recheckGrounding(id, loginUser.getCompanyId(), loginUser.getUserId());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @Operation(summary = "보고서 확정", description = "근거 검증을 통과한 DRAFT 보고서를 PDF와 함께 확정(FINALIZED)한다")
    @PostMapping("/api/reports/{id}/finalize")
    public ResponseEntity<ApiResponse<ReportDetailResponse>> finalizeReport(
            @PathVariable Long id,
            @Valid @RequestBody FinalizeReportRequest request,
            @AuthenticationPrincipal LoginUser loginUser) {
        ReportDetailResponse response =
                reportService.finalizeReport(
                        id, request.pdfUrl(), loginUser.getCompanyId(), loginUser.getUserId());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @Operation(summary = "보고서 초안 삭제", description = "DRAFT 보고서만 soft delete 처리한다. FINALIZED 보고서는 삭제할 수 없다")
    @DeleteMapping("/api/reports/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteDraftReport(
            @PathVariable Long id,
            @AuthenticationPrincipal LoginUser loginUser) {
        reportService.deleteDraftReport(id, loginUser.getCompanyId(), loginUser.getUserId());
        return ResponseEntity.ok(ApiResponse.ok(null));
    }

    @Operation(summary = "보고서 PDF 업로드", description = "확정용 PDF 파일을 저장하고 접근 URL을 반환한다(별도로 /finalize에 전달)")
    @PostMapping("/api/reports/{id}/pdf")
    public ResponseEntity<ApiResponse<ReportPdfResponse>> uploadPdf(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal LoginUser loginUser) {
        // 소유권 및 DRAFT 상태 검증 — 존재하지 않거나 타인 소유 또는 이미 확정된 보고서에 대한 PDF 업로드를 차단.
        ReportDetailResponse report = reportService.getReport(id, loginUser.getUserId(), loginUser.getCompanyId());
        if (report.status() != ReportStatus.DRAFT) {
            throw new BusinessException(ErrorCode.FINALIZED_REPORT_IMMUTABLE);
        }
        String storageKey = reportPdfStorage.store(id, file);
        String pdfUrl = "/api/reports/%d/pdf/%s".formatted(id, storageKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(new ReportPdfResponse(pdfUrl)));
    }

    @Operation(summary = "보고서 PDF 다운로드", description = "업로드된 보고서 PDF를 소유권 검증 후 스트리밍한다")
    // 문서 전용 표기 — 전역 default-produces-media-type(JSON)이 PDF 응답을 JSON으로 오문서화하는 걸 덮는다.
    // @GetMapping(produces=)는 MVC 요청 매칭까지 좁히므로 쓰지 않는다.
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "보고서 PDF 바이너리",
            content = @Content(mediaType = MediaType.APPLICATION_PDF_VALUE,
                    schema = @Schema(type = "string", format = "binary")))
    @GetMapping("/api/reports/{id}/pdf/{storageKey}")
    public ResponseEntity<Resource> downloadPdf(
            @PathVariable Long id,
            @PathVariable String storageKey,
            @AuthenticationPrincipal LoginUser loginUser) {
        // 소유권 검증 — 존재하지 않거나 타인 소유 보고서의 PDF 열람을 차단(IDOR 방지). 정적 리소스
        // 핸들러로 직접 서빙하지 않는 이유(#455 P2-1)가 바로 이 검증을 강제하기 위함이다.
        reportService.getReport(id, loginUser.getUserId(), loginUser.getCompanyId());
        Resource resource = reportPdfStorage.load(id, storageKey);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).body(resource);
    }
}
