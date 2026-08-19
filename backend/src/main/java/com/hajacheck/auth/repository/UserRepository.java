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
     * <p>참고: {@code PlatformAdminUserRepository#findByIdForUpdate} 가 같은 users 행 잠금을 이미 갖고
     * 있으나 그쪽은 플랫폼 관리자 컨텍스트 전용({@code changeSkill}·{@code changeRole}·
     * {@code changeStatus} — 좌석/사용량 자원은 건드리지 않는다)이라 auth 컨텍스트에서 재사용하지 않는다.
     *
     * <p><b>왜 필요한가</b>: 초대 코드 redeem 은 좌석을 {@code usage_counters} 행 잠금으로 지키는데 그
     * 잠금은 <b>회사 단위</b>라 "같은 사용자" 축의 경합을 갈라놓지 못한다. 실측된 두 축:
     * <ul>
     *   <li><b>서로 다른 두 회사</b> 코드 동시 redeem — 부분 UQ
     *       {@code uq_company_memberships_approved_user} 가 정합은 지켜주지만, 승패가 서비스 규칙이
     *       아니라 DB 제약 위반(raw {@code DataIntegrityViolationException})으로 갈린다.</li>
     *   <li><b>같은 회사</b>(같은 코드 2회 제출) 동시 redeem — <b>DB 가 지켜주지 못한다</b>. 패자가
     *       {@code usage_counters} 잠금 해제 후 재실측하는 사이 멤버십 행은 이미 존재해
     *       {@code InviteCodeService#grantEffectiveMembership} 이 INSERT 가 아니라 재승인(UPDATE)으로
     *       빠지므로 어떤 UQ 에도 걸리지 않고 둘 다 커밋된다 → {@code seat_count} 만 1 부풀어 영구
     *       이중 계상(락 제거 실측: 성공 2 / APPROVED 1 / seat_count 3 / 실제 ACTIVE 2).</li>
     * </ul>
     * 이 잠금이 두 축을 모두 사용자 단위로 직렬화해, 뒤늦은 요청이 <b>승자가 커밋한 최신 상태</b>(ACTIVE)를
     * 읽고 {@code User#requireWaiting} 도메인 가드에서 순차 실행과 동일하게 거부되도록 한다.
     *
     * <p><b>{@code @Version} 이 아니라 비관적 락인 이유</b>: 낙관적 락은 스키마 변경
     * ({@code users.lock_version} 컬럼 + Flyway)이 필요하고, redeem 경합의 올바른 처리는 재시도가 아니라
     * <b>직렬화 후 도메인 거부</b>다(같은 사용자가 두 회사에 동시에 합류할 수는 없다).
     *
     * <h2>⚠️ 잠금 순서 — 교착이 없는 진짜 근거</h2>
     * 이 잠금을 잡는 경로는 <b>users → usage_counters</b> 순이다. <b>"역순 경로가 없다"는 서술은
     * 사실이 아니다</b> — 실재한다:
     * <ul>
     *   <li>{@code DemoResetService}(단일 {@code @Transactional})가 {@code usage_counters} 삭제 →
     *       {@code users} 삭제 순으로 진행한다.</li>
     *   <li>{@code QuotaService#reserveFacilitySlot}({@code usage_counters} 잠금) 뒤의
     *       {@code facilities} INSERT 는 FK 때문에 참조 대상 users 행에 {@code FOR KEY SHARE} 를 잡고,
     *       이는 {@code FOR UPDATE} 와 충돌한다.</li>
     * </ul>
     *
     * <p>그럼에도 교착이 성립하지 않는 근거는 다음 <b>불변식</b>이다: 이 잠금은 {@code redeem} 트랜잭션의
     * <b>첫 락</b>이고(앞에는 Redis rate-limit 뿐), 락을 <b>계속 쥔 채 다른 자원을 기다리는</b> 경우는
     * {@code User#requireWaiting()} 을 통과한 <b>WAITING</b> 사용자뿐이다(그 외에는 즉시 예외 → 롤백 →
     * {@code usage_counters} 를 잡기도 전에 락 해제). 그리고 WAITING 은 정의상
     * <b>{@code company_id IS NULL}</b> 이라({@code User#createSocialUser} 가 유일한 생성 경로), 위의 역순
     * 경로들(전부 회사 스코프 — {@code where company_id = ?} 또는 회사 자원 FK)이 그 행에 닿지 못한다.
     * 대기 그래프에 간선이 생기지 않으므로 순환도 없다.
     *
     * <p><b>이 근거를 무효화하는 변경(바꾸려면 순서를 다시 정렬할 것)</b>:
     * <ol>
     *   <li>이 잠금과 {@code requireWaiting()} <b>사이</b>에 회사 스코프 자원 잠금
     *       ({@code usage_counters} 등)을 끼워 넣는 것 — WAITING 이 아닌 사용자에 대해서도 users 락을
     *       쥔 채 회사 자원을 기다리게 된다.</li>
     *   <li>회사 스코프 <b>쓰기</b> 경로가 기존 users 행을 잠그게 되는 것. (현재 users 행을
     *       <b>명시적으로</b>({@code PESSIMISTIC_WRITE}) 잠그는 다른 경로는 플랫폼 관리자 콘솔
     *       {@code PlatformAdminUserService} 의 {@code changeSkill}·{@code changeRole}·
     *       {@code changeStatus} 셋뿐이고 <b>전부 좌석/사용량 자원과 무관</b>하다 — FK
     *       {@code FOR KEY SHARE} 로 <b>암묵적으로</b> 잠그는 경로는 위 "역순 경로" 문단 참조.
     *       {@code changeRole}/{@code changeStatus} 는 users 를 먼저 잠그고 그 뒤 {@code role == ADMIN}
     *       인 강등/정지에 한해 {@code requireNotLastCompanyAdmin} 으로 companies 를 잠근다
     *       (= <b>users → companies</b>, 근거는 {@code PlatformAdminUserService#findUserForUpdate}
     *       javadoc). 이 순서는 여기서 잡는 잠금(users 가 첫 락)과 방향이 같아 순환을 만들지 않고,
     *       그 안에서도 {@code companyId == null} 이면 즉시 반환하므로 WAITING 사용자에게는 companies
     *       쪽으로 걸릴 일 자체가 없다. 또한 {@code requireAssignableStatus} 가 WAITING 부여 자체를
     *       막는다.)</li>
     *   <li>WAITING 사용자에게 {@code company_id} 가 남아 있게 되는 것. (과거엔 여기에 구멍이 있었다 —
     *       {@code PlatformAdminUserService#changeStatus} 에 {@code AdminUserService.ASSIGNABLE_STATUSES}
     *       같은 가드가 없어 회사 소속 사용자를 WAITING 으로 되돌릴 수 있었다. #1492 리뷰에서 같은
     *       화이트리스트를 적용해 막았고, {@code PlatformAdminUserServiceStatusWhitelistTest} 가 이를
     *       고정한다. WAITING 을 만드는 경로를 새로 열 때는 이 불변식을 먼저 확인할 것.)</li>
     *   <li><b>이 잠금보다 <i>앞에</i> DB 행 잠금·쓰기를 추가하는 것</b>(감사 로그 INSERT·회사 행 선조회
     *       잠금·멱등키 UPSERT 등). 위 근거의 나머지 절반은 "이 잠금이 트랜잭션의 <b>첫 락</b>"(앞에는
     *       Redis rate-limit 뿐)이라는 전제다. 앞에 다른 락이 생기면 <b>WAITING 여부와 무관하게</b>
     *       redeem 이 "자원 X 보유 → users 대기" 형태가 되어, 반대로 "users 보유 → X 대기" 하는 경로와
     *       곧바로 순환을 만든다. 조건 1~3 은 전제 "락 보유 지속은 WAITING 뿐"만 지키므로 이 조건이
     *       별도로 필요하다 — 실무에서 가장 흔한 회귀 형태다.</li>
     * </ol>
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
