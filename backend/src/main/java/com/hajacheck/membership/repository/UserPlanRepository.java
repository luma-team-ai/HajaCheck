package com.hajacheck.membership.repository;

import com.hajacheck.membership.entity.UserPlan;
import com.hajacheck.membership.entity.UserPlanStatus;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserPlanRepository extends JpaRepository<UserPlan, Long> {

    Optional<UserPlan> findFirstByCompanyIdAndStatusOrderByStartedAtDesc(Long companyId, UserPlanStatus status);

    Optional<UserPlan> findFirstByUserIdAndStatusOrderByStartedAtDesc(Long userId, UserPlanStatus status);

    // 가입 시 FREE 배정 멱등 판별(#517) — ACTIVE/UPGRADE_REQUESTED 중 하나라도 있으면 이미 구독 중이므로 재배정하지 않는다.
    boolean existsByCompanyIdAndStatusIn(Long companyId, Collection<UserPlanStatus> statuses);

    boolean existsByUserIdAndStatusIn(Long userId, Collection<UserPlanStatus> statuses);
}
