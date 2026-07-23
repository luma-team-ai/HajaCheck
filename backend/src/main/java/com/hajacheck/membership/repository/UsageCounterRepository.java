package com.hajacheck.membership.repository;

import com.hajacheck.membership.entity.UsageCounter;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UsageCounterRepository extends JpaRepository<UsageCounter, Long> {

    Optional<UsageCounter> findByUserPlanIdAndPeriod(Long userPlanId, LocalDate period);

    // 플랫폼 관리자 서비스 통계(#633) — 전 구독(개인+회사) 대상 기간별 사용량 합산 원천 데이터.
    List<UsageCounter> findByPeriodBetween(LocalDate from, LocalDate to);
}
