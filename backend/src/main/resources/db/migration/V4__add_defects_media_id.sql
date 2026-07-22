-- Flyway V4 — defects.media_id 컬럼 + 인덱스 추가(#527/HAJA-314 forward migration).
--
-- defects는 bbox_x/y/w/h로 결함 위치를 표시하지만, 그 좌표가 어느 촬영 이미지(media) 위의 것인지
-- 연결할 컬럼이 없어 하자 상세 화면에 실제 사진을 띄울 방법이 없었다. AI 탐지 파이프라인이 아직
-- 프로덕션 코드에 없어(신규 Defect 생성 경로가 없음) 기존 행은 전부 NULL로 남고, 이 마이그레이션은
-- 값을 백필하지 않는다. NOT NULL 강제는 하지 않는다.
--
-- ⚠️ CREATE INDEX CONCURRENTLY를 쓰지 않는다 — 처음에는 defects가 이미 데이터가 있는 테이블이라
-- CONCURRENTLY로 시도했지만, (1) Flyway가 기본(mixed=false)으로 한 파일 안의 트랜잭션 문장(ALTER
-- TABLE)과 비트랜잭션 문장(CONCURRENTLY)을 섞는 것을 거부해 파일을 둘로 쪼개야 했고, (2) 그렇게
-- 분리해도 Testcontainers 기반 통합 테스트에서 CONCURRENTLY 인덱스 빌드가 실제로 멈춰(14분 이상 응답
-- 없음, PostgreSQL 컨테이너가 살아있는 채로 행) 재현 가능한 행 위험을 확인했다. 기존 V1~V3 Flyway
-- 마이그레이션도 CONCURRENTLY를 쓰지 않는 동일한 이유(Flyway 트랜잭션 관리와의 충돌)로 보인다 —
-- 이 컬럼은 nullable bigint 단일 컬럼이라 인덱스 빌드 자체는 짧아, 일반 CREATE INDEX의 짧은 쓰기 잠금을
-- 감수하는 편이 Flyway 마이그레이션 도중 멈추는 위험보다 낫다고 판단했다.
alter table defects
    add column if not exists media_id bigint references media;

comment on column defects.media_id is '결함이 탐지된 촬영 이미지 식별자(HAJA-314, nullable — AI 탐지 파이프라인 도입 전 기존 행은 NULL)';

create index if not exists idx_defects_media
    on defects (media_id);
