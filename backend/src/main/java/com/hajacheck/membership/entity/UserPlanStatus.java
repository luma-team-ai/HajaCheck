package com.hajacheck.membership.entity;

/**
 * 구독 상태(이용중/만료/업그레이드 요청) — DDL user_plan_status_type (v0.3).
 */
public enum UserPlanStatus {
    ACTIVE,
    EXPIRED,
    UPGRADE_REQUESTED
}
