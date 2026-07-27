package com.hajacheck.admin.controller;

import com.hajacheck.admin.dto.AdminAnalysisJobItem;
import com.hajacheck.admin.dto.AdminAnalysisJobStatus;
import com.hajacheck.admin.service.AdminAnalysisJobService;
import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.global.common.ApiResponse;
import com.hajacheck.global.common.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 콘솔 — AI 분석 현황 모니터링(신규). ADMIN 인가는 SecurityConfig의 URL 매처
 * ("/api/admin/**" → hasRole(ADMIN))가 필터 단계에서 강제한다(AdminUserController와 동일 원칙) —
 * 프론트 AdminRoute는 UX 가드일 뿐 실제 차단은 이 경계가 책임진다.
 */
@Tag(name = "Admin", description = "관리자 API")
@RestController
@RequestMapping("/api/admin/analysis-jobs")
public class AdminAnalysisJobController {

    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;

    private final AdminAnalysisJobService adminAnalysisJobService;

    public AdminAnalysisJobController(AdminAnalysisJobService adminAnalysisJobService) {
        this.adminAnalysisJobService = adminAnalysisJobService;
    }

    @Operation(summary = "AI 분석 작업 현황 조회",
            description = "요청 관리자 소속 회사의 점검 회차를 AI 분석 진행 상태(PENDING/ANALYZING/COMPLETED) 기준으로 "
                    + "조회한다(ADMIN 전용). jobId는 점검 회차 id(inspectionId)와 동일하다.")
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<AdminAnalysisJobItem>>> list(
            @AuthenticationPrincipal LoginUser loginUser,
            @RequestParam(required = false) AdminAnalysisJobStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        int safePage = Math.max(page, 0);
        int safeSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(safePage, safeSize);

        PageResponse<AdminAnalysisJobItem> response =
                adminAnalysisJobService.list(loginUser.getCompanyId(), status, pageable);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
