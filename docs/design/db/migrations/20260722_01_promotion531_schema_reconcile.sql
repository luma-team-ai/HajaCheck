-- 20260722_01 승격 #531 스키마 정합 (핫픽스 기록)
--
-- 계기: dev→main 승격 #531 배포 시 arm1(ddl-auto=validate)이 아래 누락 스키마로 boot 실패
--   (프로덕션 일시 다운 → 아래 DDL 수동 적용으로 복구, 2026-07-22).
-- 근본 원인: Flyway 부재 + 정규 증분 마이그레이션 없이 엔티티 스키마가 dev에 누적됨
--   (@Version lock_version 컬럼, RAG/멤버십 신규 스키마 등). → Flyway 도입(#359)으로 재발 차단 예정.
-- 정본: docs/design/db/HajaCheck_script.sql. 모든 문장 재실행 안전(IF NOT EXISTS/idempotent).
--
--   psql -X --set ON_ERROR_STOP=1 --dbname "$DATABASE_URL" \
--     --file docs/design/db/migrations/20260722_01_promotion531_schema_reconcile.sql

BEGIN;

-- 1) @Version 낙관적 락 컬럼 — 기존 행 있는 테이블이라 DEFAULT 0으로 채운 뒤 DROP DEFAULT(정본 정합).
ALTER TABLE companies       ADD COLUMN IF NOT EXISTS lock_version bigint NOT NULL DEFAULT 0;
ALTER TABLE companies       ALTER COLUMN lock_version DROP DEFAULT;
ALTER TABLE usage_counters  ADD COLUMN IF NOT EXISTS lock_version bigint NOT NULL DEFAULT 0;
ALTER TABLE usage_counters  ALTER COLUMN lock_version DROP DEFAULT;

-- 2) company_memberships — 테이블 통째 누락(기업 멤버십/초대). enum·인덱스·트리거 포함.
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'company_membership_status_type') THEN
    CREATE TYPE company_membership_status_type AS ENUM ('PENDING','APPROVED','REJECTED','REVOKED','EXPIRED');
  END IF;
END $$;

CREATE TABLE IF NOT EXISTS company_memberships (
    id           bigint generated always as identity primary key,
    lock_version bigint default 0 not null,
    company_id   bigint not null references companies,
    user_id      bigint not null references users,
    invited_by   bigint references users,
    status       company_membership_status_type default 'PENDING'::company_membership_status_type not null,
    approved_at  timestamp with time zone,
    expires_at   timestamp with time zone,
    revoked_at   timestamp with time zone,
    created_at   timestamp with time zone default now() not null,
    updated_at   timestamp with time zone default now() not null,
    unique (company_id, user_id),
    constraint ck_company_memberships_approved_at
        check (status <> 'APPROVED'::company_membership_status_type or approved_at is not null),
    constraint ck_company_memberships_revoked_at
        check (status <> 'REVOKED'::company_membership_status_type or revoked_at is not null),
    constraint ck_company_memberships_expiry
        check (expires_at is null or (expires_at > created_at and (approved_at is null or expires_at > approved_at)))
);

CREATE INDEX IF NOT EXISTS idx_company_memberships_company_status ON company_memberships (company_id, status);
CREATE INDEX IF NOT EXISTS idx_company_memberships_user_status    ON company_memberships (user_id, status);
CREATE UNIQUE INDEX IF NOT EXISTS uq_company_memberships_approved_user
    ON company_memberships (user_id) WHERE (status = 'APPROVED'::company_membership_status_type);

DROP TRIGGER IF EXISTS trg_company_memberships_set_updated_at ON company_memberships;
CREATE TRIGGER trg_company_memberships_set_updated_at
    BEFORE UPDATE ON company_memberships
    FOR EACH ROW EXECUTE PROCEDURE set_updated_at();

-- 3) rag_documents.verification_status — nullable enum(문서 검증 여부).
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'rag_doc_verification_status_type') THEN
    CREATE TYPE rag_doc_verification_status_type AS ENUM ('UNVERIFIED','VERIFIED');
  END IF;
END $$;

ALTER TABLE rag_documents ADD COLUMN IF NOT EXISTS verification_status rag_doc_verification_status_type;

COMMIT;

-- 남은 정합(비블로커, 별도): menus / menu_role_access 테이블(20260716_04_menu_schema_expand.sql)
-- — 현재 @Entity 미매핑이라 validate 무관하나, 스키마 완전 정합을 위해 Flyway 도입 시 흡수.
