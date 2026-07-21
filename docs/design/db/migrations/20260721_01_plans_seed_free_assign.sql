-- #517 / HAJA-308 가입 시 FREE 플랜 자동 배정.
--
-- 이 파일은 두 작업을 한 번에 처리한다:
--   1) plans 시드 3건(FREE/STANDARD/ENTERPRISE) — PRD §2.4(v0.44 확정) 요금제 표 그대로.
--      한도값 근거: docs/prd/PRD_hajaCheck.md §2.4 "요금제(안)" 표.
--      max_seats 는 NOT NULL 컬럼이라 Enterprise "무제한"은 sentinel 1000000 으로 표현한다
--      (max_facilities/max_monthly_analyses 는 nullable 이라 무제한을 NULL 로 표현 — Plan 엔티티 계약과 정합).
--   2) 백필 — 기존 회사(companies)와 무소속 개인 활성 유저(users.company_id IS NULL, status ACTIVE) 중
--      ACTIVE/UPGRADE_REQUESTED user_plans 이 없는 대상에 FREE ACTIVE 행을 채운다.
--      (신규 가입은 애플리케이션 PlanProvisioningService 가 처리 — 이 SQL 은 기존 데이터 캐치업 전용)
--
-- Run this file with autocommit enabled (psql default), no explicit transaction wrap needed —
-- 각 문장이 ON CONFLICT/WHERE NOT EXISTS 로 재실행 가능(idempotent)하다.
--
--   psql -X --set ON_ERROR_STOP=1 --dbname "$DATABASE_URL" \
--     --file docs/design/db/migrations/20260721_01_plans_seed_free_assign.sql
--
-- 운영 OCI DB에는 이 파일을 전달만 하고 직접 실행하지 않는다 — 운영자가 백업/점검 시간 확보 후 수동 적용.

select pg_advisory_lock(hashtext('hajacheck:HAJA-308:plans-seed-free-assign'));

do $migration$
begin
    if to_regclass('public.plans') is null then
        raise exception 'HAJA-308 migration requires the plans/user_plans tables (v0.3+ baseline)';
    end if;
end
$migration$;

-- 1) plans 시드 (name unique 제약 전제 — ON CONFLICT DO NOTHING 로 재실행 안전).
insert into plans (name, max_facilities, max_monthly_analyses, max_seats,
                   has_pdf_watermark, has_counselor_access, has_ai_addon, price_monthly)
values
    ('FREE'::plan_name_type, 1, 50, 1, true, false, false, 0.00),
    ('STANDARD'::plan_name_type, 10, 1000, 3, false, true, true, 29000.00),
    ('ENTERPRISE'::plan_name_type, null, null, 1000000, false, true, true, 59000.00)
on conflict (name) do nothing;

-- 2) 백필 — 회사 귀속. company_status_type 은 PENDING_REVIEW/APPROVED/REJECTED 3값뿐이고 탈퇴·삭제
-- 상태 컬럼은 없다(HajaCheck_script.sql companies 정의 확인). PENDING_REVIEW 는 포함이 맞다 — PRD
-- §2.4(v0.47) "Free 즉시 체험"이 승인 대기와 무관하게 가입 즉시 활성화를 명시하고, 애플리케이션 경로
-- (CompanyAccountWriter)도 심사 승인 이전에 FREE를 배정한다. REJECTED 는 개인 백필의
-- status = ACTIVE 필터(정지 계정 제외)와 정합을 맞춰 제외한다 — 반려된 회사에 새로 구독을 열어줄
-- 이유가 없고, table_design.md §2.6 도 반려 회사는 회사 귀속 플랜을 ACTIVE로 둘 수 없다고 명시한다.
-- ACTIVE 부분 유니크 인덱스(uq_user_plans_active_company)가 최종 방어.
insert into user_plans (company_id, plan_id, status, started_at)
select c.id, (select id from plans where name = 'FREE'::plan_name_type), 'ACTIVE'::user_plan_status_type, now()
from companies c
where c.status <> 'REJECTED'::company_status_type
  and not exists (
    select 1 from user_plans up
    where up.company_id = c.id
      and up.status in ('ACTIVE'::user_plan_status_type, 'UPGRADE_REQUESTED'::user_plan_status_type)
);

-- 2) 백필 — 무소속 개인(회사 미소속, 활성) 사용자. ACTIVE 부분 유니크 인덱스(uq_user_plans_active_user)가 최종 방어.
insert into user_plans (user_id, plan_id, status, started_at)
select u.id, (select id from plans where name = 'FREE'::plan_name_type), 'ACTIVE'::user_plan_status_type, now()
from users u
where u.company_id is null
  and u.status = 'ACTIVE'::user_status_type
  and not exists (
    select 1 from user_plans up
    where up.user_id = u.id
      and up.status in ('ACTIVE'::user_plan_status_type, 'UPGRADE_REQUESTED'::user_plan_status_type)
);

select pg_advisory_unlock(hashtext('hajacheck:HAJA-308:plans-seed-free-assign'));
