package com.hajacheck.membership.repository;

import com.hajacheck.membership.entity.UserPlan;
import com.hajacheck.membership.entity.UserPlanStatus;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserPlanRepository extends JpaRepository<UserPlan, Long> {

    Optional<UserPlan> findFirstByCompanyIdAndStatusOrderByStartedAtDesc(Long companyId, UserPlanStatus status);

    Optional<UserPlan> findFirstByUserIdAndStatusOrderByStartedAtDesc(Long userId, UserPlanStatus status);
}
