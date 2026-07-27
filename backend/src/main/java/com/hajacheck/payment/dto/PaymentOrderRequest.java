package com.hajacheck.payment.dto;

import com.hajacheck.membership.entity.PlanName;
import jakarta.validation.constraints.NotNull;

/**
 * POST /api/me/plan/orders 요청(#988 / HAJA-489) — 결제할 대상 요금제.
 *
 * <p><b>금액은 받지 않는다</b>: 청구 금액은 서버가 {@code plans.price_monthly} 로 정한다(보안 요구 1).
 * planName 은 PlanName enum 이라 잘못된 값은 역직렬화 단계에서 INVALID_INPUT(400)으로 떨어지고,
 * FREE 는 서비스 단에서 거부한다(다운그레이드는 결제 흐름이 아니다).
 */
public record PaymentOrderRequest(@NotNull PlanName planName) {
}
