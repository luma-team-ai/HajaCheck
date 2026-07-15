package com.hajacheck.core.facility.repository;

import com.hajacheck.core.facility.entity.Facility;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FacilityRepository extends JpaRepository<Facility, Long> {

    List<Facility> findByOwnerId(Long ownerId);

    Optional<Facility> findByIdAndOwnerId(Long id, Long ownerId);

    long countByOwnerId(Long ownerId);

    // 대시보드 개요(HAJA-17) — 전월 대비 증감률 계산용: 기준 시각 이전 누적 등록 수.
    long countByOwnerIdAndCreatedAtBefore(Long ownerId, LocalDateTime before);
}
