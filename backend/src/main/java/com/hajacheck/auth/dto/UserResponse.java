package com.hajacheck.auth.dto;

import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import java.time.LocalDateTime;

/**
 * 사용자 응답 DTO — Entity 직접 노출 금지(§0). passwordHash/socialId 등 민감 필드 제외.
 * createdAt/companyName은 #740(가입일·소속 기업명 노출) 추가 — 기존 필드 순서·이름은 하위호환을 위해 유지.
 */
public record UserResponse(
        Long id,
        String email,
        String name,
        Role role,
        Long companyId,
        String profileImageUrl,
        LocalDateTime createdAt,
        String companyName
) {
    /**
     * companyName은 호출부(서비스)가 companyId로 조회한 값을 넘긴다(companyId=null인 개인 회원은 null).
     * PlatformAdminUserResponse.from(User, String)과 동일 패턴.
     */
    public static UserResponse from(User user, String companyName) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getName(),
                user.getRole(),
                user.getCompanyId(),
                user.getProfileImageUrl(),
                user.getCreatedAt(),
                companyName
        );
    }
}
