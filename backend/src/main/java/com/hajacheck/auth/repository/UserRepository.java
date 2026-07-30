package com.hajacheck.auth.repository;

import com.hajacheck.auth.entity.SocialProvider;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    Optional<User> findBySocialProviderAndSocialId(SocialProvider socialProvider, String socialId);

    // 마이페이지 좌석 현황(HAJA-177) — 회사 소속 "활성" 사용자만(비활성/정지 구성원은 좌석 과다집계·PII 노출 방지로 제외).
    List<User> findByCompanyIdAndStatus(Long companyId, UserStatus status);

    // 좌석 목록 조회 상한(#484) — maxSeats 에 실질 상한이 있으나, 화면 표시용 목록 자체가 무제한
    // 반환되지 않도록 방어적으로 상한을 둔다. "used"(총원 수) 는 아래 count 쿼리로 별도 산출한다.
    List<User> findByCompanyIdAndStatusOrderByIdAsc(Long companyId, UserStatus status, Pageable pageable);

    long countByCompanyIdAndStatus(Long companyId, UserStatus status);

    // 대시보드 "최근 점검 전체보기" 검색(신규) — 검색어가 담당자명에 매칭되는 회사 소속 사용자 id만
    // 골라 InspectionRepositoryImpl 의 createdBy IN(:ids) 예측에 넘긴다. 회사 스코프를 여기서부터
    // 강제해(companyId = :companyId) 타사 사용자 이름이 매칭되지 않도록 한다(cross-company IDOR 방지).
    // namePattern은 호출부(DashboardService.escapeLikeWildcards)에서 LIKE 와일드카드(%, _)를 이미
    // 이스케이프해서 넘긴다 — AdminUserRepository.search와 동일하게 escape '\\'로 짝을 맞춘다.
    @Query("select u.id from User u where u.companyId = :companyId "
            + "and lower(u.name) like lower(concat('%', :namePattern, '%')) escape '\\'")
    List<Long> findIdsByCompanyIdAndNameContaining(
            @Param("companyId") Long companyId, @Param("namePattern") String namePattern);
}
