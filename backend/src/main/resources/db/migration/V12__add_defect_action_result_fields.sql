-- Flyway V12 — defects 조치 결과 등록 필드 추가(HAJA-393/#725, Figma 모달 node 1562-3682 실사 확정).
-- handoff 문서는 애초 "V5"를 지정했으나 그 사이 V5~V11이 이미 다른 PR(#598/#527/#637 등)에서 선점해
-- 다음 번호인 V12로 진행한다(V1~V11 전부 무수정).
--
-- "조치 완료 등록" 버튼 제출 시 저장되는 4개 필드(조치 후 사진/조치 내용/조치일/담당자)를 저장할 컬럼이
-- 없어 신설한다. 모두 nullable — 조치 등록 전(RESOLVED 전이 전) 하자는 값이 없는 것이 정상이며,
-- 이 마이그레이션은 기존 행을 백필하지 않는다(V6 defects.media_id 와 동일 정책).
alter table defects
    add column if not exists action_media_id bigint references media,
    add column if not exists action_content text,
    add column if not exists action_date date,
    add column if not exists action_assignee_id bigint references users;

comment on column defects.action_media_id is '조치 후 사진(HAJA-393/#725) — 조치 결과 등록 시 업로드한 촬영 이미지 식별자, nullable';
comment on column defects.action_content is '조치 내용(HAJA-393/#725) — 조치 결과 등록 시 입력한 텍스트, nullable';
comment on column defects.action_date is '조치일(HAJA-393/#725) — 조치 결과 등록 시 입력한 날짜, nullable';
comment on column defects.action_assignee_id is '조치 담당자(HAJA-393/#725) — GET /api/facilities/assignable-users 로 선택된 회사 소속 사용자, nullable';
