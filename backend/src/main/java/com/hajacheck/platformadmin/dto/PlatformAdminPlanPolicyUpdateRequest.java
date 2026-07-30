package com.hajacheck.platformadmin.dto;

import com.hajacheck.membership.entity.PlanName;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.List;

/**
 * PUT /api/platform-admin/plans 요청 — FREE/STANDARD/ENTERPRISE 3개 플랜의 가격·한도·기능 제공 여부를
 * 한 번에 교체한다(플랫폼 관리자 "플랜 정책 설정", 사용자 지시). plans 는 정확히 3건, PlanName 각각
 * 한 번씩이어야 한다(서비스에서 검증 — Bean Validation만으로는 "정확히 이 3개 값 각 1회"를 표현할 수 없음).
 */
public record PlatformAdminPlanPolicyUpdateRequest(
        @NotEmpty @Valid List<Entry> plans) {

    public record Entry(
            @NotNull PlanName name,
            @NotNull @DecimalMin(value = "0", message = "월 구독 가격은 0 이상이어야 합니다.") BigDecimal priceMonthly,
            // null = 무제한(plans 테이블 nullable과 대응) — 값이 있으면 1 이상이어야 한다.
            @Positive Integer maxFacilities,
            @Positive Integer maxMonthlyAnalyses,
            @Positive Integer maxSeats,
            boolean hasPdfWatermark,
            boolean hasCounselorAccess) {
    }
}
