package com.hajacheck.core.report.controller;

import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.core.report.dto.FinalizeReportRequest;
import com.hajacheck.core.report.dto.ReportDetailResponse;
import com.hajacheck.core.report.dto.ReportPdfResponse;
import com.hajacheck.core.report.dto.ReportSummaryResponse;
import com.hajacheck.core.report.dto.UpdateReportContentRequest;
import com.hajacheck.core.report.service.ReportService;
import com.hajacheck.core.report.support.ReportPdfStorage;
import com.hajacheck.global.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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

    @Operation(summary = "보고서 초안 생성", description = "점검의 확정 하자를 근거로 AI 보고서 초안을 생성한다")
    @PostMapping("/api/inspections/{inspectionId}/reports")
    public ResponseEntity<ApiResponse<ReportDetailResponse>> generateDraft(
            @PathVariable Long inspectionId, @AuthenticationPrincipal LoginUser loginUser) {
        ReportDetailResponse response = reportService.generateDraft(
                inspectionId, loginUser.getCompanyId(), loginUser.getUserId());
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

    @Operation(summary = "보고서 PDF 업로드", description = "확정용 PDF 파일을 저장하고 접근 URL을 반환한다(별도로 /finalize에 전달)")
    @PostMapping("/api/reports/{id}/pdf")
    public ResponseEntity<ApiResponse<ReportPdfResponse>> uploadPdf(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal LoginUser loginUser) {
        // 소유권 검증 — 존재하지 않거나 타인 소유 보고서에 대한 PDF 업로드를 차단(IDOR 방지).
        reportService.getReport(id, loginUser.getUserId(), loginUser.getCompanyId());
        String storageKey = reportPdfStorage.store(id, file);
        String pdfUrl = "/api/reports/%d/pdf/%s".formatted(id, storageKey);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(new ReportPdfResponse(pdfUrl)));
    }

    @Operation(summary = "보고서 PDF 다운로드", description = "업로드된 보고서 PDF를 소유권 검증 후 스트리밍한다")
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
