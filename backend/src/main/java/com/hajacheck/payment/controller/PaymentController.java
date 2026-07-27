package com.hajacheck.payment.controller;

import com.hajacheck.auth.security.LoginUser;
import com.hajacheck.global.common.ApiResponse;
import com.hajacheck.membership.dto.MyPlanResponse;
import com.hajacheck.payment.dto.PaymentConfirmRequest;
import com.hajacheck.payment.dto.PaymentHistoryResponse;
import com.hajacheck.payment.dto.PaymentOrderRequest;
import com.hajacheck.payment.dto.PaymentOrderResponse;
import com.hajacheck.payment.service.PaymentService;
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
 * 플랜 구독 결제(#988 / HAJA-489) — 토스페이먼츠 샌드박스 연동.
 *
 * <p>세션 인증({@code anyRequest().authenticated()} 대상 — SecurityConfig 별도 설정 불필요). 결제 주체는
 * 요청 바디가 아니라 항상 {@code loginUser.getUserId()} 로 정한다(사용자 id 를 파라미터로 받지 않는다).
 */
@Tag(name = "Payment", description = "플랜 구독 결제 — 주문 생성 · 승인 · 결제 내역 API")
@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @Operation(summary = "플랜 결제 주문 생성",
            description = "결제창을 띄우기 전 서버가 orderId와 청구 금액(plans.price_monthly)을 확정해 "
                    + "주문을 사전 등록한다(READY). 금액은 클라이언트가 정하지 못한다. 소유자만 요청 가능하며 "
                    + "FREE 대상·이미 사용 중인 요금제·확인 없는 하향은 거절한다.")
    @PostMapping("/plan/orders")
    public ResponseEntity<ApiResponse<PaymentOrderResponse>> createOrder(
            @AuthenticationPrincipal LoginUser loginUser,
            @Valid @RequestBody PaymentOrderRequest request) {
        return ResponseEntity.ok(
                ApiResponse.ok(paymentService.createOrder(loginUser.getUserId(), request)));
    }

    @Operation(summary = "플랜 결제 승인",
            description = "결제창이 돌려준 paymentKey/orderId/amount로 PG 승인을 요청하고, 승인에 성공한 "
                    + "경우에만 구독을 전환한다. 이미 승인된 주문의 재요청은 PG 재호출 없이 현재 플랜을 "
                    + "그대로 반환한다(멱등). 응답 스키마는 GET /api/me/plan 과 동일하다.")
    @PostMapping("/payments/confirm")
    public ResponseEntity<ApiResponse<MyPlanResponse>> confirm(
            @AuthenticationPrincipal LoginUser loginUser,
            @Valid @RequestBody PaymentConfirmRequest request) {
        return ResponseEntity.ok(
                ApiResponse.ok(paymentService.confirm(loginUser.getUserId(), request)));
    }

    @Operation(summary = "결제 내역 조회",
            description = "본인이 생성한 결제 주문 이력을 최신순으로 반환한다(#864).")
    @GetMapping("/payments")
    public ResponseEntity<ApiResponse<PaymentHistoryResponse>> getMyPayments(
            @AuthenticationPrincipal LoginUser loginUser) {
        return ResponseEntity.ok(
                ApiResponse.ok(paymentService.getMyPayments(loginUser.getUserId())));
    }
}
