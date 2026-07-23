-- Flyway V5 — companies 에 개업일자(business_start_date) 컬럼 추가(#596).
--
-- 국세청 사업자등록정보 진위확인(사업자등록번호+대표자명+개업일자)에 필요한 개업일자를 보관한다.
-- 기존 회사 행은 이 값이 없으므로 nullable 로 추가한다(backfill 대상). 신규 가입은 항상 채워진다.
-- ADD COLUMN IF NOT EXISTS(PG9.6+)로 재실행 안전(V4 의 IF NOT EXISTS 스타일과 정합).
alter table companies add column if not exists business_start_date date;
