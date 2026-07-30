package com.hajacheck.platformadmin.controller;

import com.hajacheck.global.common.ApiResponse;
import com.hajacheck.platformadmin.dto.PlatformAdminServiceStatsResponse;
import com.hajacheck.platformadmin.service.PlatformAdminServiceStatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 플랫폼 관리자 콘솔 — 서비스 통계(#633, frontend PlatformAdminStatsPage.tsx 대응). PLATFORM_ADMIN 인가는
 * SecurityConfig 의 URL 매처("/api/platform-admin/**" → hasRole(PLATFORM_ADMIN))가 필터 단계에서 강제한다.
 * companyId 스코프 없이 플랫폼 전체를 집계한다.
 */
@Tag(name = "PlatformAdmin", description = "플랫폼 관리자 API")
@RestController
@RequestMapping("/api/platform-admin/stats")
@RequiredArgsConstructor
public class PlatformAdminStatsController {

    private final PlatformAdminServiceStatsService platformAdminServiceStatsService;

    @Operation(summary = "서비스 통계 조회",
            description = "가입자/분석 요청/상담 건수 KPI + 최근 6개월 추이·플랜 분포·월별 요약(PLATFORM_ADMIN 전용).")
    @GetMapping
    public ResponseEntity<ApiResponse<PlatformAdminServiceStatsResponse>> getStats() {
        return ResponseEntity.ok(ApiResponse.ok(platformAdminServiceStatsService.getStats()));
    }
}
