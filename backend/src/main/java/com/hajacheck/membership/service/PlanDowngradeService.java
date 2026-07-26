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
import java.util.stream.Collectors;
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
     * 대상 요금제로 내렸을 때 넘치게 되는 자원을 계산한다(부작용 없음). 유지 대상 미지정(기존 동작 —
     * id 오름차순 자동 선정)과 동일하다.
     *
     * <p>실제 전환({@link #applyOverflow})과 <b>같은 메서드로 대상을 산출</b>하므로 미리보기와 결과가
     * 어긋나지 않는다.
     */
    public DowngradeOverflow preview(Long companyId, Plan currentPlan, Plan targetPlan) {
        return preview(companyId, currentPlan, targetPlan, List.of());
    }

    /**
     * 대상 요금제로 내렸을 때 넘치게 되는 자원을 계산한다(부작용 없음) — 관리자가 유지할 구성원을 직접
     * 고를 수 있다(#890 Phase 2).
     *
     * <p>실제 전환({@link #applyOverflow})과 <b>같은 메서드로 대상을 산출</b>하므로 미리보기와 결과가
     * 어긋나지 않는다 — 검증도 여기 한 곳({@link #resolveSeatsToSuspend})에서만 이뤄진다.
     *
     * @param keepUserIds 관리자가 유지를 선택한 구성원 id. <b>비어 있으면(null 포함) 기존 동작</b>(owner +
     *                    id 오름차순 자동 선정)을 그대로 따른다 — 하위 호환. 비어 있지 않으면 아래를 검증한다.
     *                    <ul>
     *                      <li>전부 <b>요청 회사의 ACTIVE 구성원</b>이어야 한다 — 아니면(타 회사 id 등)
     *                          존재 여부를 흘리지 않고 {@link ErrorCode#PLAN_FORBIDDEN}으로 일괄 거절한다
     *                          (기존 owner 조회 실패와 같은 관례).</li>
     *                      <li>owner 는 선택 여부와 무관하게 <b>항상</b> 유지 집합에 포함된다.</li>
     *                      <li>owner 를 합친 유지 인원(union, 중복 제거)이 좌석 한도를 넘으면
     *                          {@link ErrorCode#PLAN_SEAT_QUOTA_EXCEEDED}로 거절한다.</li>
     *                    </ul>
     */
    public DowngradeOverflow preview(Long companyId, Plan currentPlan, Plan targetPlan, List<Long> keepUserIds) {
        List<Long> seats = isNarrowing(currentPlan.getMaxSeats(), targetPlan.getMaxSeats())
                ? resolveSeatsToSuspend(companyId, targetPlan, keepUserIds)
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
        return applyOverflow(companyId, currentPlan, targetPlan, List.of());
    }

    /**
     * 초과 좌석을 실제로 정지시킨다 — 유지 대상을 관리자가 직접 선택할 수 있다(#890 Phase 2).
     * 검증·산출은 {@link #preview(Long, Plan, Plan, List)} 와 완전히 동일한 경로를 탄다(정합 보장).
     *
     * @param keepUserIds {@link #preview(Long, Plan, Plan, List)} 와 동일한 규칙 — 비어 있으면 기존 동작.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public DowngradeOverflow applyOverflow(Long companyId, Plan currentPlan, Plan targetPlan, List<Long> keepUserIds) {
        DowngradeOverflow overflow = preview(companyId, currentPlan, targetPlan, keepUserIds);
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
     * 정지 대상 산출 — 관리자가 유지 대상을 직접 선택하지 않으면(#890 Phase 1 기존 동작) ACTIVE 구성원을
     * id 오름차순으로 두고 한도만큼 앞에서 남긴 뒤 나머지를 반환한다. 직접 선택하면(#890 Phase 2) 그
     * 선택 그대로 유지하고 나머지 전부를 정지 대상으로 돌린다 — "한도까지 자동으로 채워주는" 게 아니라
     * 관리자의 선택을 있는 그대로 존중한다("keepUserIds" javadoc 참고).
     *
     * <p><b>owner 는 선택 여부와 무관하게 무조건 유지</b>한다(정지되면 회사가 관리 불능). 목록 순서·선택
     * 포함 여부와 무관하게 가장 먼저 좌석을 차지한다.
     *
     * <p>⚠️ <b>ADMIN 역할도 정지 대상에 포함한다</b> — {@code AdminUserService#requireNotLastOrSelfAdmin}
     * 은 "ADMIN 은 정지 불가"가 아니라 <b>①자기 자신 정지 ②마지막 ACTIVE ADMIN 정지</b> 두 가지만 막는다.
     * 즉 지키려는 건 ADMIN 신분이 아니라 <b>회사가 관리 콘솔 접근 수단을 잃지 않는 것</b>이고, 그 불변식은
     * {@link #requireSurvivingActiveAdmin} 이 여기서 직접 강제한다. ADMIN 을 통째로 면제하면 ADMIN 을
     * 늘리는 것만으로 좌석 한도를 무제한 우회할 수 있어(#850 과 같은 구멍) 강제가 무의미해진다.
     *
     * <p>⚠️ <b>keepUserIds 검증이 이 메서드의 보안 경계다</b>(#890 Phase 2 — 여기가 뚫리면 계정 정지를
     * 남이 조종한다). id 하나라도 이 회사의 ACTIVE 구성원이 아니면(타 회사 소속·이미 SUSPENDED 등) 어느
     * 것이 문제인지 특정하지 않고 {@link ErrorCode#PLAN_FORBIDDEN}으로 일괄 거절한다 — 존재 여부를 흘리지
     * 않는 기존 관례(바로 위 owner 조회 실패 처리)와 맞춘다. <b>이 스코프 검증은 좌석이 남아 정지 대상이
     * 없는 경우에도 항상 수행한다</b> — "지금은 초과가 없어 어차피 아무도 안 정지된다"는 이유로 잘못된
     * id 를 조용히 통과시키면, 이후 한도가 좁아지거나 인원이 늘어 이 값이 실제로 쓰이는 시점에야 뒤늦게
     * 발견되는 잠복 결함이 된다.
     */
    private List<Long> resolveSeatsToSuspend(Long companyId, Plan targetPlan, List<Long> keepUserIds) {
        Integer maxSeats = targetPlan.getMaxSeats();
        if (companyId == null || maxSeats == null) {
            // null = 무제한(Plan javadoc) — 정지 대상 없음.
            return List.of();
        }
        List<User> active = userRepository.findByCompanyIdAndStatusOrderByIdAsc(
                companyId, UserStatus.ACTIVE, Pageable.unpaged());

        boolean hasSelection = keepUserIds != null && !keepUserIds.isEmpty();
        if (hasSelection) {
            Set<Long> activeIds = active.stream().map(User::getId).collect(Collectors.toSet());
            for (Long selected : keepUserIds) {
                if (!activeIds.contains(selected)) {
                    // 타 회사 id·이미 정지된 id 등 — 존재 여부를 흘리지 않고 전체를 거절한다.
                    throw new BusinessException(ErrorCode.PLAN_FORBIDDEN);
                }
            }
        }

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

        if (!hasSelection) {
            // 관리자가 직접 고르지 않음 — 기존 동작(id 오름차순 자동 선정) 그대로(하위 호환).
            for (User user : active) {
                if (keep.size() >= maxSeats) {
                    break;
                }
                keep.add(user.getId());
            }
        } else {
            keep.addAll(keepUserIds);
            if (keep.size() > maxSeats) {
                // owner 를 포함한 유지 인원(union)이 좌석 한도를 넘는다 — 한도 강제가 무의미해지므로 거절한다.
                throw new BusinessException(ErrorCode.PLAN_SEAT_QUOTA_EXCEEDED);
            }
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
     * <p>⚠️ {@code maxSeats == 0}(플랫폼 관리자가 설정 가능) 이어도 <b>owner 가 active 목록에 있으면
     * keep 의 첫 원소로 살아남아</b> 이 단정에 걸리지 않는다(정지되지 않는다). 이 단정이 실제로 발화하는
     * 건 owner 가 active 목록에 없거나 owner 가 ADMIN 이 아닌 경우다 — 현행 가입 경로
     * ({@code User#createCompanyOwner} 가 owner 를 ADMIN+ACTIVE 로 만들고 V8 이 기존 owner 를 소급
     * ADMIN 으로 올린다)에서는 발생하지 않으므로, 외부 호출부가 붙었을 때를 대비한 순수 방어선이다.
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
