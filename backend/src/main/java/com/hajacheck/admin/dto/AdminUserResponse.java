package com.hajacheck.admin.dto;

import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.membership.entity.PlanName;
import java.time.Instant;
import java.time.LocalDateTime;

public record AdminUserResponse(
        Long id,
        String name,
        String email,
        String avatarUrl,
        Role role,
        PlanName plan,
        LocalDateTime joinedAt,
        Instant lastAccessAt,
        UserStatus status) {

    public static AdminUserResponse from(AdminUserProjection projection) {
        // 활성 구독이 없는(플랜 미구매) 사용자는 Free로 보여지도록 한다(#405 Figma 리뷰 코멘트).
        PlanName plan = projection.plan() != null ? projection.plan() : PlanName.FREE;
        return new AdminUserResponse(
                projection.id(),
                projection.name(),
                projection.email(),
                projection.avatarUrl(),
                projection.role(),
                plan,
                projection.joinedAt(),
                projection.lastAccessAt(),
                projection.status());
    }

    /** 관리자 콘솔 — 사용자 등록 직후 응답용. 방금 만든 계정이라 활성 구독이 있을 수 없어 plan=FREE 고정. */
    public static AdminUserResponse from(User user) {
        return new AdminUserResponse(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getProfileImageUrl(),
                user.getRole(),
                PlanName.FREE,
                user.getCreatedAt(),
                user.getLastLoginAt(),
                user.getStatus());
    }
}
