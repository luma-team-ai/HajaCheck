package com.hajacheck.auth.entity;

import com.hajacheck.global.common.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 서비스 사용자 계정 (자체가입 + 소셜) — DDL users 테이블 대응.
 * SpringBoot_코드_컨벤션.md §6/§7: @Setter 금지, 상태 변경은 의도가 드러나는 메서드로.
 *
 * role/status/socialProvider 는 DDL 상 PG named enum 타입(role_type/social_provider_type/user_status_type)이며,
 * @JdbcTypeCode(NAMED_ENUM) + columnDefinition 으로 실 PG enum 타입에 매핑한다(서버 ddl-auto=validate 통과).
 * Java enum 라벨은 v0.3 DDL 의 enum 라벨과 정확히 일치한다.
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

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "role_type", nullable = false)
    private Role role;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "social_provider", columnDefinition = "social_provider_type")
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

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "user_status_type", nullable = false)
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
     * 기업 회원가입(자체가입) 소유자 계정 팩토리 — email/password 로그인, role=USER, status=ACTIVE.
     * 참고: user.name 에는 대표자명을 담는다(users.name len100 = 표시명). companyId 는 가입 트랜잭션에서
     * Company 저장 후 {@link #assignToCompany(Long)} 로 배선한다.
     * status=ACTIVE 인 이유: user_status_type 에 PENDING 라벨이 없다. 승인 게이팅(company.status=PENDING_REVIEW)은
     * 각 보호 리소스 엔드포인트의 후속 과제이며, 이 계정은 로그인은 되되 미승인 상태로 남는다.
     */
    public static User createCompanyOwner(String email, String name, String passwordHash) {
        return User.builder()
                .email(email)
                .name(name)
                .role(Role.USER)
                .passwordHash(passwordHash)
                .status(UserStatus.ACTIVE)
                .build();
    }

    /**
     * 로그인 성공 시각 갱신 (상태 전이 메서드).
     */
    public void updateLastLogin(Instant loginAt) {
        this.lastLoginAt = loginAt;
    }

    /**
     * 기업 계정 소속 배선 (상태 전이 — @Setter 금지 회피). 가입 트랜잭션에서 Company 저장 직후 호출.
     */
    public void assignToCompany(Long companyId) {
        this.companyId = companyId;
    }

    /**
     * 비밀번호 로그인이 가능한 계정인지 — 소셜 전용 계정(passwordHash=null)은 false.
     *
     * <p>CustomUserDetailsService 가 이 조건으로 소셜 전용 계정의 <b>비밀번호 로그인을 금지</b>한다.
     * 비밀번호 재설정도 같은 규칙을 따라야 한다: 재설정이 소셜 전용 계정에 비밀번호를 심으면 다른 계층이
     * 명시적으로 금지한 로그인 수단을 이 경로가 말없이 열어주는 셈이 된다(계층 간 규칙 불일치).
     */
    public boolean hasPassword() {
        return this.passwordHash != null;
    }

    /**
     * 비밀번호 재설정 (상태 전이). 호출부에서 반드시 인코딩된 해시를 전달한다.
     * 사용처: PasswordResetService(이메일 링크 방식 2단계 — #194 / HAJA-172).
     */
    public void changePassword(String newPasswordHash) {
        this.passwordHash = newPasswordHash;
    }

    public boolean isSuspended() {
        return this.status == UserStatus.SUSPENDED;
    }
}
