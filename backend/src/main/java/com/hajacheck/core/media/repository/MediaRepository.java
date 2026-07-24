package com.hajacheck.core.media.repository;

import com.hajacheck.core.media.entity.Media;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MediaRepository extends JpaRepository<Media, Long> {

    // 조치 결과 등록(HAJA-393/#725) — actionMediaId가 같은 점검(=같은 하자가 속한 inspection) 소속인지
    // 검증한다. defect 자체는 이미 findByIdAndCompanyId로 회사 스코프를 확인한 뒤라, 이 조회로
    // 타 점검/타 회사 media를 조치 후 사진으로 연결하는 IDOR을 차단한다.
    Optional<Media> findByIdAndInspectionId(Long id, Long inspectionId);

    // 관리자 플랜·쿼터 관리(#507) — 멤버별 "이번 달 분석한 이미지 장수" 근사치. media 테이블에 업로더 FK가
    // 없어(point-in-time 스키마) 담당 점검자(inspections.assigned_inspector_id) 단위로 집계한다 — 한 점검을
    // 여러 사람이 함께 촬영/업로드하면 실제 기여자 분포와 다를 수 있는 근사값이며, 회사 전체 합계(KPI 카드)는
    // 이 근사치가 아니라 usage_counters.analyzed_image_count(권위 있는 회사 단위 집계)를 그대로 쓴다.
    @Query("""
            select i.assignedInspectorId as inspectorId, count(m) as mediaCount
            from Media m
            join Inspection i on i.id = m.inspectionId
            where i.assignedInspectorId in :inspectorIds
              and m.createdAt >= :from and m.createdAt < :to
            group by i.assignedInspectorId
            """)
    List<InspectorMediaCount> countByAssignedInspectorInAndCreatedAtBetween(
            @Param("inspectorIds") List<Long> inspectorIds,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    interface InspectorMediaCount {
        Long getInspectorId();

        long getMediaCount();
    }
}
