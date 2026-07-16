-- HAJA-25 expand migration: 기존 v0.3 계열 DB에 신규 구조를 nullable/비차단 형태로 추가한다.
-- 이 단계 뒤 README의 데이터 백필을 완료하고 finalize 스크립트를 실행해야 한다.

begin;

select pg_advisory_xact_lock(hashtext('hajacheck:HAJA-25:schema-migration'));

do $migration$
begin
    if to_regclass('public.users') is null
       or to_regclass('public.companies') is null
       or to_regclass('public.inspections') is null
       or to_regclass('public.rag_documents') is null
       or to_regclass('public.chat_message_citations') is null then
        raise exception 'HAJA-25 migration requires the v0.3 baseline tables';
    end if;
end
$migration$;

do $migration$
begin
    if to_regtype('public.company_membership_status_type') is null then
        create type company_membership_status_type as enum
            ('PENDING', 'APPROVED', 'REJECTED', 'REVOKED', 'EXPIRED');
    end if;
    if to_regtype('public.rag_target_collection_type') is null then
        create type rag_target_collection_type as enum ('REGULATIONS', 'DEFECT_KB');
    end if;
    if to_regtype('public.rag_doc_verification_status_type') is null then
        create type rag_doc_verification_status_type as enum ('UNVERIFIED', 'VERIFIED');
    end if;
end
$migration$;

create table if not exists company_memberships
(
    id          bigint generated always as identity primary key,
    company_id  bigint not null references companies,
    user_id     bigint not null references users,
    invited_by  bigint references users,
    status      company_membership_status_type
        default 'PENDING'::company_membership_status_type not null,
    approved_at timestamp with time zone,
    expires_at  timestamp with time zone,
    revoked_at  timestamp with time zone,
    created_at  timestamp with time zone default now() not null,
    updated_at  timestamp with time zone default now() not null,
    constraint company_memberships_company_id_user_id_key unique (company_id, user_id),
    constraint ck_company_memberships_approved_at
        check (status <> 'APPROVED'::company_membership_status_type or approved_at is not null),
    constraint ck_company_memberships_revoked_at
        check (status <> 'REVOKED'::company_membership_status_type or revoked_at is not null),
    constraint ck_company_memberships_expiry
        check (expires_at is null or
               (expires_at > created_at and (approved_at is null or expires_at > approved_at)))
);

create index if not exists idx_company_memberships_company_status
    on company_memberships (company_id, status);
create index if not exists idx_company_memberships_user_status
    on company_memberships (user_id, status);
create unique index if not exists uq_company_memberships_approved_user
    on company_memberships (user_id)
    where status = 'APPROVED'::company_membership_status_type;

-- 기존 회사 포인터는 승인 근거가 아니므로 권한이 없는 PENDING 행으로만 보존한다.
insert into company_memberships (company_id, user_id, status)
select c.id, c.owner_user_id, 'PENDING'::company_membership_status_type
from companies c
on conflict (company_id, user_id) do nothing;

insert into company_memberships (company_id, user_id, status)
select u.company_id, u.id, 'PENDING'::company_membership_status_type
from users u
where u.company_id is not null
on conflict (company_id, user_id) do nothing;

alter table inspections
    add column if not exists assigned_inspector_id bigint;

do $migration$
begin
    if not exists (
        select 1 from pg_constraint
        where conname = 'fk_inspections_assigned_inspector'
          and conrelid = 'inspections'::regclass
    ) then
        alter table inspections
            add constraint fk_inspections_assigned_inspector
            foreign key (assigned_inspector_id) references users(id) not valid;
    end if;
end
$migration$;

create index if not exists idx_inspections_assigned_inspector
    on inspections (assigned_inspector_id);

alter table rag_documents
    add column if not exists target_collection rag_target_collection_type,
    add column if not exists effective_date date,
    add column if not exists publisher varchar(200),
    add column if not exists authored_at date,
    add column if not exists verification_status rag_doc_verification_status_type;

alter table chat_message_citations
    add column if not exists locator text;

-- v0.3에서는 snippet이 nullable이므로 finalize 전 원문을 복구할 수 있게 유지한다.

