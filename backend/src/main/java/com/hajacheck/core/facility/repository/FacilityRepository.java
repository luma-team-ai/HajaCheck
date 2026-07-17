package com.hajacheck.core.facility.repository;

import com.hajacheck.core.facility.entity.Facility;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FacilityRepository extends JpaRepository<Facility, Long> {

    List<Facility> findByOwnerId(Long ownerId);

    Optional<Facility> findByIdAndOwnerId(Long id, Long ownerId);

    long countByOwnerId(Long ownerId);

    // 대시보드 개요(HAJA-17) — 전월 대비 증감률 계산용: 기준 시각 이전 누적 등록 수.
    long countByOwnerIdAndCreatedAtBefore(Long ownerId, LocalDateTime before);

    // 점검 회차 채번 동시성 경쟁 방지(dev-05-02) — 같은 시설물에 대한 동시 점검 생성 요청을
    // 트랜잭션 종료 시점까지 직렬화하기 위한 행 잠금. 시설물은 항상 존재가 보장된 상태에서 호출되므로
    // (InspectionService 가 소유권 검증 이후에만 호출) 값 자체는 사용하지 않고 잠금 획득 용도로만 쓴다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from Facility f where f.id = :id")
    Optional<Facility> findByIdForUpdate(@Param("id") Long id);
}
