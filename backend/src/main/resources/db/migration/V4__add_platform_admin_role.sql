-- Flyway V4 — role_type PG enum에 PLATFORM_ADMIN 라벨 추가(#534).
--
-- Role.java에 PLATFORM_ADMIN을 추가했지만 이 값이 role_type PG enum에 없으면
-- role='PLATFORM_ADMIN' 사용자가 로그인 시 InternalAuthenticationServiceException으로 실패한다.
-- ALTER TYPE ... ADD VALUE는 PG12+에서는 트랜잭션 안에서도 실행 가능(단, 같은 트랜잭션에서
-- 그 값을 바로 사용하지만 않으면 됨) — Flyway 기본 트랜잭션 실행으로 문제없다.
-- IF NOT EXISTS(PG12+)로 재실행 안전.
alter type role_type add value if not exists 'PLATFORM_ADMIN';
