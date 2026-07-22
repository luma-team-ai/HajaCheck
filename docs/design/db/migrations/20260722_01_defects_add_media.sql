-- HAJA-314(#527) 하자 상세 화면 실사진 표시 지원 — defects.media_id 추가.
-- defects는 bbox_x/y/w/h로 결함 위치를 표시하지만, 그 좌표가 어느 촬영 이미지(media) 위의 것인지
-- 연결할 컬럼이 없어 하자 상세 화면에 실제 사진을 띄울 방법이 없었다(HAJA-314 조사).
-- 이 파일은 media/defects 스키마에 nullable 컬럼 하나와 인덱스 하나만 추가하는 단일 목적 증분
-- 마이그레이션으로, HAJA-25 expand/finalize 체인과 무관하다. AI 탐지 파이프라인이 아직 없어(신규
-- Defect 생성 경로가 프로덕션 코드에 없음) 기존 행은 전부 NULL로 남고, 이번 파일이 값을 백필하지도
-- 않는다. NOT NULL 강제는 하지 않는다.
--
-- Run this file with autocommit enabled (psql default). 재실행 가능(IF NOT EXISTS).
--
--   psql -X --set ON_ERROR_STOP=1 --dbname "$DATABASE_URL" \
--     --file docs/design/db/migrations/20260722_01_defects_add_media.sql
--
-- 운영 OCI DB에는 이 파일을 전달만 하고 직접 실행하지 않는다 — 운영자가 백업/점검 시간 확보 후 수동 적용.

select pg_advisory_lock(hashtext('hajacheck:HAJA-314:defects-add-media'));

do $migration$
begin
    if to_regclass('public.defects') is null or to_regclass('public.media') is null then
        raise exception 'HAJA-314 migration requires the defects and media tables (v0.3+ baseline)';
    end if;
end
$migration$;

alter table defects
    add column if not exists media_id bigint references media;

comment on column defects.media_id is '결함이 탐지된 촬영 이미지 식별자(HAJA-314, nullable — AI 탐지 파이프라인 도입 전 기존 행은 NULL)';

create index if not exists idx_defects_media
    on defects (media_id);

select pg_advisory_unlock(hashtext('hajacheck:HAJA-314:defects-add-media'));
