package com.hajacheck.core.statistics.controller;

import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.core.statistics.dto.DefectTypeDistributionResponse;
import com.hajacheck.core.statistics.dto.FacilitySummaryResponse;
import com.hajacheck.core.statistics.dto.FacilityTypeHeatmapResponse;
import com.hajacheck.core.statistics.dto.MonthlyDefectTrendResponse;
import com.hajacheck.core.statistics.dto.StatisticsGradeDistributionResponse;
import com.hajacheck.core.statistics.dto.StatisticsKpiSummaryResponse;
import com.hajacheck.core.statistics.service.StatisticsService;
import com.hajacheck.global.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Statistics", description = "통계 API")
@RestController
@RequestMapping("/api/statistics")
@RequiredArgsConstructor
public class StatisticsController {

    private final StatisticsService statisticsService;

    @Operation(summary = "통계 KPI 요약", description = "로그인 사용자의 회사 시설물 기준 통계 KPI를 반환한다")
    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<StatisticsKpiSummaryResponse>> getSummary(
            @AuthenticationPrincipal LoginUser loginUser,
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String facilityId) {
        return ResponseEntity.ok(ApiResponse.ok(statisticsService.getSummary(
                loginUser.getUserId(), loginUser.getCompanyId(), period, facilityId)));
    }

    @Operation(summary = "월별 하자 추이", description = "로그인 사용자의 회사 시설물 기준 월별 하자 건수를 반환한다")
    @GetMapping("/monthly-trend")
    public ResponseEntity<ApiResponse<List<MonthlyDefectTrendResponse>>> getMonthlyTrend(
            @AuthenticationPrincipal LoginUser loginUser,
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String facilityId) {
        return ResponseEntity.ok(ApiResponse.ok(statisticsService.getMonthlyTrend(
                loginUser.getUserId(), loginUser.getCompanyId(), period, facilityId)));
    }

    @Operation(summary = "하자 유형별 분포", description = "로그인 사용자의 회사 시설물 기준 하자 유형별 건수를 반환한다")
    @GetMapping("/defect-type-distribution")
    public ResponseEntity<ApiResponse<List<DefectTypeDistributionResponse>>> getDefectTypeDistribution(
            @AuthenticationPrincipal LoginUser loginUser,
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String facilityId) {
        return ResponseEntity.ok(ApiResponse.ok(statisticsService.getDefectTypeDistribution(
                loginUser.getUserId(), loginUser.getCompanyId(), period, facilityId)));
    }

    @Operation(summary = "하자 등급별 분포", description = "로그인 사용자의 회사 시설물 기준 하자 등급 분포를 반환한다")
    @GetMapping("/grade-distribution")
    public ResponseEntity<ApiResponse<List<StatisticsGradeDistributionResponse>>> getGradeDistribution(
            @AuthenticationPrincipal LoginUser loginUser,
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String facilityId) {
        return ResponseEntity.ok(ApiResponse.ok(statisticsService.getGradeDistribution(
                loginUser.getUserId(), loginUser.getCompanyId(), period, facilityId)));
    }

    @Operation(summary = "시설물군별 히트맵", description = "로그인 사용자의 회사 시설물군과 월 기준 하자 건수를 반환한다")
    @GetMapping("/facility-type-heatmap")
    public ResponseEntity<ApiResponse<List<FacilityTypeHeatmapResponse>>> getFacilityTypeHeatmap(
            @AuthenticationPrincipal LoginUser loginUser,
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String facilityId) {
        return ResponseEntity.ok(ApiResponse.ok(statisticsService.getFacilityTypeHeatmap(
                loginUser.getUserId(), loginUser.getCompanyId(), period, facilityId)));
    }

    @Operation(summary = "시설물별 통계 요약", description = "로그인 사용자의 회사 시설물별 하자 요약을 반환한다")
    @GetMapping("/facility-summary")
    public ResponseEntity<ApiResponse<List<FacilitySummaryResponse>>> getFacilitySummary(
            @AuthenticationPrincipal LoginUser loginUser,
            @RequestParam(required = false) String period,
            @RequestParam(required = false) String facilityId) {
        return ResponseEntity.ok(ApiResponse.ok(statisticsService.getFacilitySummary(
                loginUser.getUserId(), loginUser.getCompanyId(), period, facilityId)));
    }
}
