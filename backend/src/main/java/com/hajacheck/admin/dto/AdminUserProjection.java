package com.hajacheck.admin.dto;

import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.membership.entity.PlanName;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * AdminUserRepository#search JPQL constructor expression 전용 프로젝션.
 * plan 은 활성 구독(user_plans.status=ACTIVE)이 없으면 null.
 */
public record AdminUserProjection(
        Long id,
        String name,
        String email,
        String avatarUrl,
        Role role,
        PlanName plan,
        LocalDateTime joinedAt,
        Instant lastAccessAt,
        UserStatus status) {
}
