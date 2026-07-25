-- Flyway V14 — user_status_type PG enum에 WAITING(초대 대기) 라벨 추가(#792).
--
-- 회사 초대 수락 전 사용자를 표현할 상태값이 없어 신설한다. ALTER TYPE ... ADD VALUE는
-- PG12+에서는 트랜잭션 안에서도 실행 가능(단, 같은 트랜잭션에서 그 값을 바로 사용하지만
-- 않으면 됨) — Flyway 기본 트랜잭션 실행으로 문제없다. IF NOT EXISTS(PG12+)로 재실행 안전.
alter type user_status_type add value if not exists 'WAITING';
