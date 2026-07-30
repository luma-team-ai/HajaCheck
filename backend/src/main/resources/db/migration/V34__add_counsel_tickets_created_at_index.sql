-- Flyway V34 — counsel_tickets.created_at 인덱스 신설(#1168, PR머신 리뷰 P2).
--
-- 플랫폼 관리자 날짜별 상담 목록 조회(findByCreatedAtBetweenOrderByCreatedAtDesc)가
-- counsel_tickets.created_at 을 WHERE·ORDER BY 양쪽에서 사용하는데, 기존 인덱스
-- (idx_counsel_tickets_counselor/user/session)는 이 컬럼을 커버하지 않아 티켓이
-- 누적될수록 매 조회가 풀 테이블 스캔이 된다.
--
-- 번호 조율: 착수 시점 다음 빈 번호가 V33이었으나 다른 작업자가 V33을 먼저 쓰고 있어
-- V34로 배정한다(project convention — 먼저 머지되는 쪽이 번호를 가져간다, V1~V33 무수정).
create index if not exists idx_counsel_tickets_created_at
    on counsel_tickets (created_at);
