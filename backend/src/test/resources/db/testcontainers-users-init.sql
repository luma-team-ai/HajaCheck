-- Testcontainers(postgres:16) 초기화 스크립트 — 서버 스키마(v0.3)의 users·facilities 부분 재현.
-- @JdbcTypeCode(NAMED_ENUM) 매핑을 실 PG named enum 에 대해 ddl-auto=validate 로 검증하기 위함.
-- 또한 같은 영속성 유닛의 모든 엔티티(Facility 포함)가 validate 대상이므로, 그 테이블도 여기에 있어야 한다.
-- 서버 스키마는 불변이므로 이 SQL 은 docs/design/db/HajaCheck_script_v0.3.sql 의 해당 정의와 동일해야 한다.

create type role_type as enum ('ADMIN', 'INSPECTOR', 'USER', 'COUNSELOR');
create type social_provider_type as enum ('KAKAO', 'GOOGLE');
create type user_status_type as enum ('ACTIVE', 'SUSPENDED');

create table users
(
    id                bigint generated always as identity
        primary key,
    email             varchar(255)                                                not null
        unique,
    name              varchar(100)                                                not null,
    role              role_type                default 'USER'::role_type          not null,
    social_provider   social_provider_type,
    social_id         varchar(255),
    password_hash     varchar(255),
    company_id        bigint,
    profile_image_url varchar(500),
    status            user_status_type         default 'ACTIVE'::user_status_type not null,
    last_login_at     timestamp with time zone,
    created_at        timestamp with time zone default now()                      not null,
    updated_at        timestamp with time zone default now()                      not null,
    unique (social_provider, social_id),
    constraint ck_users_auth_method
        check ((social_provider is not null and social_id is not null) or password_hash is not null)
);

-- <<< 병합: dev(기업 인증) + facility(시설물) 양쪽 테이블 모두 유지 >>>
-- 기업 인증(HAJA-168) — companies / user_consents 및 관련 enum. v0.3 DDL 과 정확히 동일해야 validate 통과.
create type company_status_type as enum ('PENDING_REVIEW', 'APPROVED', 'REJECTED');
create type business_verification_status_type as enum ('PENDING', 'VERIFIED', 'FAILED');
create type consent_policy_type as enum ('TERMS_OF_SERVICE', 'PRIVACY_POLICY');

create table companies
(
    id                              bigint generated always as identity
        primary key,
    owner_user_id                   bigint                                                                  not null
        references users,
    name                            varchar(200)                                                            not null,
    business_registration_number    varchar(20)                                                             not null
        unique,
    representative_name             varchar(100)                                                            not null,
    address                         varchar(300)                                                            not null,
    address_detail                  varchar(200),
    business_registration_file_url  varchar(500)                                                            not null,
    business_registration_ocr_raw   jsonb,
    verification_status             business_verification_status_type default 'PENDING'::business_verification_status_type not null,
    verified_at                     timestamp with time zone,
    status                          company_status_type               default 'PENDING_REVIEW'::company_status_type       not null,
    reviewed_by                     bigint
        references users,
    reviewed_at                     timestamp with time zone,
    rejection_reason                varchar(500),
    created_at                      timestamp with time zone          default now()                                      not null,
    updated_at                      timestamp with time zone          default now()                                      not null
);

create index idx_companies_owner
    on companies (owner_user_id);

alter table users
    add constraint fk_users_company
        foreign key (company_id) references companies;

create index idx_users_company
    on users (company_id);

create table user_consents
(
    id             bigint generated always as identity
        primary key,
    user_id        bigint                                 not null
        references users
            on delete cascade,
    policy_type    consent_policy_type                    not null,
    policy_version varchar(20)                             not null,
    agreed_at      timestamp with time zone default now() not null,
    unique (user_id, policy_type, policy_version)
);

create index idx_user_consents_user
    on user_consents (user_id);
-- facilities: 서버 스키마(v0.3)의 시설물 테이블. dev-04-01(시설물 CRUD) Facility 엔티티 validate 대조용.
-- enum 없이 type=varchar(20), is_deleted 없음(하드 삭제) — HajaCheck_script_v0.3.sql 과 동일.
create table facilities
(
    id                      bigint generated always as identity
        primary key,
    owner_id                bigint                                 not null
        references users,
    name                    varchar(200)                           not null,
    type                    varchar(20)                            not null,
    address                 varchar(300),
    latitude                numeric(9, 6),
    longitude               numeric(9, 6),
    built_year              integer,
    scale                   varchar(100),
    inspection_cycle_months integer,
    next_inspection_due_at  date,
    created_at              timestamp with time zone default now() not null,
    updated_at              timestamp with time zone default now() not null
);
