package com.hajacheck.admin.controller;

import com.hajacheck.admin.dto.AdminUserCreateRequest;
import com.hajacheck.admin.dto.AdminUserListResponse;
import com.hajacheck.admin.dto.AdminUserResponse;
import com.hajacheck.admin.dto.AdminUserRoleUpdateRequest;
import com.hajacheck.admin.dto.AdminUserRoleUpdateResponse;
import com.hajacheck.admin.dto.AdminUserStatusUpdateRequest;
import com.hajacheck.admin.dto.AdminUserStatusUpdateResponse;
import com.hajacheck.admin.service.AdminUserService;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.global.common.ApiResponse;
import com.hajacheck.membership.entity.PlanName;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 콘솔 — 사용자 관리(#405, frontend #378 대응). ADMIN 인가는 SecurityConfig 의 URL 매처
 * ("/api/admin/**" → hasRole(ADMIN)) 가 필터 단계에서 강제한다 — 프론트 AdminRoute 는 UX 가드일 뿐
 * 실제 차단은 이 경계가 책임진다.
 */
@Tag(name = "Admin", description = "관리자 API")
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;

    private final AdminUserService adminUserService;

    public AdminUserController(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    @Operation(summary = "사용자 목록 조회", description = "검색(이름·이메일)/필터(role·plan·status)/서버 페이징 + 요청 관리자 소속 회사 기준 통계(ADMIN 전용). 요청 관리자와 같은 회사 소속 사용자만 조회된다.")
    @GetMapping
    public ResponseEntity<ApiResponse<AdminUserListResponse>> list(
            @AuthenticationPrincipal LoginUser loginUser,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Role role,
            @RequestParam(required = false) PlanName plan,
            @RequestParam(required = false) UserStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        int safePage = Math.max(page, 0);
        int safeSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(safePage, safeSize);

        AdminUserListResponse response =
                adminUserService.list(loginUser.getCompanyId(), keyword, role, plan, status, pageable);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @Operation(summary = "사용자 등록", description = "관리자가 직접 사용자 계정을 생성한다(ADMIN 전용). 등록된 계정은 요청한 관리자와 같은 회사에 배선된다.")
    @PostMapping
    public ResponseEntity<ApiResponse<AdminUserResponse>> create(
            @AuthenticationPrincipal LoginUser loginUser,
            @Valid @RequestBody AdminUserCreateRequest request) {
        AdminUserResponse response = adminUserService.createUser(request, loginUser.getCompanyId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }

    @Operation(summary = "사용자 역할 변경", description = "대상 사용자의 role을 변경한다(ADMIN 전용). 요청 관리자와 다른 회사 소속이면 404.")
    @PatchMapping("/{id}/role")
    public ResponseEntity<ApiResponse<AdminUserRoleUpdateResponse>> changeRole(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable Long id, @Valid @RequestBody AdminUserRoleUpdateRequest request) {
        AdminUserRoleUpdateResponse response =
                adminUserService.changeRole(id, request.role(), loginUser.getCompanyId());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @Operation(summary = "사용자 상태 변경", description = "대상 사용자의 status를 변경한다(ADMIN 전용). 요청 관리자와 다른 회사 소속이면 404.")
    @PatchMapping("/{id}/status")
    public ResponseEntity<ApiResponse<AdminUserStatusUpdateResponse>> changeStatus(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable Long id, @Valid @RequestBody AdminUserStatusUpdateRequest request) {
        AdminUserStatusUpdateResponse response =
                adminUserService.changeStatus(id, request.status(), loginUser.getCompanyId());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
