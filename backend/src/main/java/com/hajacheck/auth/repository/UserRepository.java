package com.hajacheck.auth.repository;

import com.hajacheck.auth.entity.SocialProvider;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    Optional<User> findBySocialProviderAndSocialId(SocialProvider socialProvider, String socialId);

    // 마이페이지 좌석 현황(HAJA-177) — 회사 소속 "활성" 사용자만(비활성/정지 구성원은 좌석 과다집계·PII 노출 방지로 제외).
    List<User> findByCompanyIdAndStatus(Long companyId, UserStatus status);
}
