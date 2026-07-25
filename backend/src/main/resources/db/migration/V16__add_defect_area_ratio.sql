-- Flyway V16 — defects.area_ratio 컬럼 추가(#803 분석 결과 뷰어 면적비율 지원).
--
-- AI 서버(#802)가 계산한 각 결함의 면적비율(detected bbox 면적 ÷ 이미지 전체 면적)을 저장한다.
-- nullable Double 컬럼 — 미래 계산 대상이거나 AI 파이프라인 미적용 회차의 기존 행은 NULL로 남는다.
--
alter table defects
    add column if not exists area_ratio double precision;

comment on column defects.area_ratio is '결함 면적비율(탐지 bbox 면적 ÷ 이미지 전체 면적, HAJA-803, nullable)';
