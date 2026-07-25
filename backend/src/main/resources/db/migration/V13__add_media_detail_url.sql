-- Flyway V13 — media.detail_url 컬럼 추가(#788/#789 P2 후속).
--
-- getDetailImage()가 조회 요청마다 원본을 재디코딩/재인코딩하던 성능 문제(PR머신 리뷰 P2) —
-- 썸네일처럼 업로드 시 1회 생성해 디스크에 저장하고 조회는 읽기만 하도록 바꾼다.
-- 이 마이그레이션 이전에 업로드된 기존 행은 detail_url이 NULL로 남는다(백필하지 않음 —
-- V6과 동일한 이유: AI 탐지 파이프라인 이전 기존 데이터에 소급 적용할 근거가 없음).
-- MediaService#getDetailImage()는 NULL이면 원본에서 즉석 생성하는 폴백 경로를 유지한다.
alter table media
    add column if not exists detail_url varchar(500);

comment on column media.detail_url is '분석 결과 뷰어 전용 상세 이미지(그리드용 썸네일보다 큰 해상도) 저장키 — nullable, V13 이전 업로드 행은 NULL(조회 시 원본에서 즉석 생성 폴백)';
