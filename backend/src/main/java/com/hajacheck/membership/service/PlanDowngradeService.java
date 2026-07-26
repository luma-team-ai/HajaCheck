package com.hajacheck.membership.service;

import com.hajacheck.auth.entity.Company;
import com.hajacheck.auth.entity.User;
import com.hajacheck.auth.entity.UserStatus;
import com.hajacheck.auth.repository.CompanyRepository;
import com.hajacheck.auth.repository.UserRepository;
import com.hajacheck.core.facility.repository.FacilityRepository;
import com.hajacheck.membership.dto.DowngradeOverflow;
import com.hajacheck.membership.entity.Plan;
import java.util.ArrayList;
import java.util.List;
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
 *   <li><b>시설물</b> — 상태 컬럼을 추가하지 않고 <b>계산 판정</b>한다({@link #isFacilityReadOnly}).
 *       한도가 다시 올라가면 자동 복구된다는 게 컬럼 방식 대비 이점이다.</li>
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
    public DowngradeOverflow preview(Long companyId, Plan targetPlan) {
        List<Long> seats = resolveSeatsToSuspend(companyId, targetPlan);
        return new DowngradeOverflow(seats, resolveFacilityOverflowCount(companyId, targetPlan));
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
    public DowngradeOverflow applyOverflow(Long companyId, Plan targetPlan) {
        DowngradeOverflow overflow = preview(companyId, targetPlan);
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
     * 이 시설물이 현재 요금제 한도를 넘어 "읽기 전용"인지 — id 오름차순 순위가 {@code maxFacilities} 를
     * 넘으면 읽기 전용이다(계산 판정, 클래스 javadoc 참고).
     *
     * <p>읽기 전용이어도 <b>조회·기존 점검 이력은 그대로</b>다. 차단 대상은 <b>신규 점검 생성</b>뿐이다 —
     * 점검·보고서가 시설물을 참조하므로 목록에서 빼거나 삭제하면 참조 정합성이 깨진다.
     */
    public boolean isFacilityReadOnly(Long companyId, Long facilityId, Plan plan) {
        Integer maxFacilities = plan.getMaxFacilities();
        if (maxFacilities == null || companyId == null || facilityId == null) {
            // null = 무제한(Plan javadoc) — 읽기 전용으로 떨어지는 시설물이 없다.
            return false;
        }
        long rank = facilityRepository.countByCompanyIdAndIdLessThanEqual(companyId, facilityId);
        return rank > maxFacilities;
    }

    /**
     * 정지 대상 산출 — ACTIVE 구성원을 id 오름차순으로 두고 한도만큼 앞에서 남긴 뒤 나머지를 반환한다.
     *
     * <p><b>owner 는 무조건 유지</b>한다(정지되면 회사가 관리 불능). 목록 순서와 무관하게 가장 먼저
     * 좌석을 차지한다.
     *
     * <p>⚠️ <b>ADMIN 역할도 정지 대상에 포함한다</b> — {@code AdminUserService#changeStatus} 는 ADMIN 의
     * SUSPENDED 전환을 막지만, 그 가드의 취지는 "관리자끼리 서로 정지시키는 것"을 막는 데 있다. 여기서는
     * <b>owner 본인이 자기 회사 규모를 줄이는 의도된 행위</b>이고, ADMIN 을 면제하면 ADMIN 을 늘리는
     * 것만으로 좌석 한도를 무제한 우회할 수 있어(#850 과 같은 구멍) 강제가 무의미해진다.
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
        Long ownerUserId = companyRepository.findById(companyId)
                .map(Company::getOwnerUserId)
                .orElse(null);

        List<Long> keep = new ArrayList<>();
        if (ownerUserId != null && active.stream().anyMatch(u -> ownerUserId.equals(u.getId()))) {
            keep.add(ownerUserId);
        }
        for (User user : active) {
            if (keep.size() >= maxSeats) {
                break;
            }
            if (!keep.contains(user.getId())) {
                keep.add(user.getId());
            }
        }
        return active.stream()
                .map(User::getId)
                .filter(id -> !keep.contains(id))
                .toList();
    }

    private int resolveFacilityOverflowCount(Long companyId, Plan targetPlan) {
        Integer maxFacilities = targetPlan.getMaxFacilities();
        if (companyId == null || maxFacilities == null) {
            return 0;
        }
        long owned = facilityRepository.countByCompanyId(companyId);
        return (int) Math.max(0, owned - maxFacilities);
    }
}
