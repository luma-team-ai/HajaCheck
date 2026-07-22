-- Flyway V2 — plans 시드(FREE/STANDARD/ENTERPRISE, #359).
--
-- V1(baseline)에는 이 동일한 INSERT가 이미 포함돼 있어(신규 빈 DB에서 V1이 실제로 실행되는 경로) 이
-- 문장은 그 경로에서는 중복 실행이지만 ON CONFLICT DO NOTHING으로 안전하다.
--
-- 이 파일이 진짜로 필요한 경로는 baseline-on-migrate(baseline-version=1)로 V1 실행이 "이미 적용됨"
-- 스탬프만 되고 건너뛰어지는 기존 DB(arm1 프로덕션, 팀원 로컬)다 — 그 DB들은 전체 스키마는 있지만
-- plans 3티어 시드가 없을 수 있어(PlanSeedGuard 부팅 가드가 요구, #517/#518) 여기서 채운다.
--
-- 값 출처: docs/design/db/migrations/20260721_01_plans_seed_free_assign.sql 의 plans INSERT 부분과
-- 동일(PRD §2.4 v0.44 확정 요금제 표). 그 파일의 사용자/회사 백필 부분은 baseline 대상이 아니다
-- (신규 DB는 아직 유저가 없다).
insert into plans (name, max_facilities, max_monthly_analyses, max_seats,
                   has_pdf_watermark, has_counselor_access, has_ai_addon, price_monthly)
values
    ('FREE'::plan_name_type, 1, 50, 1, true, false, false, 0.00),
    ('STANDARD'::plan_name_type, 10, 1000, 3, false, true, true, 29000.00),
    ('ENTERPRISE'::plan_name_type, null, null, 1000000, false, true, true, 59000.00)
on conflict (name) do nothing;
