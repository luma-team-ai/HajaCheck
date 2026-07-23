package com.hajacheck.platformadmin.dto;

import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.membership.entity.PlanName;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * PlatformAdminUserRepository#search JPQL constructor expression 전용 프로젝션.
 * AdminUserProjection(#405, 회사 스코프)과 달리 companyId/companyName을 함께 싣는다 — 전사 조회라
 * 화면이 소속 기업을 컬럼으로 보여줘야 한다(#576, PR #626 후속 요구사항). 개인(회사 미소속) 사용자는
 * companyId/companyName이 null.
 */
public record PlatformAdminUserProjection(
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
}
