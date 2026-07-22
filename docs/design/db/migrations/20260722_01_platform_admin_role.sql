-- 독립 마이그레이션(HAJA-25 expand/finalize/verify 체인과 무관, migrations/README.md 참조) — #534
-- role_type PG enum에 PLATFORM_ADMIN 라벨을 추가한다. 이 값이 없으면 role='PLATFORM_ADMIN' 사용자가
-- 로그인 시 InternalAuthenticationServiceException(No enum constant Role.PLATFORM_ADMIN)으로 실패한다.
-- ALTER TYPE ... ADD VALUE는 트랜잭션 안에서 실행할 수 없으므로 psql 기본 autocommit으로 실행한다.
-- IF NOT EXISTS(PG12+)로 재실행 안전.
alter type role_type add value if not exists 'PLATFORM_ADMIN';
