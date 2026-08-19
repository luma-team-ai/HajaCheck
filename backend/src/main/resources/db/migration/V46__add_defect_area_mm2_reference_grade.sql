-- Flyway V46 — defects.area_mm2_reference_grade 컬럼 추가(#1682 ai-server 후속, #1683 backend 저장·응답 연결).
--
-- ai-server(#1682, defect_detection_chain.py DetectedDefect.area_mm2_reference_grade)가 area_mm2 기반으로
-- 산출한 참고 등급(A~E 문자열)을 additive로 내려준다. crackWidthMm/areaMm2(V44)와 동일한 패턴 —
-- area_mm2가 있는 SPALLING/REBAR_EXPOSURE만 값이 있을 수 있고, area_mm2가 없거나 CRACK 타입이면 NULL로 남는다.
-- A~E 값을 문자열 그대로 저장한다(enum 변환 불요 — 참고값이라 본등급 grade 컬럼과 무관·불변).
--
-- 멱등 가드(#544 P1 회귀 방지 패턴, V16/V41/V44와 동일) — add column if not exists로 캐노니컬 DDL이
-- 이미 이 컬럼을 포함한 기존 DB(baseline-on-existing)에서도 no-op으로 안전하게 통과한다.
alter table defects
    add column if not exists area_mm2_reference_grade varchar(1);

comment on column defects.area_mm2_reference_grade is 'mm² 기반 참고 등급(A~E, #1682/#1683) — area_mm2가 있는 SPALLING/REBAR_EXPOSURE만 값 존재 가능, 본등급(grade)과 무관한 참고값·nullable';
