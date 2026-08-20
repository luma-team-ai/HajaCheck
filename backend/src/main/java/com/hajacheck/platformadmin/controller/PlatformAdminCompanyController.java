package com.hajacheck.platformadmin.controller;

import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.global.common.ApiResponse;
import com.hajacheck.platformadmin.dto.CompanyOptionResponse;
import com.hajacheck.platformadmin.dto.CompanyVerificationActionRequest;
import com.hajacheck.platformadmin.dto.CompanyVerificationResponse;
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
 * 플랫폼 관리자 콘솔 — 기업 목록(#576, PR #626 후속 요구사항) + <b>회사 검증 무효화 킬스위치·복구</b>(#1367).
 * PLATFORM_ADMIN 인가는 SecurityConfig의 "/api/platform-admin/**" 매처가 강제한다.
 *
 * <p>회사 관리자(ROLE_ADMIN)가 쓰는 "/api/admin/**" 와는 <b>절대 겹치지 않는</b> 별도 라우트다(설계 §6).
 * 킬스위치는 자기 회사 관리자에게 열려서는 안 되는 전역 조치라 이 경계에 둔다.
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

    @Operation(summary = "기업 검증 상태 조회",
            description = "회사 스코프 차단 판단 근거(PLATFORM_ADMIN 전용) — 승인 상태·검증 상태·국세청 provenance·개업일자 유무·활성 구성원 수.")
    @GetMapping("/{companyId}/verification")
    public ResponseEntity<ApiResponse<CompanyVerificationResponse>> getVerification(
            @PathVariable Long companyId) {
        return ResponseEntity.ok(
                ApiResponse.ok(platformAdminCompanyService.getVerification(companyId)));
    }

    @Operation(summary = "기업 검증 무효화(킬스위치)",
            description = "사칭·오등록 대응으로 회사 검증을 무효화해 전 구성원의 회사 스코프를 즉시 닫는다(PLATFORM_ADMIN 전용). 사유 필수. 이미 무효화된 기업이면 409.")
    @PostMapping("/{companyId}/verification/revoke")
    public ResponseEntity<ApiResponse<CompanyVerificationResponse>> revokeVerification(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable Long companyId,
            @Valid @RequestBody CompanyVerificationActionRequest request) {
        CompanyVerificationResponse response = platformAdminCompanyService.revokeVerification(
                companyId, loginUser.getUserId(), request.reason());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @Operation(summary = "기업 검증 복구",
            description = "무효화(FAILED)를 되무른다(PLATFORM_ADMIN 전용). 관리자 무효화의 취소이고 직전 상태가 국세청 인정이면 VERIFIED 로 즉시 복원하고, 그 밖(배치 강등 등)은 PENDING 으로 되돌려 다음 재검증 배치가 국세청에 재판정하게 한다. 사유 필수. 무효화 상태가 아니면 409, PENDING 복귀 경로인데 재검증 대상이 될 수 없는 기업(개업일자 없음·반려·데모)이면 400.")
    @PostMapping("/{companyId}/verification/restore")
    public ResponseEntity<ApiResponse<CompanyVerificationResponse>> restoreVerification(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable Long companyId,
            @Valid @RequestBody CompanyVerificationActionRequest request) {
        CompanyVerificationResponse response = platformAdminCompanyService.restoreVerification(
                companyId, loginUser.getUserId(), request.reason());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @Operation(summary = "기업 검증 강제개방(override)",
            description = "국세청 판정과 무관하게 회사 스코프를 여는 조치다(PLATFORM_ADMIN 전용). 국세청이 계속 불일치(대표자 변경 등)를 응답해 복구(restore)로는 PENDING 에 고착되는 기업을 사람이 실물 확인 후 여는 경로다. "
                    + "인증 배지는 켜지지 않고(국세청이 확인해 준 것이 아니다) 재검증 대상에 남아 확정 불량(미등록·폐업) 시 배치가 자동 재차단한다. 사유 필수. 이미 검증된 기업이면 409.")
    @PostMapping("/{companyId}/verification/override")
    public ResponseEntity<ApiResponse<CompanyVerificationResponse>> overrideVerification(
            @AuthenticationPrincipal LoginUser loginUser,
            @PathVariable Long companyId,
            @Valid @RequestBody CompanyVerificationActionRequest request) {
        CompanyVerificationResponse response = platformAdminCompanyService.overrideVerification(
                companyId, loginUser.getUserId(), request.reason());
        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
