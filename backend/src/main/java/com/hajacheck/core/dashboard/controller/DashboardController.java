package com.hajacheck.core.dashboard.controller;

import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.core.dashboard.dto.DashboardSummaryResponse;
import com.hajacheck.core.dashboard.dto.GradeDistributionResponse;
import com.hajacheck.core.dashboard.dto.PendingPriorityResponse;
import com.hajacheck.core.dashboard.dto.RecentInspectionResponse;
import com.hajacheck.core.dashboard.service.DashboardService;
import com.hajacheck.global.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 대시보드 개요 집계 API(HAJA-17, dev-03-01). 소유자(owner)는 인증 사용자(@AuthenticationPrincipal)로부터만
 * 취득 — 요청 바디/파라미터로 ownerId 를 받지 않는다(cross-owner IDOR 방지, facility API와 동일 원칙).
 */
@Tag(name = "Dashboard", description = "대시보드 개요 집계 API")
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @Operation(summary = "대시보드 요약 카드", description = "로그인 사용자 소유 시설물 기준 요약 지표 4종을 반환한다")
    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<DashboardSummaryResponse>> getSummary(
            @AuthenticationPrincipal LoginUser loginUser) {
        return ResponseEntity.ok(ApiResponse.ok(dashboardService.getSummary(loginUser.getUserId())));
    }

    @Operation(summary = "등급 분포", description = "로그인 사용자 소유 시설물의 결함 등급(A~E) 분포를 반환한다")
    @GetMapping("/grade-distribution")
    public ResponseEntity<ApiResponse<List<GradeDistributionResponse>>> getGradeDistribution(
            @AuthenticationPrincipal LoginUser loginUser) {
        return ResponseEntity.ok(ApiResponse.ok(dashboardService.getGradeDistribution(loginUser.getUserId())));
    }

    @Operation(summary = "조치 우선순위 목록", description = "로그인 사용자 소유 시설물의 조치대기 결함을 등급·최신순으로 반환한다")
    @GetMapping("/pending-priority")
    public ResponseEntity<ApiResponse<List<PendingPriorityResponse>>> getPendingPriority(
            @AuthenticationPrincipal LoginUser loginUser) {
        return ResponseEntity.ok(ApiResponse.ok(dashboardService.getPendingPriority(loginUser.getUserId())));
    }

    @Operation(summary = "최근 점검 목록", description = "로그인 사용자 소유 시설물의 최근 점검을 점검일 최신순으로 반환한다")
    @GetMapping("/recent-inspections")
    public ResponseEntity<ApiResponse<List<RecentInspectionResponse>>> getRecentInspections(
            @AuthenticationPrincipal LoginUser loginUser) {
        return ResponseEntity.ok(ApiResponse.ok(dashboardService.getRecentInspections(loginUser.getUserId())));
    }
}
