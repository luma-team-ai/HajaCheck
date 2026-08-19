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
-- ⚠️ 비-CONCURRENTLY 트레이드오프 검토·수용(PR #1685 머신 1차 검수 P1, 2026-08-19): 메인이 prod
-- `hajacheck`.inspections 를 직접 실측한 결과 **65행**(초기 단계) — 이 규모에서는 인덱스 빌드가 밀리초
-- 단위라 ACCESS EXCLUSIVE 락으로 인한 부팅/배포 지연 리스크가 사실상 없다. Flyway가 기본적으로
-- 마이그레이션을 트랜잭션 안에서 실행해 CONCURRENTLY와 함께 쓸 수 없으므로(별도 executeInTransaction=false
-- 설정이 추가로 필요, V25 notifications 인덱스와 동일한 이유) 일반 CREATE INDEX를 유지한다. 테이블이
-- 수십만 행 규모로 성장한 뒤 대형 인덱스를 추가할 때는 CONCURRENTLY + executeInTransaction=false 전환을
-- 재검토할 것.
--
-- 멱등 가드(V9/V25/V34와 동일 패턴): create index if not exists.
create index if not exists idx_inspections_recent_order
    on inspections (facility_id, inspection_date desc, performed_at desc nulls last, id desc);

-- ⚠️ 중복 인덱스 정리(PR #1685 머신 1차 검수 P3, 2026-08-19): idx_inspections_facility(facility_id)는
-- 위 복합 인덱스의 선두 컬럼(facility_id)에 완전 포함되는 접두사 중복이다 — PostgreSQL 멀티컬럼
-- btree는 선두 컬럼만 쓰는 등치/IN 조회에도 그대로 쓰일 수 있으므로, facility_id 단독 조건 쿼리
-- (InspectionRepository.findMaxRoundNoByFacilityId/findMaxInspectionDateByFacilityId/
-- findMaxInspectionDateByFacilityIdAndStatus/findByFacilityIdAndRoundNo/findByFacilityIdOrderByRoundNoDesc/
-- findByFacilityIdIn 등 — repo 내 `facilityId =`/`FacilityId(`/`facility_id in` grep으로 전수 확인,
-- inspections 테이블에 facility_id만으로 필터링하는 쿼리가 전부 여기 포함됨)은 idx_inspections_recent_order
-- 로 그대로 커버된다. prod 실측(65행) 규모라 인덱스 drop 자체의 전환 리스크도 없어 이번 PR에서 함께
-- 정리한다(캐노니컬 DDL도 동일하게 반영 — HajaCheck_script.sql 참고).
drop index if exists idx_inspections_facility;
