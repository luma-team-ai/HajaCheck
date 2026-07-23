package com.hajacheck.platformadmin.controller;

import com.hajacheck.global.common.ApiResponse;
import com.hajacheck.platformadmin.dto.PlatformAdminPlanCatalogResponse;
import com.hajacheck.platformadmin.dto.PlatformAdminPlanPolicyUpdateRequest;
import com.hajacheck.platformadmin.service.PlatformAdminPlanPolicyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 플랫폼 관리자 콘솔 — 플랜 정책 설정(#624 후속). PLATFORM_ADMIN 인가는 SecurityConfig 의 URL 매처
 * ("/api/platform-admin/**" → hasRole(PLATFORM_ADMIN))가 필터 단계에서 강제한다.
 *
 * <p>AdminPlanController(#507, "/api/admin/plans")는 조회만 허용하는 반면, 이 경로는 PLATFORM_ADMIN
 * 에게만 수정(PUT)까지 허용한다 — plans 는 회사 스코프 없는 전역 데이터라 두 경로가 같은 테이블을 보되
 * 권한 경계만 다르다.
 */
@Tag(name = "PlatformAdmin", description = "플랫폼 관리자 API")
@RestController
@RequestMapping("/api/platform-admin/plans")
@RequiredArgsConstructor
public class PlatformAdminPlanPolicyController {

    private final PlatformAdminPlanPolicyService platformAdminPlanPolicyService;

    @Operation(summary = "요금제 정책 카탈로그 조회",
            description = "FREE/STANDARD/ENTERPRISE 전체 요금제의 가격·한도·기능 제공 여부를 반환한다(PLATFORM_ADMIN 전용).")
    @GetMapping
    public ResponseEntity<ApiResponse<PlatformAdminPlanCatalogResponse>> getCatalog() {
        return ResponseEntity.ok(ApiResponse.ok(platformAdminPlanPolicyService.getCatalog()));
    }

    @Operation(summary = "요금제 정책 일괄 변경",
            description = "FREE/STANDARD/ENTERPRISE 3개 플랜의 가격·한도·기능 제공 여부를 한 번에 교체한다"
                    + "(PLATFORM_ADMIN 전용). 3개 플랜명을 각각 정확히 한 번씩 포함해야 한다.")
    @PutMapping
    public ResponseEntity<ApiResponse<PlatformAdminPlanCatalogResponse>> updatePolicies(
            @Valid @RequestBody PlatformAdminPlanPolicyUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(platformAdminPlanPolicyService.updatePolicies(request)));
    }
}
