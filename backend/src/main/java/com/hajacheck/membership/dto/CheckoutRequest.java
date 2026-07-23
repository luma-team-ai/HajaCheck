package com.hajacheck.membership.dto;

import com.hajacheck.membership.entity.PlanName;
import jakarta.validation.constraints.NotNull;

/**
 * POST /api/me/plan/checkout 요청 — 모의 결제(PG 실결제 없음, #711) 대상 요금제.
 * planName 은 PlanName enum(FREE/STANDARD/ENTERPRISE) — 잘못된 값은 역직렬화 단계에서 400.
 * FREE 는 서비스 단에서 INVALID_INPUT 으로 거부한다(업그레이드 결제 대체 흐름만 다루는 범위).
 */
public record CheckoutRequest(@NotNull PlanName planName) {
}
