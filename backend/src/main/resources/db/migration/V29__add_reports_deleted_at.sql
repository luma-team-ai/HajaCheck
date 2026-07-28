-- Flyway V29 — reports DRAFT soft delete 컬럼 추가(#1172).
--
-- 정책: FINALIZED 보고서는 삭제 불가, DRAFT 보고서만 soft delete 대상이다.
-- hard delete 대신 삭제 시각을 남겨 운영/감사 확인이 가능하게 한다.
--
-- 구현 후속:
-- - DRAFT 삭제 API는 deleted_at만 세팅한다.
-- - 보고서 목록/버전 이력/요약 집계는 deleted_at is null 조건으로 삭제된 DRAFT를 제외한다.
-- - FINALIZED 삭제 요청은 서비스/API 레벨에서 거부한다.
--
-- IF NOT EXISTS: 캐노니컬 DDL에 컬럼이 먼저 반영된 DB에서도 안전하게 지나가게 한다.

alter table reports
    add column if not exists deleted_at timestamptz;

comment on column reports.deleted_at is '보고서 DRAFT soft delete 시각(#1172). NULL = 활성. FINALIZED 보고서는 삭제 불가 정책으로 유지한다.';
