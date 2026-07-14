package com.hajacheck.auth.dto;

import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;

/**
 * 사용자 응답 DTO — Entity 직접 노출 금지(§0). passwordHash/socialId 등 민감 필드 제외.
 */
public record UserResponse(
        Long id,
        String email,
        String name,
        Role role,
        Long companyId,
        String profileImageUrl
) {
    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getName(),
                user.getRole(),
                user.getCompanyId(),
                user.getProfileImageUrl()
        );
    }
}
