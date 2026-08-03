-- Flyway V41 — inspection_status_type PG enum에 FAILED(분석 실패) 라벨 추가.
--
-- 회차에 속한 사진 중 하나라도 AI 분석에 실패하면 회차 상태를 FAILED로 표시해, 성공/실패가
-- 섞인 회차가 조용히 ANALYZED(완료)로 넘어가 검수 단계로 진입하는 것을 막는다. ALTER TYPE ...
-- ADD VALUE는 PG12+에서는 트랜잭션 안에서도 실행 가능(단, 같은 트랜잭션에서 그 값을 바로
-- 사용하지만 않으면 됨) — Flyway 기본 트랜잭션 실행으로 문제없다. IF NOT EXISTS(PG12+)로 재실행 안전.
alter type inspection_status_type add value if not exists 'FAILED';
