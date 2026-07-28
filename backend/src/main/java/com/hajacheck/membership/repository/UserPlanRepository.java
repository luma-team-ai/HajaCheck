package com.hajacheck.membership.repository;

import com.hajacheck.membership.entity.UserPlan;
import com.hajacheck.membership.entity.UserPlanStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserPlanRepository extends JpaRepository<UserPlan, Long> {

    Optional<UserPlan> findFirstByCompanyIdAndStatusOrderByStartedAtDesc(Long companyId, UserPlanStatus status);

    Optional<UserPlan> findFirstByUserIdAndStatusOrderByStartedAtDesc(Long userId, UserPlanStatus status);

    // 가입 시 FREE 배정 멱등 판별(#517) — ACTIVE/UPGRADE_REQUESTED 중 하나라도 있으면 이미 구독 중이므로 재배정하지 않는다.
    boolean existsByCompanyIdAndStatusIn(Long companyId, Collection<UserPlanStatus> statuses);

    boolean existsByUserIdAndStatusIn(Long userId, Collection<UserPlanStatus> statuses);

    // 플랫폼 관리자 플랜·쿼터 관리(#624) — 회사 귀속 활성 구독 전체(companyId → plan 매핑용, 회사당 최대 1건).
    List<UserPlan> findByCompanyIdIsNotNullAndStatus(UserPlanStatus status);

    // 플랫폼 관리자 서비스 통계(#633) — 회사 귀속 구독의 전체 이력(ACTIVE+EXPIRED 등). expire()가 기존 행을
    // EXPIRED로 내리고 새 행을 발급하는 방식(UserPlan 클래스 상단 javadoc)이라, 이 전체 이력만으로 과거
    // 특정 시점의 구독 여부·플랜 전환 이력을 순수 조회로 재구성할 수 있다(스냅샷 테이블 불필요).
    List<UserPlan> findByCompanyIdIsNotNull();

    // 플랫폼 관리자 날짜별 상담 목록(#1168) — 페이지 내 고객(userId) 들의 활성 개인 구독을 배치 조회
    // (resolveCounselorNames 와 동일한 N+1 방지 패턴). 회사 소속 고객의 플랜은 이 메서드로 잡히지 않지만
    // (owner XOR 상 companyId 구독), 상담 티켓 주체는 userId 이므로 개인 구독 조회만으로 충분하다.
    List<UserPlan> findByUserIdInAndStatus(Collection<Long> userIds, UserPlanStatus status);
}
