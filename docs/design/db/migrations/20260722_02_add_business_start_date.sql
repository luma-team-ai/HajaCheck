-- 독립 마이그레이션(HAJA-25 expand/finalize/verify 체인과 무관, migrations/README.md 참조) — #596
-- companies 에 개업일자(business_start_date) 컬럼을 추가한다. 국세청 사업자등록정보 진위확인
-- (사업자등록번호+대표자명+개업일자)에 필요한 개업일자를 보관한다. 기존 회사 행 backfill 을 위해
-- nullable 로 추가한다(신규 가입은 항상 채워진다). IF NOT EXISTS(PG9.6+)로 재실행 안전.
--
-- 이 파일은 Flyway V5__add_business_start_date.sql 과 동일 변경의 카탈로그 대조용 증분본이다
-- (Ha25IncrementalMigrationTest 가 v0.3+증분 경로와 canonical DDL 을 전체 대조).
alter table companies add column if not exists business_start_date date;

comment on column companies.business_start_date is '개업일자(국세청 진위확인 파라미터)';
