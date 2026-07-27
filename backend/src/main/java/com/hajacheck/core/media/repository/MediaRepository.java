package com.hajacheck.core.media.repository;

import com.hajacheck.core.facility.entity.Facility;
import com.hajacheck.core.media.entity.Media;
import com.hajacheck.core.media.entity.MediaFileType;
import java.time.LocalDateTime;
import java.util.Collection;
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

    // AI 분석(dev-05-04) 대상 이미지 조회 — id asc로 고정해 잡 진행률(파일별 처리 현황) 순서가
    // 요청마다 흔들리지 않게 한다(defect 조회의 id ASC 고정과 동일 이유).
    List<Media> findByInspectionIdAndFileTypeOrderByIdAsc(Long inspectionId, MediaFileType fileType);

    // 점검 회차별 미디어 전체 조회(#803 분석 결과 뷰어) — 업로드된 모든 미디어를 반환(하자 유무 무관).
    // id asc 고정으로 조회 순서를 일관되게 유지한다.
    List<Media> findByInspectionIdOrderByIdAsc(Long inspectionId);

    // 시설물 대표 사진(#632/#652, HAJA-377) — 최대 4장 제한을 업로드 전 애플리케이션 레벨에서 검증하기
    // 위한 현재 보유 장수 집계. facility_id 만 채워진 로우(inspection_id=null)만 센다.
    long countByFacilityId(Long facilityId);

    // 시설물 대표 사진 목록 조회 — id asc 고정으로 조회 순서를 일관되게 유지한다.
    List<Media> findByFacilityIdOrderByIdAsc(Long facilityId);

    // 시설물 목록 썸네일 배치 조회(HAJA-367/#670) — 시설물 수만큼 findByFacilityIdOrderByIdAsc를
    // 반복 호출하면 N+1이므로, 대상 시설물 전체의 (facilityId, mediaId) 쌍을 한 번에 가져온다.
    // facilityId asc, id asc로 정렬해 서비스 계층에서 facilityId별 첫 값만 취하면 시설물당 최초 등록
    // 사진 1건이 된다(DefectRepository.findLatestByFacilityIds의 배치 조립 패턴과 동일 원칙).
    // Media 는 Facility 와 연관관계 객체가 없어(facilityId 값 컬럼만 보유) countByAssignedInspectorIn...
    // 처럼 "join Facility f on f.id = m.facilityId" 애드혹 조인으로 companyId 를 방어적으로 재검증한다
    // (호출부가 이미 회사 스코프로 걸러진 facilityIds 만 넘기지만, DefectRepository.findLatestByFacilityIds
    // 와 동일하게 리포지토리 계층에서도 한 번 더 막아 향후 다른 호출부가 생겨도 안전하게 한다).
    @Query("select m.facilityId as facilityId, m.id as mediaId from Media m "
            + "join Facility f on f.id = m.facilityId "
            + "where m.facilityId in :facilityIds and f.companyId = :companyId "
            + "order by m.facilityId asc, m.id asc")
    List<FacilityRepresentativeMediaProjection> findFirstIdsByFacilityIds(
            @Param("facilityIds") Collection<Long> facilityIds, @Param("companyId") Long companyId);

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
