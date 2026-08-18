-- Flyway V42 — media.analyzed_at 컬럼 추가: 원본 촬영사진 단위 "AI 분석을 거쳤는지" 기록(#1654).
--
-- 배경: 분석 완료(ANALYZED) 후 같은 점검 회차에 추가 업로드된 원본 사진은 재분석 fail-closed 가드
-- (InspectionAnalysisService — 기존 하자 존재 시 재분석 자체를 거부)에 막혀 **영구 미분석** 상태로
-- 남는다 — 이건 현재 가드의 구조적 결함이다(운영 데이터 실측 여부와 무관하게, 이 흐름을 타면 항상
-- 재현된다). ⚠️ 메인 정정(2026-08-18 운영 DB 실측) — 최초 작성 시 "운영 실사례"로 인용했던
-- m473/m474(insp44)·m485(insp47)·m509(insp56)은 **부정확한 인용**이었다. 실측 결과 이 미디어들은
-- 전부 defects.action_media_id/defect_action_logs가 참조하는 **조치 후 사진**이고, V41 백필이 이미
-- purpose=DEFECT_ACTION으로 분류해 애초에 분석 대상에서 제외한다(#1641) — 즉 이 사진들은 이 버그의
-- 실사례가 아니다. 2026-08-18 기준 운영에 이 버그로 실제 영구 미분석에 빠진 회차는 **0건**이다(아래
-- 백필 시뮬레이션 결과와도 일치 — 전 회차 전 미디어가 marked로 떨어져 소급 재분석 대상이 없다).
-- 이 마이그레이션은 실사례 구제가 아니라 **구조적 방어**(앞으로 이 흐름을 탈 미래 업로드를 막음)
-- 목적으로 유지한다. 지금까지 분석 대상은 "회차의 전체 원본 사진"이었는데, media 단위로 "이미 AI
-- 분석을 거쳤는지"를 기록해두면 분석 대상을 "아직 분석되지 않은 원본 사진"으로 좁힐 수 있고, 이미
-- 완료된 회차에 새 사진이 추가돼도 그 사진만 증분으로(기존 하자를 건드리지 않고 append only) 분석할
-- 수 있다.
--
-- purpose(V41)와 마찬가지로 named enum이 아니라 nullable timestamptz 컬럼 하나로 충분하다 — null이면
-- 미분석(증분 분석 대상), non-null이면 그 시각에 분석을 거쳤다는 뜻이다. INSPECTION_SOURCE가 아닌
-- 미디어(DEFECT_ACTION)는 애초에 분석 대상이 아니므로(#1641) 항상 null로 남는다.
--
-- 멱등 가드(#544 P1 회귀 방지 패턴, V19/V32/V41과 동일): add column if not exists로 캐노니컬 DDL이
-- 이미 이 컬럼을 포함한 기존 DB(baseline-on-existing)에서도 no-op으로 안전하게 통과한다.
set local lock_timeout = '5s';

alter table media
    add column if not exists analyzed_at timestamp with time zone;

comment on column media.analyzed_at is '이 미디어(원본 촬영사진)가 AI 분석을 거친 시각(#1654) — null이면 미분석(증분 분석 대상). DEFECT_ACTION(조치 후 사진)은 애초에 분석 대상이 아니라 항상 null.';

-- 백필(#1654) — 이미 분석을 진행/완료한(ANALYZED 이상: ANALYZED/REVIEWED/REPORTED) 회차의 원본
-- 촬영사진(INSPECTION_SOURCE) 중, "그 회차가 실제로 분석을 마쳤을 때 이미 존재했던" 사진만
-- analyzed_at = created_at 으로 채운다. 정확한 "분석 실행 시각" 기록이 없으므로 세 조건 중 하나만
-- 만족해도 "분석됨"으로 본다:
--   ① 하자 보유 — 그 사진에서 실제로 하자가 검출된 경우. 재분석 소프트삭제로 이후 지워진 구세대
--      하자(is_deleted=true)도 "그 시점에 분석 대상이었다"는 사실 자체는 바뀌지 않으므로 deleted
--      여부와 무관하게 media_id 연결 존재만 본다.
--   ② 그 회차의 마지막 하자 생성 시각 이전 업로드 — AI가 하자를 못 찾은 사진(①에 안 걸림)도 실제로는
--      분석 대상이었을 수 있다. 그 회차에서 실제로 탐지된 마지막 하자의 created_at을 "마지막 분석
--      실행 시점"의 근사치로 써서, 그 시점 이전에 업로드된 사진은 이미 그 실행에 포함됐던 것으로 본다.
--   ③ 리뷰 P1 픽스 — 그 회차에 defects 행이 **아예 없음**(하자 0건으로 정상 완료). ①·②는 둘 다
--      "이 회차에 하자가 최소 1건은 있다"를 전제로 한 근사치라, 하자 없이 정상 완료된 회차는 last_defect_at
--      조인이 통째로 비어(lda.inspection_id is null) ①·② 모두 거짓이 된다 — 원래 로직대로면 그런
--      회차의 사진 전부가 "미분석"으로 잘못 남아, 사용자가 화면에서 아무 근거 없이 "추가 사진 분석"
--      버튼을 마주치는 오분류가 났다. ANALYZED 이상 상태 자체가 "이 회차는 이미 분석을 마쳤다"는
--      뜻이므로, 하자가 하나도 없는 회차는 그 전체 원본 사진을 "정상적으로 0건 완료"로 보고 전부
--      analyzed_at을 채운다.
-- 세 조건 모두 해당하지 않는 사진(하자가 있는 회차에서, 그 회차 마지막 하자 생성 시각 이후에
-- 업로드된 사진)만 analyzed_at을 null로 남겨 증분 분석 대상으로 삼는다 — 바로 이 경계가 이 이슈가
-- 고치려는 "분석 후 추가 업로드돼 영구 미분석으로 남던" 사진들이다.
with last_defect_at as (
    select inspection_id, max(created_at) as last_created_at
    from defects
    group by inspection_id
)
update media m
   set analyzed_at = m.created_at
  from inspections i
  left join last_defect_at lda on lda.inspection_id = i.id
 where m.inspection_id = i.id
   and m.purpose = 'INSPECTION_SOURCE'
   and i.status in ('ANALYZED'::inspection_status_type,
                     'REVIEWED'::inspection_status_type,
                     'REPORTED'::inspection_status_type)
   and (
        exists (select 1 from defects d where d.media_id = m.id)
        or (lda.last_created_at is not null and m.created_at <= lda.last_created_at)
        or lda.inspection_id is null
   )
   and m.analyzed_at is null;
