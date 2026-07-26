package com.hajacheck.membership.service;

import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.Role;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.core.facility.repository.FacilityRepository;
import com.hajacheck.global.exception.BusinessException;
import com.hajacheck.global.exception.ErrorCode;
import com.hajacheck.membership.dto.DowngradeOverflow;
import com.hajacheck.membership.entity.Plan;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 플랜 하향 시 한도 초과분 처리(#890 Phase 1) — 확정 정책은 GitHub 이슈 #890 본문이 정본이다.
 *
 * <p><b>원칙</b>: 데이터를 지우지 않되 한도를 넘는 자원을 정지/읽기전용으로 전환한다. <b>목록에서 숨기지
 * 않는다</b> — 숨겨도 그 계정은 여전히 로그인·수정이 가능한데({@code CompanyScopeGuard} 는 화면 표시
 * 여부를 모른다) 관리자는 존재조차 모르게 되어 한도 초과보다 위험하다.
 *
 * <p><b>자원별 처리</b>
 * <ul>
 *   <li><b>좌석</b> — 초과분을 {@link UserStatus#SUSPENDED} 로 전환한다. 목록에는 "정지됨"으로 계속 보인다.</li>
 *   <li><b>시설물</b> — 상태 컬럼을 추가하지 않고 <b>계산 판정</b>한다
 *       ({@code QuotaService#isFacilityReadOnly} — 현재 플랜 해석이 필요해 그쪽에 둔다).
 *       한도가 다시 올라가면 자동 복구된다는 게 컬럼 방식 대비 이점이다. 그래서 하향 시점에
 *       시설물에 대해 할 일이 없다.</li>
 *   <li><b>월 분석</b> — <b>아무것도 하지 않는다.</b> 이미 소비한 누적 기록이라 되돌리면 기록 왜곡이다.
 *       당월 초과는 그대로 두고 신규 분석만 차단되는 현행 동작(=QuotaService)이 곧 정책이며,
 *       다음 달 period 행이 새로 열리면서 자연히 새 한도가 적용된다. <b>누락이 아니다.</b></li>
 * </ul>
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class PlanDowngradeService {

    private final UserRepository userRepository;
    private final CompanyRepository companyRepository;
    private final FacilityRepository facilityRepository;

    /**
     * 대상 요금제로 내렸을 때 넘치게 되는 자원을 계산한다(부작용 없음).
     *
     * <p>실제 전환({@link #applyOverflow})과 <b>같은 메서드로 대상을 산출</b>하므로 미리보기와 결과가
     * 어긋나지 않는다.
     */
    public DowngradeOverflow preview(Long companyId, Plan currentPlan, Plan targetPlan) {
        List<Long> seats = isNarrowing(currentPlan.getMaxSeats(), targetPlan.getMaxSeats())
                ? resolveSeatsToSuspend(companyId, targetPlan)
                : List.of();
        int facilities = isNarrowing(currentPlan.getMaxFacilities(), targetPlan.getMaxFacilities())
                ? resolveFacilityOverflowCount(companyId, targetPlan)
                : 0;
        return new DowngradeOverflow(seats, facilities);
    }

    /**
     * 이 자원의 한도가 실제로 <b>좁아지는가</b> — 자원별로 따로 판정한다(리뷰 P1).
     *
     * <p>⚠️ 이 게이트가 없으면 <b>업그레이드까지 초과로 잡힌다.</b> 하향해도 자원을 지우지 않는 게 이
     * 기능의 정책이라, 한 번 내려간 회사는 영구히 "보유량 &gt; 한도" 상태로 남는다. 그 상태에서 요금제를
     * <b>올릴 때</b>도 "목표 한도 vs 현재 보유량"만 비교하면 초과로 판정돼 결제가 막히고, 확인 플래그를
     * 실으면 <b>업그레이드인데 구성원이 정지된다</b>.
     *
     * @param currentLimit 현재 한도(null = 무제한)
     * @param targetLimit  대상 한도(null = 무제한)
     */
    private boolean isNarrowing(Integer currentLimit, Integer targetLimit) {
        if (targetLimit == null) {
            // 목표가 무제한 → 어떤 경우에도 하향이 아니다.
            return false;
        }
        // 현재가 무제한인데 목표가 유한 → 좁아진다.
        return currentLimit == null || targetLimit < currentLimit;
    }

    /**
     * 초과 좌석을 실제로 정지시킨다 — <b>플랜 전환과 같은 트랜잭션</b>에서 호출해야 한다
     * ({@code MANDATORY}). 플랜만 바뀌고 정지가 안 된 상태가 남으면 한도가 조용히 무력화된다.
     *
     * <p>시설물은 계산 판정이라 여기서 할 일이 없고, 월 분석은 정책상 손대지 않는다(클래스 javadoc).
     *
     * @return 실제로 정지된 대상 요약(호출부 응답·로깅용)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public DowngradeOverflow applyOverflow(Long companyId, Plan currentPlan, Plan targetPlan) {
        DowngradeOverflow overflow = preview(companyId, currentPlan, targetPlan);
        if (overflow.seatUserIdsToSuspend().isEmpty()) {
            return overflow;
        }
        userRepository.findAllById(overflow.seatUserIdsToSuspend())
                .forEach(user -> user.changeStatus(UserStatus.SUSPENDED));
        log.info("플랜 하향 좌석 정지 — companyId={} targetPlan={} suspended={} facilityReadOnly={}",
                companyId, targetPlan.getName(), overflow.seatOverflowCount(), overflow.facilityOverflowCount());
        return overflow;
    }

    /**
     * 정지 대상 산출 — ACTIVE 구성원을 id 오름차순으로 두고 한도만큼 앞에서 남긴 뒤 나머지를 반환한다.
     *
     * <p><b>owner 는 무조건 유지</b>한다(정지되면 회사가 관리 불능). 목록 순서와 무관하게 가장 먼저
     * 좌석을 차지한다.
     *
     * <p>⚠️ <b>ADMIN 역할도 정지 대상에 포함한다</b> — {@code AdminUserService#requireNotLastOrSelfAdmin}
     * 은 "ADMIN 은 정지 불가"가 아니라 <b>①자기 자신 정지 ②마지막 ACTIVE ADMIN 정지</b> 두 가지만 막는다.
     * 즉 지키려는 건 ADMIN 신분이 아니라 <b>회사가 관리 콘솔 접근 수단을 잃지 않는 것</b>이고, 그 불변식은
     * {@link #requireSurvivingActiveAdmin} 이 여기서 직접 강제한다. ADMIN 을 통째로 면제하면 ADMIN 을
     * 늘리는 것만으로 좌석 한도를 무제한 우회할 수 있어(#850 과 같은 구멍) 강제가 무의미해진다.
     */
    private List<Long> resolveSeatsToSuspend(Long companyId, Plan targetPlan) {
        Integer maxSeats = targetPlan.getMaxSeats();
        if (companyId == null || maxSeats == null) {
            // null = 무제한(Plan javadoc) — 정지 대상 없음.
            return List.of();
        }
        List<User> active = userRepository.findByCompanyIdAndStatusOrderByIdAsc(
                companyId, UserStatus.ACTIVE, Pageable.unpaged());
        if (active.size() <= maxSeats) {
            return List.of();
        }
        // 회사 행이 없으면 owner 를 특정할 수 없다 — 조용히 owner 보호를 해제하는 대신 거절한다(리뷰 P3).
        Long ownerUserId = companyRepository.findById(companyId)
                .map(Company::getOwnerUserId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLAN_FORBIDDEN));

        Set<Long> keep = new LinkedHashSet<>();
        if (active.stream().anyMatch(u -> ownerUserId.equals(u.getId()))) {
            keep.add(ownerUserId);
        }
        for (User user : active) {
            if (keep.size() >= maxSeats) {
                break;
            }
            keep.add(user.getId());
        }
        List<Long> suspend = active.stream()
                .map(User::getId)
                .filter(id -> !keep.contains(id))
                .toList();
        requireSurvivingActiveAdmin(active, suspend);
        return suspend;
    }

    /**
     * 정지 후에도 <b>ACTIVE ADMIN 이 최소 1명 남는지</b> 단정한다(리뷰 P2) —
     * {@code AdminUserService#requireNotLastOrSelfAdmin} 과 같은 취지다. 그 가드가 지키려는 건
     * "ADMIN 신분"이 아니라 <b>회사가 관리 콘솔 접근 수단을 잃지 않는 것</b>이므로, 하향 경로에서도
     * 같은 불변식을 지켜야 한다.
     *
     * <p>현재 유일한 호출부({@code AdminPlanService#changePlan})는 요청자가 owner=ACTIVE ADMIN 임을
     * 이미 강제하지만, 그 성립은 <b>이 클래스 밖의 인가에 의존</b>한다. {@code applyOverflow} 는 public
     * 이고 Phase 2/3·정책값 변경·배치 등 owner 인증을 거치지 않는 호출부가 하나만 붙어도 회사가 영구
     * 잠긴다(owner 까지 SUSPENDED 면 {@code SessionUserRevalidationFilter} 가 세션을 즉시 죽여 로그인도
     * 불가). 그래서 외부 인가에 기대지 않고 여기서 직접 막는다.
     *
     * <p>{@code maxSeats == 0}(플랫폼 관리자가 설정 가능)처럼 전원이 정지 대상이 되는 입력도 이 단정에
     * 걸린다.
     */
    private void requireSurvivingActiveAdmin(List<User> active, List<Long> suspend) {
        Set<Long> suspended = Set.copyOf(suspend);
        boolean keepsActiveAdmin = active.stream()
                .filter(user -> !suspended.contains(user.getId()))
                .anyMatch(user -> user.getRole() == Role.ADMIN);
        if (!keepsActiveAdmin) {
            throw new BusinessException(ErrorCode.ADMIN_PROTECTED_ACCOUNT);
        }
    }

    /**
     * 읽기 전용이 될 시설물 <b>총량</b>(증분이 아니다, 재검토 P3) — "대상 요금제 기준으로 한도를 넘는
     * 개수"다. 이미 이전 하향으로 읽기전용이던 것도 포함되므로, 화면에 "이번에 새로 바뀌는 개수"로
     * 표시하면 오인을 준다.
     */
    private int resolveFacilityOverflowCount(Long companyId, Plan targetPlan) {
        Integer maxFacilities = targetPlan.getMaxFacilities();
        if (companyId == null || maxFacilities == null) {
            return 0;
        }
        long owned = facilityRepository.countByCompanyId(companyId);
        return (int) Math.max(0, owned - maxFacilities);
    }
}
