-- AP-020(#25 / HAJA-38) 알림 센터 목록 조회(GET /api/notifications) 지원 인덱스.
-- 기존 idx_notifications_user_unread는 is_read=false만 커버하는 partial 인덱스라, 읽음/미읽음
-- 전체를 user_id로 좁혀 생성일 최신순(동률 시 id desc)으로 정렬하는 이 조회(인앱 폴링, 30초 주기)는
-- 그 인덱스로 커버되지 않아 매 폴링마다 seq scan+sort가 발생한다. 이 파일은 HAJA-25 expand/finalize
-- 체인과 무관한 단일 목적 증분 마이그레이션이다 — 신규 설치는 canonical DDL(HajaCheck_script.sql)에
-- 이미 반영된 idx_notifications_user_history를 그대로 사용하고, 기존 운영 DB만 이 파일로 따라잡는다.
--
-- Run this file with autocommit enabled (psql default). CREATE INDEX CONCURRENTLY must not run
-- inside an explicit transaction block. 재실행 가능(IF NOT EXISTS) — 실패 후 원인 해소 뒤 그대로
-- 다시 실행하면 된다.
--
--   psql -X --set ON_ERROR_STOP=1 --dbname "$DATABASE_URL" \
--     --file docs/design/db/migrations/20260719_01_ap020_notification_history_index.sql
--
-- 운영 OCI DB에는 이 파일을 전달만 하고 직접 실행하지 않는다 — 운영자가 백업/점검 시간 확보 후 수동 적용.

select pg_advisory_lock(hashtext('hajacheck:AP-020:notification-history-index'));

do $migration$
begin
    if to_regclass('public.notifications') is null then
        raise exception 'AP-020 migration requires the notifications table (v0.3+ baseline)';
    end if;
end
$migration$;

create index concurrently if not exists idx_notifications_user_history
    on notifications (user_id, created_at desc, id desc);

select pg_advisory_unlock(hashtext('hajacheck:AP-020:notification-history-index'));
