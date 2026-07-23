-- Flyway V8 — 기존 회사 owner 계정을 ADMIN 으로 소급 상향(#636).
--
-- 배경: 기업 회원가입 시 회사 owner 도 role=USER 로 생성돼 왔다(이 변경 이전 createCompanyOwner).
-- 그 결과 회사에 ADMIN 이 0명이 되어 아무도 회사 관리자 페이지(/api/admin/**)에 진입하지 못한다.
-- 이 변경 이후 신규 owner 는 코드(createCompanyOwner)에서 ADMIN 으로 생성되지만, 이미 만들어진 기존
-- owner 계정은 여전히 USER 이므로 여기서 1회 소급 상향한다.
--
-- 안전장치(파괴적 UPDATE 최소화):
--   - role = 'USER' 인 계정만 대상 → 이미 ADMIN/INSPECTOR/COUNSELOR/PLATFORM_ADMIN 인 계정은 불변.
--   - companies.owner_user_id 에 실제로 걸린 계정만 대상 → owner 가 아닌 일반 USER 는 불변.
-- 'ADMIN' 은 baseline(V1) role_type enum 에 존재하는 라벨이다. 컬럼 타입이 PG named enum(role_type)
-- 이므로 리터럴을 role_type 으로 명시 캐스팅한다.
update users
set role = 'ADMIN'::role_type
where role = 'USER'::role_type
  and id in (select owner_user_id from companies);
