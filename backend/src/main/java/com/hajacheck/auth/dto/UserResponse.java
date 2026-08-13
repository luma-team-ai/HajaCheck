package com.hajacheck.auth.dto;

import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import java.time.LocalDateTime;

/**
 * 사용자 응답 DTO — Entity 직접 노출 금지(§0). passwordHash/socialId 등 민감 필드 제외.
 * createdAt/companyName은 #740(가입일·소속 기업명 노출) 추가 — 기존 필드 순서·이름은 하위호환을 위해 유지.
 * status는 #794(초대 코드) 추가 — 프론트가 WAITING(초대 대기)을 판별해 초대 코드 입력 화면으로 분기한다.
 * isDemo는 #1626(데모 계정) 추가 — 서버가 설정(app.demo.login-id) 매칭으로 계산해 내려주며, 프론트는
 * 이 값으로 데모 배너·안내 UI를 분기한다(추가 필드라 기존 클라이언트와 호환).
 */
public record UserResponse(
        Long id,
        String email,
        String name,
        Role role,
        Long companyId,
        String profileImageUrl,
        LocalDateTime createdAt,
        String companyName,
        UserStatus status,
        boolean isDemo
) {
    /**
     * companyName은 호출부(서비스)가 companyId로 조회한 값을 넘긴다(companyId=null인 개인 회원은 null).
     * PlatformAdminUserResponse.from(User, String)과 동일 패턴. isDemo도 호출부(AuthService)가
     * 설정(DemoProperties) 매칭으로 계산해 넘긴다 — DTO는 판정 로직을 갖지 않는다.
     */
    public static UserResponse from(User user, String companyName, boolean isDemo) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getName(),
                user.getRole(),
                user.getCompanyId(),
                user.getProfileImageUrl(),
                user.getCreatedAt(),
                companyName,
                user.getStatus(),
                isDemo
        );
    }
}
