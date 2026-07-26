package com.hajacheck.platformadmin.controller;

import com.hajacheck.global.common.ApiResponse;
import com.hajacheck.platformadmin.dto.CompanyOptionResponse;
import com.hajacheck.platformadmin.service.PlatformAdminCompanyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 플랫폼 관리자 콘솔 — 사용자 등록 모달의 기업명 selectbox 후보 목록(#576, PR #626 후속 요구사항).
 * PLATFORM_ADMIN 인가는 SecurityConfig의 "/api/platform-admin/**" 매처가 강제한다.
 */
@Tag(name = "PlatformAdmin", description = "플랫폼 관리자 API")
@RestController
@RequestMapping("/api/platform-admin/companies")
public class PlatformAdminCompanyController {

    private final PlatformAdminCompanyService platformAdminCompanyService;

    public PlatformAdminCompanyController(PlatformAdminCompanyService platformAdminCompanyService) {
        this.platformAdminCompanyService = platformAdminCompanyService;
    }

    @Operation(summary = "배정 가능 기업 목록 조회", description = "심사 승인(APPROVED)된 기업 목록(PLATFORM_ADMIN 전용) — 사용자 등록 모달의 기업명 selectbox용.")
    @GetMapping
    public ResponseEntity<ApiResponse<List<CompanyOptionResponse>>> list() {
        return ResponseEntity.ok(ApiResponse.ok(platformAdminCompanyService.listAssignableCompanies()));
    }
}
