package com.hajacheck.platformadmin.controller;

import com.hajacheck.global.common.ApiResponse;
import com.hajacheck.platformadmin.dto.SystemMonitoringResponse;
import com.hajacheck.platformadmin.service.PlatformAdminMonitoringService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 플랫폼 관리자 콘솔 — 시스템 모니터링(#728, frontend PlatformAdminMonitoringPage.tsx 대응). PLATFORM_ADMIN
 * 인가는 SecurityConfig 의 URL 매처("/api/platform-admin/**" → hasRole(PLATFORM_ADMIN))가 필터 단계에서
 * 강제한다.
 */
@Tag(name = "PlatformAdmin", description = "플랫폼 관리자 API")
@RestController
@RequestMapping("/api/platform-admin/monitoring")
@RequiredArgsConstructor
public class PlatformAdminMonitoringController {

    private final PlatformAdminMonitoringService platformAdminMonitoringService;

    @Operation(summary = "시스템 모니터링 조회",
            description = "서버 헬스체크(API 서버/AI 분석 서버/DB) + 서버 자원(CPU/메모리/디스크) + 최근 에러 로그"
                    + "(PLATFORM_ADMIN 전용). 분석 잡 큐는 AI 자동 분석 파이프라인 도입 전까지 항상 빈 값을 반환한다.")
    @GetMapping
    public ResponseEntity<ApiResponse<SystemMonitoringResponse>> getMonitoring() {
        return ResponseEntity.ok(ApiResponse.ok(platformAdminMonitoringService.getMonitoring()));
    }
}
