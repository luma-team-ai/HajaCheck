-- Flyway V27 — user_plans 결제 주기 실체화(#1104 / HAJA-525).
--
-- 번호 배분(2026-07-28 팀 확정): V25=#1050(INSPECTION_DUE dedupe 유니크 인덱스) · V26=#1116
-- (media.original_filename) · V27=이 작업. 착수 시점엔 V25로 준비했으나 앞의 두 건이 먼저 dev에
-- 확정돼 재번호했다(결번이 생기면 FlywayMigrationVersionSequenceTest가 CI를 막는다).
--
-- 배경: nextBillingDate 가 DB 필드가 아니라 startedAt + 1개월로 파생 계산됐다. 플랜을 바꿀 때마다 새
-- user_plans 행이 생기고 startedAt = now 로 리셋돼 "결제일이 변경할 때마다 밀리는" 버그가 있었고, 같은
-- 계산이 MyPlanResponse 와 PlatformAdminPlanQuotaService 두 곳에 복제돼 있었다(후자는 FREE 제외 필터가
-- 없어 가입한 지 한 달 넘은 FREE 회사가 전부 "만료됨"으로 표시되는 현존 버그도 있었다). 이 컬럼들이 그
-- 파생 계산을 실체화해 단일 진실 소스가 된다.
--
-- 후속 예약 다운그레이드(#1105 / HAJA-526)가 이 current_period_end 를 예약 실행 기준일로 쓴다.
--
-- 멱등 가드(IF NOT EXISTS): 캐노니컬 DDL(HajaCheck_script.sql)에 이 스키마를 함께 반영하므로,
-- baseline-on-existing 경로(이미 전체 스키마가 적재된 DB)에서 이 파일이 처음 적용될 때
-- 'already exists' 로 기동을 깨뜨리지 않아야 한다(V20·V24와 동일 패턴).

alter table user_plans
    add column if not exists current_period_start timestamptz,
    add column if not exists current_period_end   timestamptz;

comment on column user_plans.current_period_start is '현재 결제 주기 시작 시각(#1104). 신규 발급/전이 시각으로 채워진다.';

comment on column user_plans.current_period_end is '현재 결제 주기 종료 시각(#1104). NULL = 무기한(FREE). 결제 승인 전이 시 리셋(now~now+1개월), 관리자 플랜 변경(무결제) 시 기존 값이 승계된다.';

-- 백필 — 기존 파생 규칙(startedAt + 1개월)을 그대로 옮긴다(동작 변화 최소화).
-- current_period_start 는 유료/무료 구분 없이 started_at 을 그대로 옮긴다.
update user_plans
   set current_period_start = started_at
 where current_period_start is null;

-- current_period_end 는 유료 플랜(plans.price_monthly > 0)만 채운다. FREE 는 만료가 없으므로 NULL로 둔다.
update user_plans up
   set current_period_end = up.started_at + interval '1 month'
  from plans p
 where up.plan_id = p.id
   and p.price_monthly is not null
   and p.price_monthly > 0
   and up.current_period_end is null;
