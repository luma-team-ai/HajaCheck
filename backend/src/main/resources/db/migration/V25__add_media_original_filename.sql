-- Flyway V25 — media.original_filename 컬럼 추가.
-- (V22~V24는 다른 브랜치 작업이 dev에 먼저 병합돼 선점 — 번호 충돌 방지를 위해 V25로 이어 붙인다.)
--
-- AI 분석 실행/상태 화면(이미지별 처리 현황 테이블)이 실제 업로드 파일명 대신 "이미지 N" 순번
-- 라벨만 보여주던 문제 — LocalFileStorage가 저장 경로엔 UUID만 쓰지만(경로 추측 방지 목적),
-- 그 UUID화와는 별개로 사용자가 올린 원본 파일명 자체는 지금까지 어디에도 저장되지 않았다
-- (MediaService#storeAndBuild가 file.getOriginalFilename()을 아예 읽지 않음). 표시 전용 메타데이터라
-- 저장 경로 보안(원본 비공개 서빙 정책, PRD FR-2)과는 무관하다.
-- 이 마이그레이션 이전에 업로드된 기존 행은 NULL로 남는다(백필하지 않음 — V6/V13과 동일 이유) —
-- InspectionAnalysisWorker/InspectionAnalysisService는 NULL이면 기존과 같은 "이미지 N" 순번으로 폴백한다.
alter table media
    add column if not exists original_filename varchar(255);

comment on column media.original_filename is '업로드 시 클라이언트가 보낸 원본 파일명(표시 전용) — nullable, V25 이전 업로드 행은 NULL(조회 시 "이미지 N" 순번으로 폴백)';
