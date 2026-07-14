package com.hajacheck.auth.entity;

import com.hajacheck.global.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 서비스 사용자 계정 (자체가입 + 소셜) — DDL users 테이블 대응.
 * SpringBoot_코드_컨벤션.md §6/§7: @Setter 금지, 상태 변경은 의도가 드러나는 메서드로.
 *
 * ⚠️ role/status/socialProvider 는 DDL 상 PG enum 타입이지만 로컬 ddl-auto=update 에서는
 *    @Enumerated(STRING) 에 따라 varchar 로 생성된다. prod(validate) enum 정합성은 후속 인프라 과제.
 */
@Entity
@Getter
@Table(name = "users", uniqueConstraints = {
        // DDL 의 unique(social_provider, social_id) 반영 — 소셜 계정 중복/경합을 DB 제약으로 차단.
        @UniqueConstraint(name = "uk_users_social", columnNames = {"social_provider", "social_id"})
})
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseTimeEntity {

    // id: PG generated always as identity → IDENTITY 전략
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(name = "social_provider", length = 20)
    private SocialProvider socialProvider;

    @Column(name = "social_id", length = 255)
    private String socialId;

    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    // 이번 PR 범위: Company 엔티티 미생성 → 단순 FK 값 컬럼(Long, nullable)로만 보유.
    @Column(name = "company_id")
    private Long companyId;

    @Column(name = "profile_image_url", length = 500)
    private String profileImageUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Builder
    private User(String email, String name, Role role, SocialProvider socialProvider,
                 String socialId, String passwordHash, Long companyId,
                 String profileImageUrl, UserStatus status) {
        this.email = email;
        this.name = name;
        this.role = role == null ? Role.USER : role;
        this.socialProvider = socialProvider;
        this.socialId = socialId;
        this.passwordHash = passwordHash;
        this.companyId = companyId;
        this.profileImageUrl = profileImageUrl;
        this.status = status == null ? UserStatus.ACTIVE : status;
    }

    /**
     * 소셜 신규 가입 팩토리 — passwordHash 는 null(소셜 전용), role=USER, status=ACTIVE.
     * 참고: 소셜 자동가입이 ACTIVE 인 것은 companyId=null 개인회원의 의도된 셀프가입이다.
     * 보호 리소스의 companyId/role 권한 경계는 각 엔드포인트 후속 과제로 다룬다(이 PR 범위 밖).
     */
    public static User createSocialUser(SocialProvider provider, String socialId,
                                        String email, String name) {
        return User.builder()
                .email(email)
                .name(name)
                .role(Role.USER)
                .socialProvider(provider)
                .socialId(socialId)
                .status(UserStatus.ACTIVE)
                .build();
    }

    /**
     * 로그인 성공 시각 갱신 (상태 전이 메서드).
     */
    public void updateLastLogin(Instant loginAt) {
        this.lastLoginAt = loginAt;
    }

    public boolean isSuspended() {
        return this.status == UserStatus.SUSPENDED;
    }
}
