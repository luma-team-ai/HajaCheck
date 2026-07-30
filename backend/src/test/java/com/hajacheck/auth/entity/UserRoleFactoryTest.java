package com.hajacheck.auth.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * User 팩토리별 역할(role) 부여 규칙 검증(#636).
 *
 * <p>기업 owner=회사 관리자이므로 {@link User#createCompanyOwner}는 ADMIN 을 부여한다.
 * 소셜 개인가입({@link User#createSocialUser})은 절대 ADMIN 이 되어선 안 되므로 USER 유지를 회귀로 고정한다.
 */
class UserRoleFactoryTest {

    @Test
    @DisplayName("기업 owner 가입 계정은 회사 관리자(ADMIN)로 생성된다")
    void createCompanyOwner_role_ADMIN() {
        User owner = User.createCompanyOwner("owner@haja.com", "김민수", "$2a$hashed");

        assertThat(owner.getRole()).isEqualTo(Role.ADMIN);
        assertThat(owner.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(owner.getCompanyId()).isNull(); // companyId 는 가입 트랜잭션에서 사후 배선
    }

    @Test
    @DisplayName("소셜 개인가입 계정은 USER 로 유지된다(권한 상향 회귀 방지)")
    void createSocialUser_role_USER_regression() {
        User social = User.createSocialUser(SocialProvider.KAKAO, "kakao-123",
                "user@kakao.com", "홍길동");

        assertThat(social.getRole()).isEqualTo(Role.USER);
        assertThat(social.getCompanyId()).isNull();
        assertThat(social.hasPassword()).isFalse();
    }
}
