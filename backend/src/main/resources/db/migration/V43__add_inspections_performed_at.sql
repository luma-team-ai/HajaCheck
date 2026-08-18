-- Flyway V43 — inspections.performed_at 추가: 동일 날짜 점검 실제 수행 순서 보장(#1667).
--
-- 배경: 최신 점검 판정이 inspection_date DESC, id DESC라 같은 날짜에 등록된 여러 점검 회차의 실제
-- 수행 순서를 보장하지 못한다(id는 생성 순서일 뿐 촬영 순서의 대리값이 아니다).
--
-- 자동 세팅(MediaWriter, #1667): 회차의 INSPECTION_SOURCE 미디어 업로드 시 EXIF captured_at(min)을
-- 우선 후보로 쓰고, 없으면 업로드 시각을 후보로 쓴다. 이미 performed_at이 있으면 더 이른 값일 때만
-- 갱신한다(늦은 값으로 덮지 않음) — 사용자 입력 UI는 없다.
--
-- 정렬 tie-break 확장(InspectionRepository.findLatestByFacilityIds/findRecentByFacilityIds):
-- inspection_date desc, performed_at desc nulls last, id desc.
--
-- 멱등 가드(V19/V32/V41과 동일 패턴): add column if not exists.
set local lock_timeout = '5s';

-- 1) performed_at 컬럼 추가.
alter table inspections
    add column if not exists performed_at timestamptz;

comment on column inspections.performed_at is '점검 실제 수행 시각(#1667) — 회차의 INSPECTION_SOURCE 미디어 EXIF 촬영시각(min) 또는 업로드 시각으로 자동 세팅(사용자 입력 없음). 동일 inspection_date 내 정렬 tie-break(inspection_date desc, performed_at desc nulls last, id desc)에 사용.';

-- 2) 백필 — 기존 회차는 그 회차의 INSPECTION_SOURCE 미디어 중 min(captured_at)을 우선 쓰고, 촬영시각이
--    전혀 없으면 min(created_at, 업로드 시각)으로 대신한다. 조치 후 사진(DEFECT_ACTION)은 실제 점검
--    수행 이후에 등록될 수 있어 대상에서 제외한다(V41 purpose 구분과 동일 원칙). 미디어가 아예 없는
--    회차는 null로 남는다.
--
--    WHERE i.performed_at is null로 이미 채워진 행은 건드리지 않으므로 재실행해도 결과가 달라지지
--    않는다(V41과 동일한 멱등 원칙 — 새로 심긴 미디어가 있어도 이미 세팅된 회차는 이 백필이 다시
--    계산하지 않고, 그 이후로는 애플리케이션(MediaWriter.applyPerformedAt)이 담당한다).
update inspections i
set performed_at = coalesce(
        (select min(m.captured_at)
           from media m
          where m.inspection_id = i.id
            and m.purpose = 'INSPECTION_SOURCE'
            and m.captured_at is not null),
        (select min(m.created_at)
           from media m
          where m.inspection_id = i.id
            and m.purpose = 'INSPECTION_SOURCE'))
where i.performed_at is null
  and exists (
        select 1 from media m
         where m.inspection_id = i.id
           and m.purpose = 'INSPECTION_SOURCE');
