-- Flyway V45 — inspections 최근 점검 정렬 복합 인덱스(#1679, #1667 후속).
--
-- 배경: #1667(V43)이 목록 5종 핫 경로(findRecentByFacilityIds/findLatestByFacilityIds)의 정렬 기준에
-- performed_at desc nulls last tie-break를 추가했지만, 그 정렬을 뒷받침하는 인덱스가 없었다
-- (idx_inspections_facility는 facility_id 단일 컬럼이라 정렬 자체는 매 호출마다 힙 스캔 후 정렬 처리된다).
-- #1667 PR머신 리뷰 P3 — 선제 적용 결정(2026-08-19).
--
-- 정렬 순서와 정합: InspectionRepository.findRecentByFacilityIds/findLatestByFacilityIds의
-- order by facility_id, inspection_date desc, performed_at desc nulls last, id desc 를 그대로
-- 인덱스 컬럼 순서·방향에 반영한다(facility_id는 등치 조건 in :facilityIds 라 정렬 방향 무관).
--
-- 멱등 가드(V9/V25/V34와 동일 패턴): create index if not exists.
create index if not exists idx_inspections_recent_order
    on inspections (facility_id, inspection_date desc, performed_at desc nulls last, id desc);
