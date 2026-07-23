package com.hajacheck.platformadmin.controller;

import com.hajacheck.admin.dto.AdminUserRoleUpdateRequest;
import com.hajacheck.admin.dto.AdminUserRoleUpdateResponse;
import com.hajacheck.admin.dto.AdminUserStatusUpdateRequest;
import com.hajacheck.admin.dto.AdminUserStatusUpdateResponse;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.global.common.ApiResponse;
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.platformadmin.dto.PlatformAdminUserCreateRequest;
import com.hajacheck.platformadmin.dto.PlatformAdminUserListResponse;
import com.hajacheck.platformadmin.dto.PlatformAdminUserResponse;
import com.hajacheck.platformadmin.service.PlatformAdminUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 플랫폼 관리자 콘솔 — 사용자 관리(#576). PLATFORM_ADMIN 인가는 SecurityConfig 의 URL 매처
 * ("/api/platform-admin/**" → hasRole(PLATFORM_ADMIN))가 필터 단계에서 강제한다.
 *
 * <p>AdminUserController(#405, "/api/admin/users")와 완전히 분리된 별도 라우트다 — 이쪽은
 * companyId 스코프 없이 전사 사용자를 다룬다.
 */
@Tag(name = "PlatformAdmin", description = "플랫폼 관리자 API")
@RestController
@RequestMapping("/api/platform-admin/users")
public class PlatformAdminUserController {

    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;

    private final PlatformAdminUserService platformAdminUserService;

    public PlatformAdminUserController(PlatformAdminUserService platformAdminUserService) {
        this.platformAdminUserService = platformAdminUserService;
    }

    @Operation(summary = "사용자 목록 조회(전사)", description = "검색(이름·이메일·기업명)/필터(role·plan·status)/서버 페이징 + 전사 통계(PLATFORM_ADMIN 전용). companyId 스코프 없음.")
    @GetMapping
    public ResponseEntity<ApiResponse<PlatformAdminUserListResponse>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Role role,
            @RequestParam(required = false) PlanName plan,
            @RequestParam(required = false) UserStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        int safePage = Math.max(page, 0);
        int safeSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(safePage, safeSize);

        PlatformAdminUserListResponse response = platformAdminUserService.list(keyword, role, plan, status, pageable);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @Operation(summary = "사용자 등록", description = "플랫폼 관리자가 사용자 계정을 직접 생성한다(PLATFORM_ADMIN 전용). companyId가 null이면 회사 미소속(개인 계정)으로 등록된다.")
    @PostMapping
    public ResponseEntity<ApiResponse<PlatformAdminUserResponse>> create(
            @Valid @RequestBody PlatformAdminUserCreateRequest request) {
        PlatformAdminUserResponse response = platformAdminUserService.createUser(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @Operation(summary = "사용자 역할 변경", description = "대상 사용자의 role을 변경한다(PLATFORM_ADMIN 전용). 대상이 소속 회사의 마지막 활성 ADMIN이면 409.")
    @PatchMapping("/{id}/role")
    public ResponseEntity<ApiResponse<AdminUserRoleUpdateResponse>> changeRole(
            @PathVariable Long id, @Valid @RequestBody AdminUserRoleUpdateRequest request) {
        AdminUserRoleUpdateResponse response = platformAdminUserService.changeRole(id, request.role());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @Operation(summary = "사용자 상태 변경", description = "대상 사용자의 status를 변경한다(PLATFORM_ADMIN 전용). 대상이 소속 회사의 마지막 활성 ADMIN이면 409.")
    @PatchMapping("/{id}/status")
    public ResponseEntity<ApiResponse<AdminUserStatusUpdateResponse>> changeStatus(
            @PathVariable Long id, @Valid @RequestBody AdminUserStatusUpdateRequest request) {
        AdminUserStatusUpdateResponse response = platformAdminUserService.changeStatus(id, request.status());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
