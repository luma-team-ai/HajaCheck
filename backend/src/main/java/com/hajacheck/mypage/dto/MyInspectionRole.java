package com.hajacheck.mypage.dto;

/**
 * 마이페이지 "내 점검 이력"에서 요청자가 이 점검에 대해 어떤 역할이었는지(#844).
 * assignedInspectorId == 요청자면 INSPECTOR, 아니면(=createdBy만 일치) OWNER — 둘 다 해당하면
 * INSPECTOR 우선(실제 점검 수행자가 더 구체적인 역할이라는 handoff 결정).
 */
public enum MyInspectionRole {
    INSPECTOR,
    OWNER
}
