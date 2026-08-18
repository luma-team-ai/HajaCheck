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
--    전혀 없거나(또는 이상값이면) min(created_at, 업로드 시각)으로 대신한다. 조치 후 사진
--    (DEFECT_ACTION)은 실제 점검 수행 이후에 등록될 수 있어 대상에서 제외한다(V41 purpose 구분과
--    동일 원칙). 미디어가 아예 없는 회차는 null로 남는다.
--
--    PR머신 리뷰 P2 — 첫 서브쿼리(captured_at)에 2000-01-01 ~ now() 범위 가드를 추가한다. 카메라 시계
--    오설정·손상된 EXIF 메타데이터로 captured_at이 1999년 이전이거나 미래를 가리키는 손상값이 실제로
--    관측되는데, 가드 없이 그대로 min()에 태우면 이상값이 performed_at에 유입돼 동일 날짜 tie-break
--    정렬이 실사용에서 어긋난다. 범위를 벗어난 captured_at은 이 서브쿼리에서 제외되어(=이상값이 있는
--    행만 골라내는 게 아니라 그 값 자체를 min() 후보에서 뺀다) 자동으로 min(created_at) coalesce
--    분기로 폴백한다 — 라이브 경로(MediaWriter.resolveCandidate의 EXIF_SANITY_FLOOR=2000-01-01,
--    "업로드 시각보다 미래면 폴백")와 동일한 이상값 정의를 백필에도 재현해 의미론을 정합시킨다.
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
            and m.captured_at is not null
            and m.captured_at >= timestamptz '2000-01-01'
            and m.captured_at <= now()),
        (select min(m.created_at)
           from media m
          where m.inspection_id = i.id
            and m.purpose = 'INSPECTION_SOURCE'))
where i.performed_at is null
  and exists (
        select 1 from media m
         where m.inspection_id = i.id
           and m.purpose = 'INSPECTION_SOURCE');
