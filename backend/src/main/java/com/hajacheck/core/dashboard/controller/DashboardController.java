package com.hajacheck.core.dashboard.controller;

import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.core.dashboard.dto.DashboardSummaryResponse;
import com.hajacheck.core.dashboard.dto.GradeDistributionResponse;
import com.hajacheck.core.dashboard.dto.PendingPriorityResponse;
import com.hajacheck.core.dashboard.dto.RecentInspectionResponse;
import com.hajacheck.core.dashboard.dto.UpcomingInspectionResponse;
import com.hajacheck.core.dashboard.service.DashboardService;
import com.hajacheck.global.common.ApiResponse;
import com.hajacheck.global.common.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 대시보드 개요 집계 API(HAJA-17, dev-03-01). 회사 스코프는 인증 사용자(@AuthenticationPrincipal)로부터만
 * 취득 — 요청 바디/파라미터로 companyId 를 받지 않는다(cross-company IDOR 방지, facility API와 동일 원칙).
 */
@Tag(name = "Dashboard", description = "대시보드 개요 집계 API")
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
@Validated
public class DashboardController {

    private final DashboardService dashboardService;

    @Operation(summary = "대시보드 요약 카드", description = "로그인 사용자 소유 시설물 기준 요약 지표 4종을 반환한다")
    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<DashboardSummaryResponse>> getSummary(
            @AuthenticationPrincipal LoginUser loginUser) {
        return ResponseEntity.ok(ApiResponse.ok(
                dashboardService.getSummary(loginUser.getUserId(), loginUser.getCompanyId())));
    }

    @Operation(summary = "등급 분포", description = "로그인 사용자 소유 시설물의 결함 등급(A~E) 분포를 반환한다")
    @GetMapping("/grade-distribution")
    public ResponseEntity<ApiResponse<List<GradeDistributionResponse>>> getGradeDistribution(
            @AuthenticationPrincipal LoginUser loginUser) {
        return ResponseEntity.ok(ApiResponse.ok(
                dashboardService.getGradeDistribution(loginUser.getUserId(), loginUser.getCompanyId())));
    }

    @Operation(summary = "조치 우선순위 목록", description = "로그인 사용자 소유 시설물의 조치대기 결함을 등급·최신순으로 반환한다")
    @GetMapping("/pending-priority")
    public ResponseEntity<ApiResponse<List<PendingPriorityResponse>>> getPendingPriority(
            @AuthenticationPrincipal LoginUser loginUser) {
        return ResponseEntity.ok(ApiResponse.ok(
                dashboardService.getPendingPriority(loginUser.getUserId(), loginUser.getCompanyId())));
    }

    @Operation(summary = "최근 점검 목록", description = "로그인 사용자 소유 시설물의 최근 점검을 점검일 최신순으로 반환한다")
    @GetMapping("/recent-inspections")
    public ResponseEntity<ApiResponse<List<RecentInspectionResponse>>> getRecentInspections(
            @AuthenticationPrincipal LoginUser loginUser) {
        return ResponseEntity.ok(ApiResponse.ok(
                dashboardService.getRecentInspections(loginUser.getUserId(), loginUser.getCompanyId())));
    }

    @Operation(summary = "최근 점검 전체보기(페이지네이션+검색)",
            description = "로그인 사용자 소유 시설물의 점검을 시설물/상태(한글 라벨)/텍스트(시설물명·담당자명)로 "
                    + "필터링해 페이지 단위로 반환한다. /recent-inspections(위젯, 상위 10건 고정)와 별개 엔드포인트 — "
                    + "위젯 호출 경로에는 영향이 없다.")
    @GetMapping("/recent-inspections/search")
    public ResponseEntity<ApiResponse<PageResponse<RecentInspectionResponse>>> searchRecentInspections(
            @AuthenticationPrincipal LoginUser loginUser,
            @RequestParam(required = false) Long facilityId,
            @RequestParam(required = false) String facilityType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String query,
            @ParameterObject @PageableDefault(size = 10) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(dashboardService.searchRecentInspections(
                loginUser.getUserId(), loginUser.getCompanyId(), facilityId, facilityType, status, query,
                pageable)));
    }

    @Operation(summary = "다가오는 점검 예정 시설물 조회",
            description = "로그인 사용자 소유 시설물 중 오늘부터 days 일 이내로 점검이 임박한 것을 최대 limit 건 반환한다")
    @GetMapping("/upcoming-inspections")
    public ResponseEntity<ApiResponse<List<UpcomingInspectionResponse>>> getUpcomingInspections(
            @AuthenticationPrincipal LoginUser loginUser,
            @RequestParam(defaultValue = "30") @Min(1) @Max(365) int days,
            @RequestParam(defaultValue = "5") @Min(1) int limit) {
        return ResponseEntity.ok(ApiResponse.ok(
                dashboardService.getUpcomingInspections(
                        loginUser.getUserId(), loginUser.getCompanyId(), days, limit)));
    }
}
