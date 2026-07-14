-- Testcontainers(postgres:16) 초기화 스크립트 — 서버 스키마(v0.3)의 users 관련 부분만 재현.
-- @JdbcTypeCode(NAMED_ENUM) 매핑을 실 PG named enum 에 대해 ddl-auto=validate 로 검증하기 위함.
-- 서버 스키마는 불변이므로 이 SQL 은 docs/design/db/HajaCheck_script_v0.3.sql 의 users 정의와 동일해야 한다.

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
