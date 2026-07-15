package com.hajacheck.auth.repository;

import com.hajacheck.auth.entity.SocialProvider;
import com.hajacheck.auth.entity.User;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    Optional<User> findBySocialProviderAndSocialId(SocialProvider socialProvider, String socialId);

    // 마이페이지 좌석 현황(HAJA-177) — 회사 소속 사용자 목록/수.
    List<User> findByCompanyId(Long companyId);

    long countByCompanyId(Long companyId);
}
