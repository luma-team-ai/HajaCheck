package com.hajacheck.membership.repository;

import com.hajacheck.membership.entity.Plan;
import com.hajacheck.membership.entity.PlanName;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlanRepository extends JpaRepository<Plan, Long> {

    Optional<Plan> findByName(PlanName name);
}