do $migration$
begin
    if to_regprocedure('public.set_updated_at()') is null then
        raise exception 'set_updated_at() from the v0.3 baseline is required';
    end if;

    if not exists (select 1 from pg_trigger where tgname = 'trg_users_set_updated_at'
                   and tgrelid = 'users'::regclass and not tgisinternal) then
        create trigger trg_users_set_updated_at before update on users
            for each row execute procedure set_updated_at();
    end if;
    if not exists (select 1 from pg_trigger where tgname = 'trg_companies_set_updated_at'
                   and tgrelid = 'companies'::regclass and not tgisinternal) then
        create trigger trg_companies_set_updated_at before update on companies
            for each row execute procedure set_updated_at();
    end if;
    if not exists (select 1 from pg_trigger where tgname = 'trg_company_memberships_set_updated_at'
                   and tgrelid = 'company_memberships'::regclass and not tgisinternal) then
        create trigger trg_company_memberships_set_updated_at before update on company_memberships
            for each row execute procedure set_updated_at();
    end if;
    if not exists (select 1 from pg_trigger where tgname = 'trg_facilities_set_updated_at'
                   and tgrelid = 'facilities'::regclass and not tgisinternal) then
        create trigger trg_facilities_set_updated_at before update on facilities
            for each row execute procedure set_updated_at();
    end if;
end
$migration$;

comment on column users.company_id is '현재 소속 기업의 조회 편의 포인터. 개인 사용자는 NULL이며 company_memberships의 유효한 승인 행과 일치해야 하지만 단독 권한 근거로 사용하지 않는다.';

comment on table company_memberships is '기업 초대·승인·회수·만료 이력과 현재 소속 판정의 기준을 관리한다.';
comment on column company_memberships.company_id is '소속 회사 식별자';
comment on column company_memberships.user_id is '소속 사용자 식별자';
comment on column company_memberships.invited_by is '초대한 회사 오너 또는 관리자 사용자 식별자. 오너의 최초 멤버십은 NULL 가능';
comment on column company_memberships.status is '기업 멤버십 상태';
comment on column company_memberships.approved_at is '멤버십 승인 시각';
comment on column company_memberships.expires_at is '멤버십 만료 시각. NULL이면 명시적 만료 없음';
comment on column company_memberships.revoked_at is '멤버십 회수 시각';
comment on column company_memberships.created_at is '멤버십 생성 시각';
comment on column company_memberships.updated_at is '멤버십 최종 상태 변경 시각';

comment on column user_consents.user_id is '동의한 사용자 식별자. 감사·분쟁 대응을 위해 동의 이력은 사용자 삭제와 독립적으로 보존해야 하나, 실측 결과 FK는 ON DELETE CASCADE이므로 보존은 DB 제약이 아닌 운영 원칙(사용자 탈퇴는 soft delete)으로 보장한다.';
comment on table user_plans is '개인(user_id) 또는 회사(company_id)에 적용된 구독 요금제와 이용 기간을 관리한다. 회사 귀속 행은 유효한 승인 company_memberships 사용자에게만 상속된다.';
comment on column inspections.assigned_inspector_id is '점검 담당자로 배정된 점검자 사용자 식별자. 본 스크립트는 신규 환경 전체 재생성용(파일 상단 주석 참조)이라 백필 로직을 담지 않는다 — 기존 데이터가 있는 환경에 증분 반영할 때는 NOT NULL 적용 전 담당자 확정값으로 먼저 백필해야 하며, 근거 없이 created_by를 자동 복사하지 않는다(백필 절차는 table_design.md §5 `inspections` 참조)';
comment on column media.source_video_id is '프레임 이미지의 원본 영상 식별자(media.id 자기 참조 개념이나 FK 미설정 — 영상 프레임 추출 파이프라인의 유연한 기록을 위함)';
comment on column rag_documents.target_collection is '이 문서의 청크가 임베딩되는 Chroma 컬렉션(regulations/defect_kb)';
comment on column rag_documents.effective_date is '문서 시행일(법규 개정 추적용, LAW 문서만 채움 — GUIDELINE/DEFECT_KB 문서는 NULL 허용)';
comment on column rag_documents.publisher is '발행 기관/부처명(법규·지침 문서 출처 표시용, regulations 대상 — 해당 없는 문서는 NULL)';
comment on column rag_documents.authored_at is '문서 작성일(주로 하자 지식 문서 대상 — effective_date와 별개 개념)';
comment on column rag_documents.verification_status is '문서 검증 여부(주로 defect_kb 하자 지식 문서의 전문가 검토 통과 여부 — regulations는 NULL 허용)';
comment on column chat_message_citations.locator is '화면 표시용 출처 라벨(예: 제12조, 제12조 ①, 12페이지)';

commit;
