package com.hajacheck.admin.controller;

import com.hajacheck.admin.dto.AdminPlanCatalogResponse;
import com.hajacheck.admin.dto.AdminPlanChangePreviewResponse;
import com.hajacheck.admin.dto.AdminPlanChangeRequest;
import com.hajacheck.admin.dto.AdminPlanHistoryResponse;
import com.hajacheck.admin.dto.AdminPlanQuotaResponse;
import com.hajacheck.admin.dto.AdminPlanResponse;
import com.hajacheck.admin.service.AdminPlanService;
import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.membership.entity.PlanName;
import com.hajacheck.global.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 관리자 콘솔 — 플랜·쿼터 관리(FR-8-A, #507 / HAJA-258, frontend #508 대응). ADMIN 인가는 SecurityConfig 의
 * URL 매처("/api/admin/**" → hasRole(ADMIN)) 가 필터 단계에서 강제한다 — 프론트 AdminRoute 는 UX 가드일 뿐
 * 실제 차단은 이 경계가 책임진다. 회사 스코프·상속 검증은 AdminPlanService 가 담당한다.
 */
@Tag(name = "Admin", description = "관리자 API")
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@Validated
public class AdminPlanController {

    private final AdminPlanService adminPlanService;

    @Operation(summary = "요금제 카탈로그 조회", description = "제공 중인 전체 요금제 목록(플랜 변경 선택지)을 반환한다(ADMIN 전용).")
    @GetMapping("/plans")
    public ResponseEntity<ApiResponse<AdminPlanCatalogResponse>> getPlanCatalog() {
        return ResponseEntity.ok(ApiResponse.ok(adminPlanService.getPlanCatalog()));
    }

    @Operation(summary = "현재 회사 플랜 + 사용량 조회", description = "요청 관리자 회사(company_id)의 현재 구독 요금제·한도와 이번 달 사용량(월 분석 장수 등)을 반환한다(ADMIN 전용).")
    @GetMapping("/plan")
    public ResponseEntity<ApiResponse<AdminPlanResponse>> getCurrentPlan(
            @AuthenticationPrincipal LoginUser loginUser) {
        return ResponseEntity.ok(ApiResponse.ok(adminPlanService.getCurrentPlan(loginUser.getUserId())));
    }

    @Operation(summary = "회사 플랜 변경 미리보기",
            description = "이 요금제로 바꾸면 어떤 구성원이 정지되고 시설물 몇 개가 읽기 전용이 되는지 부작용 없이 계산한다(#890). "
                    + "requiresConfirmation=true 면 PATCH 요청에 confirmOverflow=true 를 실어야 실제 변경된다. 회사 owner 전용. "
                    + "keepUserIds(반복 쿼리 파라미터, 예: ?planName=FREE&keepUserIds=1&keepUserIds=2)로 유지할 구성원을 직접 "
                    + "고를 수 있다(#890 Phase 2) — 미지정 시 기존 동작(id 오름차순 자동 선정).")
    @GetMapping("/plan/change-preview")
    public ResponseEntity<ApiResponse<AdminPlanChangePreviewResponse>> previewChange(
            @AuthenticationPrincipal LoginUser loginUser,
            @RequestParam PlanName planName,
            // size(@Max(100))와 일관되게 상한을 둔다(재검토 F-14).
            @Size(max = 200) @RequestParam(required = false) List<Long> keepUserIds) {
        return ResponseEntity.ok(ApiResponse.ok(
                adminPlanService.previewChange(loginUser.getUserId(), planName, keepUserIds)));
    }

    @Operation(summary = "회사 플랜 변경",
            description = "요청 관리자 회사의 구독 요금제를 변경한다 — 기존 구독을 만료하고 신규 구독을 발급한다(변경 이력은 user_plans 로 보존). ADMIN 전용. "
                    + "하향으로 한도를 넘는 구성원·시설물이 생기면 confirmOverflow=true 없이는 409(PLAN_DOWNGRADE_CONFIRMATION_REQUIRED)로 "
                    + "거절하고 아무것도 바꾸지 않는다(#890). keepUserIds 로 유지할 구성원을 직접 고를 수 있다(#890 Phase 2) — "
                    + "타 회사 id·좌석 한도 초과는 403 으로 거절한다.")
    @PatchMapping("/plan")
    public ResponseEntity<ApiResponse<AdminPlanResponse>> changePlan(
            @AuthenticationPrincipal LoginUser loginUser,
            @Valid @RequestBody AdminPlanChangeRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(adminPlanService.changePlan(
                loginUser.getUserId(), request.planName(), request.overflowConfirmed(),
                request.resolvedKeepUserIds())));
    }

    @Operation(summary = "회사 플랜 변경 이력 조회",
            description = "요청 관리자 회사의 구독 변경 이력(요금제·기간·상태)을 최신 순으로 반환한다"
                    + "(page 는 1부터 시작, ADMIN 전용).")
    @GetMapping("/plan/history")
    public ResponseEntity<ApiResponse<AdminPlanHistoryResponse>> getHistory(
            @AuthenticationPrincipal LoginUser loginUser,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok(
                ApiResponse.ok(adminPlanService.getHistory(loginUser.getUserId(), page, size)));
    }

    @Operation(summary = "회사 멤버별 쿼터 사용 현황 조회",
            description = "요청 관리자 회사에 소속된 활성 멤버별 이번 달 쿼터 사용량과 KPI 통계를 반환한다"
                    + "(page 는 1부터 시작, ADMIN 전용).")
    @GetMapping("/plan-quota")
    public ResponseEntity<ApiResponse<AdminPlanQuotaResponse>> getPlanQuota(
            @AuthenticationPrincipal LoginUser loginUser,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
            @RequestParam(required = false) String keyword) {
        return ResponseEntity.ok(ApiResponse.ok(
                adminPlanService.getPlanQuota(loginUser.getUserId(), page, size, keyword)));
    }
}
