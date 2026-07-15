package com.hajacheck.membership.dto;

import com.hajacheck.membership.entity.UserPlan;

/**
 * POST /api/me/plan/upgrade-inquiry 응답 — 계약(contract.md "마이페이지" v1) 그대로.
 */
public record UpgradeInquiryResponse(String status) {

    public static UpgradeInquiryResponse from(UserPlan userPlan) {
        return new UpgradeInquiryResponse(userPlan.getStatus().name());
    }
}
