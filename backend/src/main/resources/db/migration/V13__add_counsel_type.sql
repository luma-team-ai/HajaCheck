-- Flyway V13 — 상담 유형(counsel_type) 분류: counsel_tickets.counsel_type 컬럼 + 상담사가 처리
-- 가능한 상담 유형을 관리하는 counselor_skills 다대다 테이블(#743).
--
-- counsel_tickets는 아직 프로덕션 write 경로가 없어(엔티티만 존재, service/controller 미구현)
-- 백필 없이 NOT NULL로 추가한다. 캐노니컬 DDL(HajaCheck_script.sql)이 이미 최신 스키마를 포함하는
-- "기존 DB" 경로(FlywayBaselineOnExistingDbIntegrationTest)에서 no-op 성공하도록, 기존 V6/V10과
-- 동일한 IF NOT EXISTS/가드 컨벤션을 따른다. 상담사(users.role=COUNSELOR) 제약은 로컬/운영 role_type
-- 정합이 아직 안 맞아(Role.java 상단 주석) DB 레벨 CHECK/트리거 없이 서비스 레벨 검증으로만 둔다.
--
-- V12는 dev에 아직 미병합인 #725/#726 시설물 재설계 마이그레이션이 선점해 V13으로 재번호했다
-- (V6/V10과 동일한 재번호 컨벤션).

do $$
begin
    if not exists (
        select 1
          from pg_type t
          join pg_namespace n on n.oid = t.typnamespace
         where n.nspname = 'public'
           and t.typname = 'counsel_type'
    ) then
        create type public.counsel_type as enum ('USAGE', 'ANALYSIS_RESULT', 'BILLING_ETC');
    end if;
end
$$;

comment on type counsel_type is '상담 유형(이용 방법/분석 결과/결제·기타)';

alter table counsel_tickets
    add column if not exists counsel_type counsel_type not null;
comment on column counsel_tickets.counsel_type is '상담 유형';

create table if not exists counselor_skills
(
    counselor_id bigint       not null references users,
    counsel_type counsel_type not null,
    primary key (counselor_id, counsel_type)
);
comment on table counselor_skills is '상담사가 처리 가능한 상담 유형(다대다)';
comment on column counselor_skills.counselor_id is '상담사 사용자 식별자(users, role=COUNSELOR — DB 제약 아닌 서비스 레벨 검증)';
comment on column counselor_skills.counsel_type is '처리 가능한 상담 유형';

create index if not exists idx_counselor_skills_counsel_type
    on counselor_skills (counsel_type);
