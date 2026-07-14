package com.hajacheck.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.SocialProvider;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.support.PostgresTestSupport;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

// 실 PG named enum 검증을 위해 임베디드 교체를 끄고 Testcontainers PostgreSQL 을 그대로 사용.
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ActiveProfiles("test")
class UserRepositoryTest extends PostgresTestSupport {

    @Autowired
    private UserRepository userRepository;

    @Test
    void findByEmail_존재하는이메일_사용자반환() {
        userRepository.save(User.builder()
                .email("company@haja.com")
                .name("기업사용자")
                .role(Role.USER)
                .passwordHash("$2a$10$hashed")
                .status(UserStatus.ACTIVE)
                .build());

        Optional<User> found = userRepository.findByEmail("company@haja.com");

        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("기업사용자");
        assertThat(found.get().getCreatedAt()).isNotNull();
    }

    @Test
    void findByEmail_없는이메일_빈값() {
        Optional<User> found = userRepository.findByEmail("none@haja.com");

        assertThat(found).isEmpty();
    }

    @Test
    void findBySocialProviderAndSocialId_소셜사용자_반환() {
        userRepository.save(User.createSocialUser(
                SocialProvider.KAKAO, "kakao-123", "social@haja.com", "카카오사용자"));

        Optional<User> found = userRepository
                .findBySocialProviderAndSocialId(SocialProvider.KAKAO, "kakao-123");

        assertThat(found).isPresent();
        assertThat(found.get().getPasswordHash()).isNull();
        assertThat(found.get().getRole()).isEqualTo(Role.USER);
    }

    @Test
    void findBySocialProviderAndSocialId_다른제공자_빈값() {
        userRepository.save(User.createSocialUser(
                SocialProvider.KAKAO, "kakao-123", "social@haja.com", "카카오사용자"));

        Optional<User> found = userRepository
                .findBySocialProviderAndSocialId(SocialProvider.GOOGLE, "kakao-123");

        assertThat(found).isEmpty();
    }
}
