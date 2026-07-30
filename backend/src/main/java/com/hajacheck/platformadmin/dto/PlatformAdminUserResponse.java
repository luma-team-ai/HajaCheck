package com.hajacheck.platformadmin.dto;

import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.membership.entity.PlanName;
import java.time.Instant;
import java.time.LocalDateTime;

public record PlatformAdminUserResponse(
        Long id,
        String name,
        String email,
        String avatarUrl,
        Role role,
        PlanName plan,
        Long companyId,
        String companyName,
        LocalDateTime joinedAt,
        Instant lastAccessAt,
        UserStatus status) {

    public static PlatformAdminUserResponse from(PlatformAdminUserProjection projection) {
        // 활성 구독이 없는(플랜 미구매) 사용자는 Free로 보여지도록 한다(AdminUserResponse와 동일 계약).
        PlanName plan = projection.plan() != null ? projection.plan() : PlanName.FREE;
        return new PlatformAdminUserResponse(
                projection.id(),
                projection.name(),
                projection.email(),
                projection.avatarUrl(),
                projection.role(),
                plan,
                projection.companyId(),
                projection.companyName(),
                projection.joinedAt(),
                projection.lastAccessAt(),
                projection.status());
    }

    /** 사용자 등록 직후 응답용. companyName은 호출부(서비스)가 조회한 값을 함께 넘긴다. */
    public static PlatformAdminUserResponse from(User user, String companyName) {
        return new PlatformAdminUserResponse(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getProfileImageUrl(),
                user.getRole(),
                PlanName.FREE,
                user.getCompanyId(),
                companyName,
                user.getCreatedAt(),
                user.getLastLoginAt(),
                user.getStatus());
    }
}
