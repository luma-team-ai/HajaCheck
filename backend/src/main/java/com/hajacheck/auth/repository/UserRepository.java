package com.hajacheck.auth.repository;

import com.hajacheck.auth.entity.SocialProvider;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    Optional<User> findBySocialProviderAndSocialId(SocialProvider socialProvider, String socialId);

    /**
     * 사용자 행 잠금(#1492) — 같은 사용자를 대상으로 한 동시 상태 전이를 트랜잭션 종료까지 직렬화한다
     * ({@code CompanyRepository#findByIdForUpdate}·{@code FacilityRepository#findByIdForUpdate} 와 동일 패턴).
     *
     * <p><b>왜 필요한가</b>: 초대 코드 redeem 은 좌석을 {@code usage_counters} 행 잠금으로 지키는데 그
     * 잠금은 <b>회사 단위</b>다. 한 사용자가 <b>서로 다른 두 회사</b>의 코드를 동시에 redeem 하면 잠금
     * 대상 행이 서로 달라 두 트랜잭션이 나란히 진행하고, 승패가 서비스 규칙이 아니라 부분 UQ
     * {@code uq_company_memberships_approved_user} 위반(=DB 제약)으로 갈린다. 이 잠금이 사용자 단위
     * 직렬화를 만들어, 뒤늦은 요청이 <b>승자가 커밋한 최신 상태</b>(ACTIVE)를 읽고
     * {@code User#requireWaiting} 도메인 가드에서 순차 실행과 동일하게 거부되도록 한다.
     *
     * <p><b>{@code @Version} 이 아니라 비관적 락인 이유</b>: 낙관적 락은 스키마 변경
     * ({@code users.lock_version} 컬럼 + Flyway)이 필요하고, redeem 경합의 올바른 처리는 재시도가 아니라
     * <b>직렬화 후 도메인 거부</b>다(같은 사용자가 두 회사에 동시에 합류할 수는 없다).
     *
     * <p><b>⚠️ 잠금 순서</b>: 이 잠금을 잡는 경로는 <b>users → usage_counters</b> 순서를 지켜야 한다.
     * {@code usage_counters} 를 먼저 잠그는 경로({@code AdminUserService#createUser}·
     * {@code PlatformAdminUserService#createUser} 의 {@code QuotaService#reserveSeat})는 그 뒤에
     * <b>신규</b> users 행을 INSERT 할 뿐 기존 행을 잠그지 않으므로 역순 대기가 생기지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);

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
