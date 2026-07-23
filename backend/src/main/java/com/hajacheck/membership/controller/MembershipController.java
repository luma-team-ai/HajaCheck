package com.hajacheck.membership.controller;

import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.global.common.ApiResponse;
import com.hajacheck.membership.dto.CheckoutRequest;
import com.hajacheck.membership.dto.MyPlanResponse;
import com.hajacheck.membership.dto.SeatsResponse;
import com.hajacheck.membership.dto.UpgradeInquiryResponse;
import com.hajacheck.membership.service.MembershipService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 마이페이지 — 내 플랜·사용량·좌석(HAJA-177). 세션 인증(anyRequest().authenticated() 대상,
 * SecurityConfig 별도 설정 불필요) — 현재 사용자는 {@code loginUser.getUserId()} 로 조회.
 */
@Tag(name = "Membership", description = "마이페이지 — 내 플랜·사용량·좌석 API")
@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class MembershipController {

    private final MembershipService membershipService;

    @Operation(summary = "내 플랜 + 사용량 조회", description = "활성 구독의 요금제 한도와 이번 달 사용량을 반환한다.")
    @GetMapping("/plan")
    public ResponseEntity<ApiResponse<MyPlanResponse>> getMyPlan(
            @AuthenticationPrincipal LoginUser loginUser) {
        return ResponseEntity.ok(ApiResponse.ok(membershipService.getMyPlan(loginUser.getUserId())));
    }

    @Operation(summary = "좌석 현황 조회", description = "회사 소속 사용자(좌석) 목록과 한도를 반환한다(조회 전용).")
    @GetMapping("/seats")
    public ResponseEntity<ApiResponse<SeatsResponse>> getSeats(
            @AuthenticationPrincipal LoginUser loginUser) {
        return ResponseEntity.ok(ApiResponse.ok(membershipService.getSeats(loginUser.getUserId())));
    }

    @Operation(summary = "업그레이드 문의", description = "PG 실결제 대체 — 구독 상태를 UPGRADE_REQUESTED 로 전이한다(멱등, 소유자만).")
    @PostMapping("/plan/upgrade-inquiry")
    public ResponseEntity<ApiResponse<UpgradeInquiryResponse>> requestUpgrade(
            @AuthenticationPrincipal LoginUser loginUser) {
        return ResponseEntity.ok(ApiResponse.ok(membershipService.requestUpgrade(loginUser.getUserId())));
    }

    @Operation(summary = "모의 결제(플랜 업그레이드)",
            description = "PG 실결제·카드청구 없는 테스트 수준 모의 결제(#711) — 기존 ACTIVE(또는 UPGRADE_REQUESTED) "
                    + "구독을 만료시키고 대상 요금제로 신규 ACTIVE 구독을 발급한다(멱등, 소유자만, STANDARD·ENTERPRISE만 허용).")
    @PostMapping("/plan/checkout")
    public ResponseEntity<ApiResponse<MyPlanResponse>> checkout(
            @AuthenticationPrincipal LoginUser loginUser,
            @Valid @RequestBody CheckoutRequest request) {
        return ResponseEntity.ok(
                ApiResponse.ok(membershipService.checkout(loginUser.getUserId(), request.planName())));
    }
}
