package com.hajacheck.core.inspection.repository;

import com.hajacheck.core.inspection.entity.Inspection;
import com.hajacheck.core.inspection.entity.InspectionStatus;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InspectionRepository extends JpaRepository<Inspection, Long> {

    // 대시보드 개요(HAJA-17) — 소유 시설물 범위 내 점검 전체(최근 점검 목록 조합용 createdBy/facilityId 매핑 포함).
    List<Inspection> findByFacilityIdIn(Collection<Long> facilityIds);

    List<Inspection> findTop10ByFacilityIdInOrderByInspectionDateDescIdDesc(Collection<Long> facilityIds);

    long countByFacilityIdInAndStatusIn(Collection<Long> facilityIds, Collection<InspectionStatus> statuses);

    @Query("select count(i) from Inspection i where i.facilityId in :facilityIds and i.status in :statuses "
            + "and i.inspectionDate >= :from and i.inspectionDate < :to")
    long countByFacilityIdInAndStatusInAndInspectionDateRange(
            @Param("facilityIds") Collection<Long> facilityIds,
            @Param("statuses") Collection<InspectionStatus> statuses,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);
}
