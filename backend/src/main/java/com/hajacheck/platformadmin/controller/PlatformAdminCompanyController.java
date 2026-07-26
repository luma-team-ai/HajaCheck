package com.hajacheck.platformadmin.controller;

import com.hajacheck.auth.dto.CompanyApprovalResponse;
import com.hajacheck.auth.dto.CompanyRejectRequest;
import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.auth.service.CompanyApprovalService;
import com.hajacheck.global.common.ApiResponse;
import com.hajacheck.platformadmin.dto.CompanyOptionResponse;
import com.hajacheck.platformadmin.service.PlatformAdminCompanyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 플랫폼 관리자 콘솔 — 기업 목록(#576, PR #626 후속) + 기업 가입 승인/반려(#363).
 * PLATFORM_ADMIN 인가는 SecurityConfig의 "/api/platform-admin/**" 매처가 강제한다.
 */
@Tag(name = "PlatformAdmin", description = "플랫폼 관리자 API")
@RestController
@RequestMapping("/api/platform-admin/companies")
public class PlatformAdminCompanyController {

    private final PlatformAdminCompanyService platformAdminCompanyService;
    private final CompanyApprovalService companyApprovalService;

    public PlatformAdminCompanyController(PlatformAdminCompanyService platformAdminCompanyService,
                                           CompanyApprovalService companyApprovalService) {
        this.platformAdminCompanyService = platformAdminCompanyService;
        this.companyApprovalService = companyApprovalService;
    }

    @Operation(summary = "배정 가능 기업 목록 조회", description = "심사 승인(APPROVED)된 기업 목록(PLATFORM_ADMIN 전용) — 사용자 등록 모달의 기업명 selectbox용.")
    @GetMapping
    public ResponseEntity<ApiResponse<List<CompanyOptionResponse>>> list() {
        return ResponseEntity.ok(ApiResponse.ok(platformAdminCompanyService.listAssignableCompanies()));
    }

    @Operation(summary = "기업 가입 승인", description = "심사대기(PENDING_REVIEW) 기업을 승인한다(#363, PLATFORM_ADMIN 전용) — "
            + "사업자등록정보 검증(VERIFIED)이 끝난 기업만 승인 가능하며, 승인과 동시에 오너의 회사 스코프 관리자 권한이 열린다.")
    @PostMapping("/{id}/approve")
    public ResponseEntity<ApiResponse<CompanyApprovalResponse>> approve(
            @AuthenticationPrincipal LoginUser loginUser, @PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(companyApprovalService.approve(loginUser.getUserId(), id)));
    }

    @Operation(summary = "기업 가입 반려", description = "심사대기(PENDING_REVIEW) 기업을 반려한다(#363, PLATFORM_ADMIN 전용).")
    @PostMapping("/{id}/reject")
    public ResponseEntity<ApiResponse<CompanyApprovalResponse>> reject(
            @AuthenticationPrincipal LoginUser loginUser, @PathVariable Long id,
            @Valid @RequestBody CompanyRejectRequest request) {
        return ResponseEntity.ok(
                ApiResponse.ok(companyApprovalService.reject(loginUser.getUserId(), id, request.reason())));
    }
}
