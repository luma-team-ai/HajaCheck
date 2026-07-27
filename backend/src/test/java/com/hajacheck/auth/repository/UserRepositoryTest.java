package com.hajacheck.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.SocialProvider;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.support.PostgresTestSupport;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

// 실 PG named enum 검증을 위해 임베디드 교체를 끄고 Testcontainers PostgreSQL 을 그대로 사용.
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ActiveProfiles("test")
class UserRepositoryTest extends PostgresTestSupport {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TestEntityManager em;

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

    // ── 대시보드 "최근 점검 전체보기"(신규) 담당자명 검색 ──

    // users.company_id 는 companies(id) FK, companies.owner_user_id 도 users(id) FK — 임의 Long
    // 리터럴을 그대로 못 쓰고 실제 User → Company 순서로 행이 있어야 한다.
    private Long createCompany(String regSuffix) {
        User owner = userRepository.save(User.builder()
                .email("owner-" + regSuffix + "@haja.com")
                .name("회사소유자")
                .role(Role.USER)
                .passwordHash("$2a$10$hashed")
                .status(UserStatus.ACTIVE)
                .build());
        Company company = Company.createPendingReview(
                owner.getId(), "테스트회사-" + regSuffix, "REG-" + regSuffix, "대표자",
                "서울시 강남구", null, "https://files.example.com/registration.png", "{}");
        em.persist(company);
        em.flush();
        return company.getId();
    }

    private User seedUser(String email, String name, Long companyId) {
        return userRepository.save(User.builder()
                .email(email)
                .name(name)
                .role(Role.INSPECTOR)
                .passwordHash("$2a$10$hashed")
                .status(UserStatus.ACTIVE)
                .companyId(companyId)
                .build());
    }

    @Test
    void findIdsByCompanyIdAndNameContaining_대소문자무시_부분일치() {
        Long companyId = createCompany("kim1");
        User kim = seedUser("kim@haja.com", "김검사", companyId);
        seedUser("lee@haja.com", "이감독", companyId);

        List<Long> result = userRepository.findIdsByCompanyIdAndNameContaining(companyId, "검사");

        assertThat(result).containsExactly(kim.getId());
    }

    @Test
    void findIdsByCompanyIdAndNameContaining_타사이름은매칭에서제외() {
        Long companyA = createCompany("kim2a");
        Long companyB = createCompany("kim2b");
        seedUser("kim@haja.com", "김검사", companyA);
        User strangerSameName = seedUser("kim-other@haja.com", "김검사", companyB);

        List<Long> result = userRepository.findIdsByCompanyIdAndNameContaining(companyB, "검사");

        assertThat(result).containsExactly(strangerSameName.getId());
    }

    @Test
    void findIdsByCompanyIdAndNameContaining_매칭없으면_빈목록() {
        Long companyId = createCompany("kim3");
        seedUser("kim@haja.com", "김검사", companyId);

        List<Long> result = userRepository.findIdsByCompanyIdAndNameContaining(companyId, "존재하지않음");

        assertThat(result).isEmpty();
    }
}
