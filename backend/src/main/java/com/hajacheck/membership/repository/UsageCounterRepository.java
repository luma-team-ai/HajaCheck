package com.hajacheck.membership.repository;

import com.hajacheck.membership.entity.UsageCounter;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UsageCounterRepository extends JpaRepository<UsageCounter, Long> {

    Optional<UsageCounter> findByUserPlanIdAndPeriod(Long userPlanId, LocalDate period);
}
