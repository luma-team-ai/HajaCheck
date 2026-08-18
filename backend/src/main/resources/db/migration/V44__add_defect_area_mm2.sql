-- Flyway V44 — defects.area_mm2 컬럼 추가(#1658 후속, #1668 backend 저장·응답 연결).
--
-- ai-server(#1658/PR #1660)가 카드 기준물로 환산한 결함 실측 면적(mm²)을 additive로 내려준다.
-- crackWidthMm(V-이전, #1487/#1547)과 동일한 패턴 — SPALLING/REBAR_EXPOSURE만 값이 있을 수 있고,
-- 카드 미검출이거나 CRACK 타입이면 NULL로 남는다. nullable Double 컬럼이라 기존 행은 전부 NULL.
--
-- 멱등 가드(#544 P1 회귀 방지 패턴, V16/V41과 동일) — add column if not exists로 캐노니컬 DDL이
-- 이미 이 컬럼을 포함한 기존 DB(baseline-on-existing)에서도 no-op으로 안전하게 통과한다.
alter table defects
    add column if not exists area_mm2 double precision;

comment on column defects.area_mm2 is '결함 실측 면적(mm², #1658/#1668) — SPALLING/REBAR_EXPOSURE만 값 존재 가능, 카드 미검출·CRACK 타입이면 nullable';
