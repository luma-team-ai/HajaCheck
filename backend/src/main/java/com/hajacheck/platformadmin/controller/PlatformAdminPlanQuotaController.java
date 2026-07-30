package com.hajacheck.platformadmin.controller;

import com.hajacheck.global.common.ApiResponse;
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.platformadmin.dto.PlatformAdminPlanQuotaResponse;
import com.hajacheck.platformadmin.service.PlatformAdminPlanQuotaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 플랫폼 관리자 콘솔 — 플랜·쿼터 관리(#624). PLATFORM_ADMIN 인가는 SecurityConfig 의 URL 매처
 * ("/api/platform-admin/**" → hasRole(PLATFORM_ADMIN))가 필터 단계에서 강제한다.
 *
 * <p>AdminPlanController(#507, "/api/admin/plan-quota")와 완전히 분리된 별도 라우트다 — 이쪽은
 * companyId 스코프 없이 전사 사용자를 다룬다.
 */
@Tag(name = "PlatformAdmin", description = "플랫폼 관리자 API")
@RestController
@RequestMapping("/api/platform-admin/plans-quota")
@RequiredArgsConstructor
@Validated
public class PlatformAdminPlanQuotaController {

    private final PlatformAdminPlanQuotaService platformAdminPlanQuotaService;

    @Operation(summary = "전사 사용자별 플랜·쿼터 사용 현황 조회",
            description = "검색(이름·이메일·기업명)/필터(plan)/서버 페이징 + 전사 KPI 통계(PLATFORM_ADMIN 전용). "
                    + "companyId 스코프 없음. page 는 1부터 시작.")
    @GetMapping
    public ResponseEntity<ApiResponse<PlatformAdminPlanQuotaResponse>> getPlanQuota(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) PlanName plan) {
        return ResponseEntity.ok(
                ApiResponse.ok(platformAdminPlanQuotaService.getPlanQuota(page, size, keyword, plan)));
    }
}
