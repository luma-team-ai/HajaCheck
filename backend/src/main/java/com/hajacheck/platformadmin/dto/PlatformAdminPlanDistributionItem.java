package com.hajacheck.platformadmin.dto;

import com.hajacheck.membership.entity.PlanName;

/** 서비스 통계(#633) 플랜 분포 — frontend PlanDistributionItem 1:1. */
public record PlatformAdminPlanDistributionItem(PlanName plan, int percent) {
}
