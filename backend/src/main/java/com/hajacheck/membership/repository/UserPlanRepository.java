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
}
