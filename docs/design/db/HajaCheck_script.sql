-- 신규 DB를 현재 최종 스키마로 생성하기 위한 설계 기준 DDL이다.
-- 운영 DB 증분 마이그레이션에는 직접 실행하지 말고 별도 버전 마이그레이션을 사용한다.

create type role_type as enum ('ADMIN', 'INSPECTOR', 'USER', 'COUNSELOR');

alter type role_type owner to postgres;

comment on type role_type is '사용자 권한 역할(관리자/검사자/일반 사용자/상담사)';

create type social_provider_type as enum ('KAKAO', 'GOOGLE');

alter type social_provider_type owner to postgres;

comment on type social_provider_type is '소셜 로그인 제공자';

create type user_status_type as enum ('ACTIVE', 'SUSPENDED');

alter type user_status_type owner to postgres;

comment on type user_status_type is '사용자 계정 상태(활성/정지)';

create type plan_name_type as enum ('FREE', 'STANDARD', 'ENTERPRISE');

alter type plan_name_type owner to postgres;

comment on type plan_name_type is '구독 요금제 명칭';

create type user_plan_status_type as enum ('ACTIVE', 'EXPIRED', 'UPGRADE_REQUESTED');

alter type user_plan_status_type owner to postgres;

comment on type user_plan_status_type is '사용자 구독 상태(이용중/만료/업그레이드 요청)';

create type inspection_status_type as enum ('CREATED', 'UPLOADING', 'ANALYZING', 'ANALYZED', 'REVIEWED', 'REPORTED');

alter type inspection_status_type owner to postgres;

comment on type inspection_status_type is '점검 처리 상태(생성/업로드중/분석중/분석완료/검토완료/보고서화)';

create type media_file_type as enum ('IMAGE', 'VIDEO');

alter type media_file_type owner to postgres;

comment on type media_file_type is '미디어 파일 유형(이미지/영상)';

create type defect_type as enum ('CRACK', 'SPALLING', 'LEAK_EFFLORESCENCE', 'REBAR_EXPOSURE', 'PAINT_DAMAGE');

alter type defect_type owner to postgres;

comment on type defect_type is '결함 유형(균열/박리·박락/누수·백태/철근노출/도장손상)';

create type defect_grade_type as enum ('A', 'B', 'C', 'D', 'E');

alter type defect_grade_type owner to postgres;

comment on type defect_grade_type is '결함 위험 또는 심각도 등급(A~E)';

create type defect_status_type as enum ('DETECTED', 'CONFIRMED', 'ACTION_PENDING', 'IN_PROGRESS', 'RESOLVED');

alter type defect_status_type owner to postgres;

comment on type defect_status_type is '결함 조치 상태(탐지됨/확인됨/조치대기/조치중/해결됨)';

create type report_status_type as enum ('DRAFT', 'FINALIZED');

alter type report_status_type owner to postgres;

comment on type report_status_type is '보고서 작성 상태(초안/확정)';

create type chat_session_type as enum ('RAG', 'SCENARIO_BOT', 'COUNSEL');

alter type chat_session_type owner to postgres;

comment on type chat_session_type is '채팅 세션 유형(RAG 문답/시나리오 봇/전문 상담)';

create type chat_sender_type as enum ('USER', 'BOT', 'COUNSELOR');

alter type chat_sender_type owner to postgres;

comment on type chat_sender_type is '채팅 메시지 발신자 유형(사용자/봇/상담사)';

create type counsel_ticket_status_type as enum ('WAITING', 'IN_PROGRESS', 'RESOLVED', 'OFFLINE_LEFT');

alter type counsel_ticket_status_type owner to postgres;

comment on type counsel_ticket_status_type is '상담 티켓 처리 상태(대기/진행중/해결/오프라인 이탈)';

create type rag_doc_source_type as enum ('LAW', 'GUIDELINE');

alter type rag_doc_source_type owner to postgres;

comment on type rag_doc_source_type is 'RAG 문서 출처 유형(법령/지침)';

create type rag_target_collection_type as enum ('REGULATIONS', 'DEFECT_KB');

alter type rag_target_collection_type owner to postgres;

comment on type rag_target_collection_type is 'RAG 문서가 임베딩되는 Chroma 컬렉션(법규·지침/하자 지식)';

create type rag_doc_verification_status_type as enum ('UNVERIFIED', 'VERIFIED');

alter type rag_doc_verification_status_type owner to postgres;

comment on type rag_doc_verification_status_type is 'RAG 문서(주로 defect_kb) 검증 여부';

create type rag_embedding_status_type as enum ('PENDING', 'EMBEDDING', 'DONE', 'FAILED');

alter type rag_embedding_status_type owner to postgres;

comment on type rag_embedding_status_type is 'RAG 문서 임베딩 처리 상태(대기/임베딩중/완료/실패)';

create type notification_type as enum ('ANALYSIS_DONE', 'REVIEW_PENDING', 'COUNSEL_REPLIED', 'INSPECTION_DUE');

alter type notification_type owner to postgres;

comment on type notification_type is '알림 유형(분석완료/검토대기/상담답변/점검예정)';

create type company_status_type as enum ('PENDING_REVIEW', 'APPROVED', 'REJECTED');

alter type company_status_type owner to postgres;

comment on type company_status_type is '기업 회원가입 관리자 승인 상태(승인대기/승인됨/반려됨)';

create type business_verification_status_type as enum ('PENDING', 'VERIFIED', 'FAILED');

alter type business_verification_status_type owner to postgres;

comment on type business_verification_status_type is '사업자등록번호 국세청 진위확인 상태(대기/확인됨/실패)';

create type company_membership_status_type as enum ('PENDING', 'APPROVED', 'REJECTED', 'REVOKED', 'EXPIRED');

alter type company_membership_status_type owner to postgres;

comment on type company_membership_status_type is '기업 초대 및 소속 승인 상태(대기/승인/반려/회수/만료)';

create type consent_policy_type as enum ('TERMS_OF_SERVICE', 'PRIVACY_POLICY');

alter type consent_policy_type owner to postgres;

comment on type consent_policy_type is '약관 동의 정책 유형(서비스 이용약관/개인정보 처리방침)';

create type menu_node_type as enum ('GROUP', 'INTERNAL', 'EXTERNAL');

alter type menu_node_type owner to postgres;

comment on type menu_node_type is '사이드바 메뉴 노드 유형(그룹/내부 링크/외부 링크)';

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

comment on table users is '서비스 사용자 계정과 인증 및 권한 정보를 관리한다.';

comment on column users.id is '사용자 식별자';

comment on column users.email is '사용자 이메일 주소이며 서비스 내에서 고유하다. 자체가입 사용자의 로그인 ID로도 사용된다.';

comment on column users.name is '사용자 이름 또는 표시명';

comment on column users.role is '사용자 권한 역할';

comment on column users.social_provider is '소셜 로그인 제공자(자체가입 사용자는 NULL)';

comment on column users.social_id is '소셜 로그인 제공자가 발급한 사용자 식별자(자체가입 사용자는 NULL)';

comment on column users.password_hash is '자체가입(이메일/비밀번호) 사용자의 비밀번호 해시(소셜 로그인 전용 사용자는 NULL)';

comment on column users.company_id is '현재 소속 기업의 조회 편의 포인터. 개인 사용자는 NULL이며 company_memberships의 유효한 승인 행과 일치해야 하지만 단독 권한 근거로 사용하지 않는다.';

comment on column users.profile_image_url is '사용자 프로필 이미지 URL';

comment on column users.status is '사용자 계정 상태';

comment on column users.last_login_at is '마지막 로그인 시각';

comment on column users.created_at is '사용자 계정 생성 시각';

comment on column users.updated_at is '사용자 계정 최종 수정 시각';

alter table users
    owner to postgres;

create table companies
(
    id                              bigint generated always as identity
        primary key,
    lock_version                    bigint                            default 0                                          not null,
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

comment on table companies is '기업 회원가입으로 생성된 회사(기업) 계정 정보를 관리한다.';

comment on column companies.id is '기업 계정 식별자';

comment on column companies.lock_version is '상태 전이 동시 갱신 충돌 감지용 낙관적 락 버전';

comment on column companies.owner_user_id is '기업 가입을 신청하고 계정을 소유·관리하는 사용자 식별자(플랜 보유자, 협업자 초대 권한)';

comment on column companies.name is '상호명';

comment on column companies.business_registration_number is '사업자등록번호';

comment on column companies.representative_name is '대표자명';

comment on column companies.address is '사업장 도로명주소';

comment on column companies.address_detail is '사업장 상세주소';

comment on column companies.business_registration_file_url is '업로드된 사업자등록증 원본 파일 URL';

comment on column companies.business_registration_ocr_raw is '사업자등록증 OCR 추출 원본 결과(감사·재처리용)';

comment on column companies.verification_status is '국세청 사업자등록정보 진위확인 상태';

comment on column companies.verified_at is '진위확인 완료 시각';

comment on column companies.status is '관리자의 기업 회원가입 승인 상태';

comment on column companies.reviewed_by is '가입 승인 또는 반려를 처리한 관리자 사용자 식별자';

comment on column companies.reviewed_at is '승인/반려 처리 시각';

comment on column companies.rejection_reason is '반려 사유';

comment on column companies.created_at is '기업 계정 생성(가입 신청) 시각';

comment on column companies.updated_at is '기업 계정 최종 수정 시각';

alter table companies
    owner to postgres;

create index idx_companies_owner
    on companies (owner_user_id);

alter table users
    add constraint fk_users_company
        foreign key (company_id) references companies;

create index idx_users_company
    on users (company_id);

create table company_memberships
(
    id          bigint generated always as identity
        primary key,
    lock_version bigint                            default 0                            not null,
    company_id  bigint                                                                     not null
        references companies,
    user_id     bigint                                                                     not null
        references users,
    invited_by  bigint
        references users,
    status      company_membership_status_type default 'PENDING'::company_membership_status_type not null,
    approved_at timestamp with time zone,
    expires_at  timestamp with time zone,
    revoked_at  timestamp with time zone,
    created_at  timestamp with time zone          default now()                            not null,
    updated_at  timestamp with time zone          default now()                            not null,
    unique (company_id, user_id),
    constraint ck_company_memberships_approved_at
        check (status <> 'APPROVED'::company_membership_status_type or approved_at is not null),
    constraint ck_company_memberships_revoked_at
        check (status <> 'REVOKED'::company_membership_status_type or revoked_at is not null),
    constraint ck_company_memberships_expiry
        check (expires_at is null or
               (expires_at > created_at and (approved_at is null or expires_at > approved_at)))
);

comment on table company_memberships is '기업 초대·승인·회수·만료 이력과 현재 소속 판정의 기준을 관리한다.';

comment on column company_memberships.lock_version is '상태 전이 동시 갱신 충돌 감지용 낙관적 락 버전';

comment on column company_memberships.company_id is '소속 회사 식별자';

comment on column company_memberships.user_id is '소속 사용자 식별자';

comment on column company_memberships.invited_by is '초대한 회사 오너 또는 관리자 사용자 식별자. 오너의 최초 멤버십은 NULL 가능';

comment on column company_memberships.status is '기업 멤버십 상태';

comment on column company_memberships.approved_at is '멤버십 승인 시각';

comment on column company_memberships.expires_at is '멤버십 만료 시각. NULL이면 명시적 만료 없음';

comment on column company_memberships.revoked_at is '멤버십 회수 시각';

comment on column company_memberships.created_at is '멤버십 생성 시각';

comment on column company_memberships.updated_at is '멤버십 최종 상태 변경 시각';

alter table company_memberships
    owner to postgres;

create index idx_company_memberships_company_status
    on company_memberships (company_id, status);

create index idx_company_memberships_user_status
    on company_memberships (user_id, status);

create unique index uq_company_memberships_approved_user
    on company_memberships (user_id)
    where (status = 'APPROVED'::company_membership_status_type);

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

comment on table user_consents is '사용자의 약관·개인정보 처리방침 버전별 동의 이력을 관리한다.';

comment on column user_consents.id is '동의 이력 식별자';

comment on column user_consents.user_id is '동의한 사용자 식별자. 감사·분쟁 대응을 위해 동의 이력은 사용자 삭제와 독립적으로 보존해야 하나, 실측 결과 FK는 ON DELETE CASCADE이므로 보존은 DB 제약이 아닌 운영 원칙(사용자 탈퇴는 soft delete)으로 보장한다.';

comment on column user_consents.policy_type is '동의한 정책 유형(이용약관/개인정보 처리방침)';

comment on column user_consents.policy_version is '동의한 정책 버전';

comment on column user_consents.agreed_at is '동의 시각';

alter table user_consents
    owner to postgres;

create index idx_user_consents_user
    on user_consents (user_id);

create table plans
(
    id                   bigint generated always as identity
        primary key,
    name                 plan_name_type                         not null
        unique,
    max_facilities       integer,
    max_monthly_analyses integer,
    max_seats            integer                  default 0     not null,
    has_pdf_watermark    boolean                  default false not null,
    has_counselor_access boolean                  default false not null,
    has_ai_addon         boolean                  default false not null,
    price_monthly        numeric(10, 2),
    created_at           timestamp with time zone default now() not null,
    updated_at           timestamp with time zone default now() not null
);

comment on table plans is '서비스에서 제공하는 구독 요금제와 이용 한도를 관리한다.';

comment on column plans.id is '요금제 식별자';

comment on column plans.name is '요금제 명칭';

comment on column plans.max_facilities is '요금제에서 등록 가능한 최대 시설 수';

comment on column plans.max_monthly_analyses is '월간 최대 분석 가능 횟수';

comment on column plans.max_seats is '요금제에서 허용하는 최대 사용자 좌석 수';

comment on column plans.has_pdf_watermark is '생성된 PDF에 워터마크를 표시하는지 여부';

comment on column plans.has_counselor_access is '전문 상담사 연결 기능 제공 여부';

comment on column plans.has_ai_addon is 'AI 부가 기능 제공 여부';

comment on column plans.price_monthly is '월 구독 가격';

comment on column plans.created_at is '요금제 생성 시각';

comment on column plans.updated_at is '요금제 최종 수정 시각';

alter table plans
    owner to postgres;

create table user_plans
(
    id         bigint generated always as identity
        primary key,
    user_id    bigint
        references users,
    company_id bigint
        references companies,
    plan_id    bigint                                                           not null
        references plans,
    status     user_plan_status_type    default 'ACTIVE'::user_plan_status_type not null,
    started_at timestamp with time zone default now()                           not null,
    ended_at   timestamp with time zone,
    constraint ck_user_plans_owner_xor
        check ((user_id is not null) <> (company_id is not null))
);

comment on table user_plans is '개인(user_id) 또는 회사(company_id)에 적용된 구독 요금제와 이용 기간을 관리한다. 회사 귀속 행은 유효한 승인 company_memberships 사용자에게만 상속된다.';

comment on column user_plans.id is '구독 식별자';

comment on column user_plans.user_id is '구독 주체가 개인일 때의 사용자 식별자(회사 귀속 행은 NULL)';

comment on column user_plans.company_id is '구독 주체가 회사일 때의 기업 계정 식별자(개인 귀속 행은 NULL)';

comment on column user_plans.plan_id is '적용된 요금제 식별자';

comment on column user_plans.status is '구독 상태';

comment on column user_plans.started_at is '구독 시작 시각';

comment on column user_plans.ended_at is '구독 종료 시각';

alter table user_plans
    owner to postgres;

create index idx_user_plans_user
    on user_plans (user_id);

create index idx_user_plans_company
    on user_plans (company_id);

create unique index uq_user_plans_active_user
    on user_plans (user_id)
    where (status = 'ACTIVE'::user_plan_status_type);

create unique index uq_user_plans_active_company
    on user_plans (company_id)
    where (status = 'ACTIVE'::user_plan_status_type);

comment on index uq_user_plans_active_user is '동일 사용자에게 ACTIVE 구독이 둘 이상 존재하는 것을 방지한다(중복 과금·엔타이틀먼트 혼선 차단).';

comment on index uq_user_plans_active_company is '동일 회사에 ACTIVE 구독이 둘 이상 존재하는 것을 방지한다(중복 과금·엔타이틀먼트 혼선 차단).';

create table usage_counters
(
    id                     bigint generated always as identity
        primary key,
    user_plan_id           bigint                                 not null
        references user_plans,
    period                 date                                   not null
        constraint ck_usage_counters_period_month_start
            check (period = (date_trunc('month'::text, (period)::timestamp with time zone))::date),
    analyzed_image_count   integer                  default 0     not null,
    facility_count         integer                  default 0     not null,
    analysis_request_count integer                  default 0     not null,
    seat_count             integer                  default 0     not null,
    counsel_ticket_count   integer                  default 0     not null,
    pdf_generation_count   integer                  default 0     not null,
    created_at             timestamp with time zone default now() not null,
    unique (user_plan_id, period),
    constraint ck_usage_counters_nonnegative
        check ((analyzed_image_count >= 0) AND (analysis_request_count >= 0) AND (facility_count >= 0) AND
               (seat_count >= 0) AND (counsel_ticket_count >= 0) AND (pdf_generation_count >= 0))
);

comment on table usage_counters is '구독(개인/회사)별 월간 기능 사용량을 집계한다.';

comment on column usage_counters.id is '사용량 집계 식별자';

comment on column usage_counters.user_plan_id is '구독 식별자(user_plans)';

comment on column usage_counters.period is '집계 기준 월이며 해당 월의 1일로 저장한다.';

comment on column usage_counters.analyzed_image_count is '해당 월에 분석한 이미지 수';

comment on column usage_counters.facility_count is '해당 월 또는 집계 시점의 등록 시설 수';

comment on column usage_counters.analysis_request_count is '해당 월의 분석 요청 수';

comment on column usage_counters.seat_count is '해당 월 또는 집계 시점의 사용 좌석 수';

comment on column usage_counters.counsel_ticket_count is '해당 월에 생성한 상담 티켓 수';

comment on column usage_counters.pdf_generation_count is '해당 월에 생성한 PDF 수';

comment on column usage_counters.created_at is '사용량 집계 레코드 생성 시각';

alter table usage_counters
    owner to postgres;

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

comment on table facilities is '사용자가 소유하거나 관리하는 점검 대상 시설 정보를 관리한다.';

comment on column facilities.id is '시설 식별자';

comment on column facilities.owner_id is '시설 소유자 또는 관리자 사용자 식별자';

comment on column facilities.name is '시설 명칭';

comment on column facilities.type is '시설 유형';

comment on column facilities.address is '시설 주소';

comment on column facilities.latitude is '시설 위치의 위도';

comment on column facilities.longitude is '시설 위치의 경도';

comment on column facilities.built_year is '시설 건축 연도';

comment on column facilities.scale is '시설 규모 설명';

comment on column facilities.inspection_cycle_months is '정기 점검 주기(개월)';

comment on column facilities.next_inspection_due_at is '다음 점검 예정일';

comment on column facilities.created_at is '시설 생성 시각';

comment on column facilities.updated_at is '시설 최종 수정 시각';

alter table facilities
    owner to postgres;

create index idx_facilities_owner
    on facilities (owner_id);

create table inspections
(
    id              bigint generated always as identity
        primary key,
    facility_id     bigint                                                             not null
        references facilities,
    created_by      bigint                                                             not null
        references users,
    assigned_inspector_id bigint                                                        not null
        constraint fk_inspections_assigned_inspector
        references users,
    round_no        integer                                                            not null,
    inspection_date date                                                               not null,
    status          inspection_status_type   default 'CREATED'::inspection_status_type not null,
    created_at      timestamp with time zone default now()                             not null,
    unique (facility_id, round_no)
);

comment on table inspections is '시설별 점검 회차와 진행 상태를 관리한다.';

comment on column inspections.id is '점검 식별자';

comment on column inspections.facility_id is '점검 대상 시설 식별자';

comment on column inspections.created_by is '점검 생성자 사용자 식별자';

comment on column inspections.assigned_inspector_id is '점검 담당자로 배정된 점검자 사용자 식별자. 본 스크립트는 신규 환경 전체 재생성용(파일 상단 주석 참조)이라 백필 로직을 담지 않는다 — 기존 데이터가 있는 환경에 증분 반영할 때는 NOT NULL 적용 전 담당자 확정값으로 먼저 백필해야 하며, 근거 없이 created_by를 자동 복사하지 않는다(백필 절차는 table_design.md §5 `inspections` 참조)';

comment on column inspections.round_no is '시설별 점검 회차';

comment on column inspections.inspection_date is '점검 수행일';

comment on column inspections.status is '점검 처리 상태';

comment on column inspections.created_at is '점검 생성 시각';

alter table inspections
    owner to postgres;

create index idx_inspections_facility
    on inspections (facility_id);

create index idx_inspections_assigned_inspector
    on inspections (assigned_inspector_id);

create table media
(
    id                      bigint generated always as identity
        primary key,
    inspection_id           bigint                                 not null
        references inspections,
    file_type               media_file_type                        not null,
    original_url            varchar(500)                           not null,
    thumbnail_url           varchar(500),
    source_video_id         bigint,
    frame_index             integer,
    captured_at             timestamp with time zone,
    gps_lat                 numeric(9, 6),
    gps_lng                 numeric(9, 6),
    mime_signature_verified boolean                  default false not null,
    created_at              timestamp with time zone default now() not null,
    mime_type               varchar(100)
);

comment on table media is '점검 과정에서 등록하거나 추출한 이미지 및 영상 정보를 관리한다.';

comment on column media.id is '미디어 식별자';

comment on column media.inspection_id is '미디어가 속한 점검 식별자';

comment on column media.file_type is '미디어 파일 유형';

comment on column media.original_url is '원본 미디어 파일 URL';

comment on column media.thumbnail_url is '미디어 썸네일 이미지 URL';

comment on column media.source_video_id is '프레임 이미지의 원본 영상 식별자(media.id 자기 참조 개념이나 FK 미설정 — 영상 프레임 추출 파이프라인의 유연한 기록을 위함)';

comment on column media.frame_index is '원본 영상 내 프레임 순번';

comment on column media.captured_at is '미디어 촬영 시각';

comment on column media.gps_lat is '미디어 촬영 위치의 위도';

comment on column media.gps_lng is '미디어 촬영 위치의 경도';

comment on column media.mime_signature_verified is '파일 시그니처와 MIME 타입의 일치 검증 여부';

comment on column media.created_at is '미디어 레코드 생성 시각';

comment on column media.mime_type is '미디어 MIME 타입(예: image/jpeg, video/mp4)';

alter table media
    owner to postgres;

create index idx_media_inspection
    on media (inspection_id);

create table defects
(
    id              bigint generated always as identity
        primary key,
    lock_version    bigint                   default 0                              not null,
    inspection_id   bigint                                                          not null
        references inspections,
    type            defect_type                                                     not null,
    bbox_x          double precision,
    bbox_y          double precision,
    bbox_w          double precision,
    bbox_h          double precision,
    confidence      double precision                                                not null,
    grade           defect_grade_type,
    status          defect_status_type       default 'DETECTED'::defect_status_type not null,
    is_reviewed     boolean                  default false                          not null,
    is_deleted      boolean                  default false                          not null,
    crack_width_mm  double precision,
    crack_length_mm double precision,
    created_at      timestamp with time zone default now()                          not null
);

comment on table defects is '점검 이미지에서 탐지되거나 검토된 시설 결함 정보를 관리한다.';

comment on column defects.id is '결함 식별자';

comment on column defects.lock_version is '상태 전이 동시 갱신 충돌 감지용 낙관적 락 버전';

comment on column defects.inspection_id is '결함이 발견된 점검 식별자';

comment on column defects.type is '결함 유형';

comment on column defects.bbox_x is '결함 바운딩 박스의 좌측 X 좌표';

comment on column defects.bbox_y is '결함 바운딩 박스의 상단 Y 좌표';

comment on column defects.bbox_w is '결함 바운딩 박스의 너비';

comment on column defects.bbox_h is '결함 바운딩 박스의 높이';

comment on column defects.confidence is 'AI 결함 탐지 신뢰도';

comment on column defects.grade is '결함 위험 또는 심각도 등급';

comment on column defects.status is '결함 조치 상태';

comment on column defects.is_reviewed is '사용자 또는 검사자가 결함을 검토했는지 여부';

comment on column defects.is_deleted is '결함의 논리 삭제 여부';

comment on column defects.crack_width_mm is '균열 폭(mm)';

comment on column defects.crack_length_mm is '균열 길이(mm)';

comment on column defects.created_at is '결함 생성 시각';

alter table defects
    owner to postgres;

create index idx_defects_inspection
    on defects (inspection_id);

create table defect_revisions
(
    id            bigint generated always as identity
        primary key,
    defect_id     bigint                                 not null
        references defects,
    revised_by    bigint                                 not null
        references users,
    field_changed varchar(50)                            not null,
    old_value     varchar(255),
    new_value     varchar(255),
    reason        varchar(500),
    created_at    timestamp with time zone default now() not null
);

comment on table defect_revisions is '결함 정보의 변경 이력과 변경 사유를 관리한다.';

comment on column defect_revisions.id is '결함 수정 이력 식별자';

comment on column defect_revisions.defect_id is '수정된 결함 식별자';

comment on column defect_revisions.revised_by is '수정자 사용자 식별자';

comment on column defect_revisions.field_changed is '변경된 컬럼 또는 항목명';

comment on column defect_revisions.old_value is '변경 전 값';

comment on column defect_revisions.new_value is '변경 후 값';

comment on column defect_revisions.reason is '변경 사유';

comment on column defect_revisions.created_at is '수정 이력 생성 시각';

alter table defect_revisions
    owner to postgres;

create index idx_defect_revisions_defect
    on defect_revisions (defect_id);

create table reports
(
    id                     bigint generated always as identity
        primary key,
    lock_version           bigint                   default 0                           not null,
    inspection_id          bigint                                                       not null
        references inspections,
    version                integer                  default 1                           not null,
    content_json           jsonb                                                        not null,
    grounding_check_passed boolean,
    grounding_warnings     jsonb,
    pdf_url                varchar(500),
    edited_by              bigint
        references users,
    status                 report_status_type       default 'DRAFT'::report_status_type not null,
    created_at             timestamp with time zone default now()                       not null,
    created_by             bigint
        constraint fk_reports_created_by
            references users,
    updated_at             timestamp with time zone default now()                       not null,
    unique (inspection_id, version)
);

comment on table reports is '점검 결과를 기반으로 생성한 버전별 보고서를 관리한다.';

comment on column reports.id is '보고서 식별자';

comment on column reports.lock_version is '보고서 업무 버전과 별개인 상태 전이 낙관적 락 버전';

comment on column reports.inspection_id is '보고서 대상 점검 식별자';

comment on column reports.version is '동일 점검 내 보고서 버전';

comment on column reports.content_json is '보고서 본문 및 구조화된 콘텐츠 JSON';

comment on column reports.grounding_check_passed is '보고서 내용의 근거 검증 통과 여부';

comment on column reports.grounding_warnings is '근거 검증 경고 목록 또는 상세 정보 JSON';

comment on column reports.pdf_url is '생성된 보고서 PDF 파일 URL';

comment on column reports.edited_by is '보고서 최종 수정자 사용자 식별자';

comment on column reports.status is '보고서 작성 및 확정 상태';

comment on column reports.created_at is '보고서 생성 시각';

comment on column reports.created_by is '보고서 최초 작성자 사용자 식별자';

comment on column reports.updated_at is '보고서 최종 수정 시각';

alter table reports
    owner to postgres;

create index idx_reports_created_by
    on reports (created_by);

create index idx_reports_edited_by
    on reports (edited_by);

create table chat_sessions
(
    id           bigint generated always as identity
        primary key,
    user_id      bigint                                 not null
        references users,
    session_type chat_session_type                      not null,
    started_at   timestamp with time zone default now() not null,
    ended_at     timestamp with time zone
);

comment on table chat_sessions is '사용자별 AI, 시나리오 봇 또는 상담 채팅 세션을 관리한다.';

comment on column chat_sessions.id is '채팅 세션 식별자';

comment on column chat_sessions.user_id is '채팅 세션을 시작한 사용자 식별자';

comment on column chat_sessions.session_type is '채팅 세션 유형';

comment on column chat_sessions.started_at is '채팅 세션 시작 시각';

comment on column chat_sessions.ended_at is '채팅 세션 종료 시각';

alter table chat_sessions
    owner to postgres;

create index idx_chat_sessions_user
    on chat_sessions (user_id);

create table chat_messages
(
    id          bigint generated always as identity
        primary key,
    session_id  bigint                                 not null
        references chat_sessions,
    sender      chat_sender_type                       not null,
    content     text                                   not null,
    scenario_id bigint,
    created_at  timestamp with time zone default now() not null
);

comment on table chat_messages is '채팅 세션에서 송수신된 메시지를 관리한다.';

comment on column chat_messages.id is '채팅 메시지 식별자';

comment on column chat_messages.session_id is '메시지가 속한 채팅 세션 식별자';

comment on column chat_messages.sender is '메시지 발신자 유형';

comment on column chat_messages.content is '채팅 메시지 내용';

comment on column chat_messages.scenario_id is '사용자가 선택한 봇 시나리오 노드 식별자(bot_scenarios, SCENARIO_BOT 세션에서 사용)';

comment on column chat_messages.created_at is '메시지 생성 시각';

alter table chat_messages
    owner to postgres;

create index idx_chat_messages_session
    on chat_messages (session_id);

create table counsel_tickets
(
    id             bigint generated always as identity
        primary key,
    lock_version   bigint                            default 0                                 not null,
    user_id        bigint                                                                   not null
        references users,
    counselor_id   bigint
        references users,
    session_id     bigint
        references chat_sessions,
    status         counsel_ticket_status_type default 'WAITING'::counsel_ticket_status_type not null,
    queue_position integer,
    created_at     timestamp with time zone   default now()                                 not null,
    ended_at       timestamp with time zone
);

comment on table counsel_tickets is '사용자의 전문 상담 요청과 상담 진행 상태를 관리한다.';

comment on column counsel_tickets.id is '상담 티켓 식별자';

comment on column counsel_tickets.lock_version is '상태 전이 동시 갱신 충돌 감지용 낙관적 락 버전';

comment on column counsel_tickets.user_id is '상담을 요청한 사용자 식별자';

comment on column counsel_tickets.counselor_id is '배정된 상담사 사용자 식별자';

comment on column counsel_tickets.session_id is '상담 대화가 이루어지는 채팅 세션 식별자(chat_sessions, session_type=COUNSEL)';

comment on column counsel_tickets.status is '상담 티켓 처리 상태';

comment on column counsel_tickets.queue_position is '상담 대기열 순번';

comment on column counsel_tickets.created_at is '상담 티켓 생성 시각';

comment on column counsel_tickets.ended_at is '상담 종료 시각';

alter table counsel_tickets
    owner to postgres;

create index idx_counsel_tickets_counselor
    on counsel_tickets (counselor_id);

create index idx_counsel_tickets_user
    on counsel_tickets (user_id);

create index idx_counsel_tickets_session
    on counsel_tickets (session_id);

create unique index uq_counsel_tickets_session
    on counsel_tickets (session_id)
    where (session_id is not null);

comment on index uq_counsel_tickets_session is
    '하나의 전문상담 세션이 여러 상담 티켓에 중복 배정되는 것을 방지한다.';

create table bot_scenarios
(
    id                 bigint generated always as identity
        primary key,
    parent_id          bigint
        constraint fk_bot_scenarios_parent
            references bot_scenarios
            on delete set null,
    category           varchar(100)                           not null,
    button_label       varchar(200)                           not null,
    response_text      text,
    leads_to_counselor boolean                  default false not null,
    sort_order         integer                  default 0     not null,
    created_at         timestamp with time zone default now() not null,
    updated_at         timestamp with time zone default now() not null,
    constraint ck_bot_scenarios_not_self_parent
        check ((parent_id IS NULL) OR (parent_id <> id))
);

comment on table bot_scenarios is '버튼 선택 방식의 계층형 챗봇 시나리오를 관리한다.';

comment on column bot_scenarios.id is '봇 시나리오 식별자';

comment on column bot_scenarios.parent_id is '상위 봇 시나리오 식별자';

comment on column bot_scenarios.category is '시나리오 분류';

comment on column bot_scenarios.button_label is '사용자에게 표시할 선택 버튼 문구';

comment on column bot_scenarios.response_text is '선택 시 제공할 봇 응답 내용';

comment on column bot_scenarios.leads_to_counselor is '전문 상담사 연결 단계인지 여부';

comment on column bot_scenarios.sort_order is '동일 단계 내 노출 순서';

comment on column bot_scenarios.created_at is '봇 시나리오 생성 시각';

comment on column bot_scenarios.updated_at is '봇 시나리오 최종 수정 시각';

alter table bot_scenarios
    owner to postgres;

create index idx_bot_scenarios_parent
    on bot_scenarios (parent_id);

alter table chat_messages
    add constraint fk_chat_messages_scenario
        foreign key (scenario_id) references bot_scenarios;

create index idx_chat_messages_scenario
    on chat_messages (scenario_id);

create table rag_documents
(
    id                 bigint generated always as identity
        primary key,
    lock_version       bigint                            default 0                                not null,
    title              varchar(300)                                                           not null,
    source_type        rag_doc_source_type                                                    not null,
    target_collection  rag_target_collection_type                                            not null,
    effective_date     date,
    publisher          varchar(200),
    authored_at        date,
    verification_status rag_doc_verification_status_type,
    file_url           varchar(500)                                                           not null,
    embedding_status   rag_embedding_status_type default 'PENDING'::rag_embedding_status_type not null,
    chunk_count        integer,
    embedded_at        timestamp with time zone,
    created_at         timestamp with time zone  default now()                                not null
);

comment on table rag_documents is '검색 증강 생성에 사용하는 법령 및 지침 문서를 관리한다.';

comment on column rag_documents.id is 'RAG 문서 식별자';

comment on column rag_documents.lock_version is '임베딩·검증 상태 전이 동시 갱신 충돌 감지용 낙관적 락 버전';

comment on column rag_documents.title is '문서 제목';

comment on column rag_documents.source_type is '문서 출처 유형';

comment on column rag_documents.target_collection is '이 문서의 청크가 임베딩되는 Chroma 컬렉션(regulations/defect_kb)';

comment on column rag_documents.effective_date is '문서 시행일(법규 개정 추적용, LAW 문서만 채움 — GUIDELINE/DEFECT_KB 문서는 NULL 허용)';

comment on column rag_documents.publisher is '발행 기관/부처명(법규·지침 문서 출처 표시용, regulations 대상 — 해당 없는 문서는 NULL)';

comment on column rag_documents.authored_at is '문서 작성일(주로 하자 지식 문서 대상 — effective_date와 별개 개념)';

comment on column rag_documents.verification_status is '문서 검증 여부(주로 defect_kb 하자 지식 문서의 전문가 검토 통과 여부 — regulations는 NULL 허용)';

comment on column rag_documents.file_url is '원본 문서 파일 URL';

comment on column rag_documents.embedding_status is '문서 임베딩 처리 상태';

comment on column rag_documents.chunk_count is '문서를 분할하여 Chroma에 임베딩한 청크 수';

comment on column rag_documents.embedded_at is '임베딩 완료 시각';

comment on column rag_documents.created_at is '문서 업로드 시작 시각';

alter table rag_documents
    owner to postgres;

create index idx_rag_documents_embedding_status
    on rag_documents (embedding_status);

create index idx_rag_documents_target_collection
    on rag_documents (target_collection);

create table chat_message_citations
(
    id          bigint generated always as identity
        primary key,
    message_id  bigint                                 not null
        references chat_messages
            on delete cascade,
    document_id bigint                                 not null
        references rag_documents,
    chunk_ref   varchar(100)                           not null,
    locator     text                                   not null,
    snippet     text                                   not null,
    created_at  timestamp with time zone default now() not null,
    unique (message_id, document_id, chunk_ref)
);

comment on table chat_message_citations is 'RAG 답변 메시지가 인용한 근거 문서·청크를 관리한다. 실제 임베딩 벡터와 청크 본문은 Chroma(FastAPI 임베디드)에 있으므로, 여기서는 어떤 문서의 어떤 청크를 인용했는지에 대한 참조 정보만 보관한다.';

comment on column chat_message_citations.id is '인용 식별자';

comment on column chat_message_citations.message_id is '인용을 포함한 채팅 메시지 식별자';

comment on column chat_message_citations.document_id is '인용된 RAG 문서 식별자(rag_documents)';

comment on column chat_message_citations.chunk_ref is 'Chroma에 저장된 청크(벡터)의 식별자 — Postgres 외부 저장소 참조이므로 FK 불가';

comment on column chat_message_citations.locator is '화면 표시용 출처 라벨(예: 제12조, 제12조 ①, 12페이지)';

comment on column chat_message_citations.snippet is '인용된 청크 원문 발췌(표시용 캐시, Chroma 재조회 없이 UI에 노출하기 위함)';

comment on column chat_message_citations.created_at is '인용 레코드 생성 시각';

alter table chat_message_citations
    owner to postgres;

create index idx_chat_message_citations_message
    on chat_message_citations (message_id);

create index idx_chat_message_citations_document
    on chat_message_citations (document_id);

create table notifications
(
    id           bigint generated always as identity
        primary key,
    lock_version bigint                   default 0    not null,
    user_id      bigint                                 not null
        references users,
    type         notification_type                      not null,
    payload_json jsonb,
    is_read      boolean                  default false not null,
    created_at   timestamp with time zone default now() not null
);

comment on table notifications is '사용자에게 전달되는 서비스 알림을 관리한다.';

comment on column notifications.id is '알림 식별자';

comment on column notifications.lock_version is '동시 갱신 충돌 감지용 낙관적 락 버전(상태 머신은 아니나 다른 가변 Entity와의 일관성을 위해 적용)';

comment on column notifications.user_id is '알림 수신 사용자 식별자';

comment on column notifications.type is '알림 유형';

comment on column notifications.payload_json is '알림 표시 및 이동에 필요한 부가 데이터 JSON';

comment on column notifications.is_read is '알림 읽음 여부';

comment on column notifications.created_at is '알림 생성 시각';

alter table notifications
    owner to postgres;

create index idx_notifications_user_unread
    on notifications (user_id)
    where (is_read = false);

-- AP-020(#25 / HAJA-38) 알림 센터 목록 조회: 읽음/미읽음 전체를 user_id로 좁힌 뒤 생성일 최신순(동률 시
-- id desc)으로 정렬해 상위 N건만 뽑는다. 위 partial 인덱스는 is_read=false 행만 커버해 이 전체 이력
-- 조회(폴링마다 실행)는 seq scan+sort로 빠진다 — 정렬 컬럼까지 포함한 일반 인덱스로 별도 커버한다.
create index idx_notifications_user_history
    on notifications (user_id, created_at desc, id desc);

create table menus
(
    id                  bigint generated always as identity
        primary key,
    code                varchar(100)                            not null
        unique,
    name                varchar(100)                            not null,
    menu_type           menu_node_type                          not null,
    parent_id           bigint
        constraint fk_menus_parent
            references menus
            on delete restrict,
    path                varchar(500),
    active_path_pattern varchar(500),
    icon_key            varchar(100),
    icon_url            varchar(500),
    sort_order          integer                  default 0     not null,
    is_visible          boolean                  default true  not null,
    is_enabled          boolean                  default true  not null,
    opens_new_tab       boolean                  default false not null,
    description         varchar(500),
    created_by          bigint
        references users,
    updated_by          bigint
        references users,
    created_at          timestamp with time zone default now() not null,
    updated_at          timestamp with time zone default now() not null,
    constraint ck_menus_not_self_parent
        check ((parent_id IS NULL) OR (parent_id <> id)),
    constraint ck_menus_sort_order_nonnegative
        check (sort_order >= 0),
    constraint ck_menus_icon_single
        check (
            (menu_type = 'GROUP'::menu_node_type AND num_nonnulls(icon_key, icon_url) <= 1)
            OR (menu_type <> 'GROUP'::menu_node_type AND num_nonnulls(icon_key, icon_url) = 1)
        ),
    constraint ck_menus_path_by_type
        check (
            (menu_type = 'GROUP'::menu_node_type AND path IS NULL)
            OR (menu_type <> 'GROUP'::menu_node_type AND path IS NOT NULL)
        )
);

comment on table menus is '사이드바 및 관리자 메뉴 트리를 관리한다. lock_version을 두지 않는다 — 소수 관리자가 드물게 편집하는 설정 테이블이라 동시 갱신 충돌 위험이 낮다. 필요해지면 companies/reports처럼 후속으로 추가한다.';

comment on column menus.id is '메뉴 식별자';

comment on column menus.code is '변경되지 않는 고유 메뉴 코드(예: DASHBOARD, ADMIN_USERS)';

comment on column menus.name is '표시 메뉴명';

comment on column menus.menu_type is '메뉴 노드 유형(그룹/내부 링크/외부 링크)';

comment on column menus.parent_id is '상위 메뉴 식별자. 자기참조이며 하위 메뉴가 있는 상위 메뉴는 삭제할 수 없다(ON DELETE RESTRICT)';

comment on column menus.path is '이동 경로. GROUP은 NULL, INTERNAL/EXTERNAL은 필수. 같은 라우트를 가리키는 여러 메뉴 항목을 허용하므로 UNIQUE로 두지 않는다';

comment on column menus.active_path_pattern is '실제 라우트가 path와 다를 때(예: 메뉴 path=/facilities/list, 실제 라우트=/facilities, 상세 라우트=/defects/:id) 활성 메뉴 판정에 쓰는 동적 경로 패턴';

comment on column menus.icon_key is '프론트 번들 아이콘 키(예: dashboard, facilities). 현재 프론트가 SVG를 번들 import하는 방식이라 icon_url보다 우선 사용한다';

comment on column menus.icon_url is 'CDN 아이콘을 쓸 때만 채우는 URL. icon_key와 동시에 채우지 않는다';

comment on column menus.sort_order is '동일 부모 하위 노출 순서. 정렬은 sort_order, id 순';

comment on column menus.is_visible is '메뉴 표시 여부';

comment on column menus.is_enabled is '클릭 가능 여부(아직 미구현된 메뉴 등을 표시는 하되 비활성화할 때 사용)';

comment on column menus.opens_new_tab is '외부 링크를 새 창으로 여는지 여부';

comment on column menus.description is '관리자용 메뉴 설명';

comment on column menus.created_by is '메뉴를 생성한 관리자 사용자 식별자. 초기 시드 데이터는 NULL 허용';

comment on column menus.updated_by is '메뉴를 마지막으로 수정한 관리자 사용자 식별자';

comment on column menus.created_at is '메뉴 생성 시각';

comment on column menus.updated_at is '메뉴 최종 수정 시각';

alter table menus
    owner to postgres;

create index idx_menus_parent
    on menus (parent_id);

create table menu_role_access
(
    menu_id    bigint                                 not null
        references menus
            on delete cascade,
    role       role_type                              not null,
    created_by bigint
        references users,
    created_at timestamp with time zone default now() not null,
    primary key (menu_id, role)
);

comment on table menu_role_access is '역할별로 노출되는 메뉴를 관리한다. 매핑 행이 존재하면 해당 역할에 노출되는 방식이라 can_view 컬럼은 두지 않는다. GROUP 메뉴에는 매핑 행을 넣지 않는다 — 허용된 자식이 하나라도 있으면 부모 GROUP은 서비스 로직이 자동으로 포함시킨다';

comment on column menu_role_access.menu_id is '메뉴 식별자. 메뉴 삭제 시 매핑도 함께 삭제된다(ON DELETE CASCADE)';

comment on column menu_role_access.role is '이 메뉴에 접근 가능한 역할';

comment on column menu_role_access.created_by is '매핑을 등록한 관리자 사용자 식별자';

comment on column menu_role_access.created_at is '매핑 등록 시각';

alter table menu_role_access
    owner to postgres;

create index idx_menu_role_access_role
    on menu_role_access (role, menu_id);

create function set_updated_at() returns trigger
    language plpgsql
as
$$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$;

alter function set_updated_at() owner to postgres;

comment on function set_updated_at() is '행 갱신 시 updated_at 컬럼을 현재 시각으로 자동 설정하는 트리거 함수';

create trigger trg_users_set_updated_at
    before update
    on users
    for each row
execute procedure set_updated_at();

comment on trigger trg_users_set_updated_at on users is 'users 행 수정 시 updated_at을 현재 시각으로 갱신한다.';

create trigger trg_companies_set_updated_at
    before update
    on companies
    for each row
execute procedure set_updated_at();

comment on trigger trg_companies_set_updated_at on companies is 'companies 행 수정 시 updated_at을 현재 시각으로 갱신한다.';

create trigger trg_company_memberships_set_updated_at
    before update
    on company_memberships
    for each row
execute procedure set_updated_at();

comment on trigger trg_company_memberships_set_updated_at on company_memberships is 'company_memberships 행 수정 시 updated_at을 현재 시각으로 갱신한다.';

create trigger trg_plans_set_updated_at
    before update
    on plans
    for each row
execute procedure set_updated_at();

comment on trigger trg_plans_set_updated_at on plans is 'plans 행 수정 시 updated_at을 현재 시각으로 갱신한다.';

-- 구독 요금제 시드(#517 / HAJA-308) — PRD §2.4(v0.44 확정) 요금제 표. 신규 설치 전용이며,
-- 기존 운영 DB는 대신 docs/design/db/migrations/20260721_01_plans_seed_free_assign.sql 을 사용한다.
-- max_seats 는 NOT NULL 컬럼이라 Enterprise "무제한"은 sentinel 1000000 으로 표현한다.
insert into plans (name, max_facilities, max_monthly_analyses, max_seats,
                   has_pdf_watermark, has_counselor_access, has_ai_addon, price_monthly)
values
    ('FREE'::plan_name_type, 1, 50, 1, true, false, false, 0.00),
    ('STANDARD'::plan_name_type, 10, 1000, 3, false, true, true, 29000.00),
    ('ENTERPRISE'::plan_name_type, null, null, 1000000, false, true, true, 59000.00)
on conflict (name) do nothing;

create trigger trg_facilities_set_updated_at
    before update
    on facilities
    for each row
execute procedure set_updated_at();

comment on trigger trg_facilities_set_updated_at on facilities is 'facilities 행 수정 시 updated_at을 현재 시각으로 갱신한다.';

create trigger trg_reports_set_updated_at
    before update
    on reports
    for each row
execute procedure set_updated_at();

comment on trigger trg_reports_set_updated_at on reports is 'reports 행 수정 시 updated_at을 현재 시각으로 갱신한다.';

create trigger trg_bot_scenarios_set_updated_at
    before update
    on bot_scenarios
    for each row
execute procedure set_updated_at();

comment on trigger trg_bot_scenarios_set_updated_at on bot_scenarios is 'bot_scenarios 행 수정 시 updated_at을 현재 시각으로 갱신한다.';

create function check_inspection_assigned_inspector_company() returns trigger
    language plpgsql
as
$$
declare
    assignment_company_id bigint;
begin
    select c.id into assignment_company_id
    from users creator
    join users inspector on inspector.id = new.assigned_inspector_id
    join companies c
      on c.id = creator.company_id
     and c.id = inspector.company_id
    join company_memberships creator_membership
      on creator_membership.company_id = c.id
     and creator_membership.user_id = creator.id
    join company_memberships inspector_membership
      on inspector_membership.company_id = c.id
     and inspector_membership.user_id = inspector.id
    where creator.id = new.created_by
      and creator.status = 'ACTIVE'::user_status_type
      and inspector.status = 'ACTIVE'::user_status_type
      and inspector.role in ('INSPECTOR'::role_type, 'ADMIN'::role_type)
      and c.status = 'APPROVED'::company_status_type
      and c.verification_status = 'VERIFIED'::business_verification_status_type
      and creator_membership.status = 'APPROVED'::company_membership_status_type
      and creator_membership.approved_at is not null
      and creator_membership.revoked_at is null
      and (creator_membership.expires_at is null or creator_membership.expires_at > now())
      and inspector_membership.status = 'APPROVED'::company_membership_status_type
      and inspector_membership.approved_at is not null
      and inspector_membership.revoked_at is null
      and (inspector_membership.expires_at is null or inspector_membership.expires_at > now())
    limit 1;

    if assignment_company_id is null then
        raise exception
            'assigned_inspector_id % must be an active inspector with an effective membership in the approved company of created_by %',
            new.assigned_inspector_id, new.created_by;
    end if;
    return new;
end;
$$;

alter function check_inspection_assigned_inspector_company() owner to postgres;

comment on function check_inspection_assigned_inspector_company() is 'AuthService.validateAssignableInspector와 동일하게 활성 사용자, 담당자 역할, APPROVED+VERIFIED 회사, 양쪽 유효 멤버십과 company_id 일치를 강제한다(HAJA-25 P2 DB 레벨 방어).';

create trigger trg_inspections_check_assigned_inspector_company
    before insert or update of assigned_inspector_id, created_by
    on inspections
    for each row
execute procedure check_inspection_assigned_inspector_company();

comment on trigger trg_inspections_check_assigned_inspector_company on inspections is 'assigned_inspector_id 배정 시 애플리케이션과 동일한 담당자·회사·멤버십 인가 불변식을 강제한다(HAJA-25 P2 — DB 레벨 방어).';

create trigger trg_menus_set_updated_at
    before update
    on menus
    for each row
execute procedure set_updated_at();

comment on trigger trg_menus_set_updated_at on menus is 'menus 행 수정 시 updated_at을 현재 시각으로 갱신한다.';

create function check_menu_role_access_not_group() returns trigger
    language plpgsql
as
$$
declare
    target_menu_type menu_node_type;
begin
    select menu_type into target_menu_type
    from menus
    where id = new.menu_id;

    if target_menu_type = 'GROUP'::menu_node_type then
        raise exception
            'menu_role_access.menu_id % refers to a GROUP menu; GROUP menus must not have direct menu_role_access rows',
            new.menu_id;
    end if;
    return new;
end;
$$;

alter function check_menu_role_access_not_group() owner to postgres;

comment on function check_menu_role_access_not_group() is 'GROUP 메뉴는 허용된 자식이 있으면 서비스 로직이 자동으로 노출시키므로 menu_role_access에 직접 매핑을 추가할 수 없도록 강제한다(메뉴 스키마 DB 레벨 방어).';

create trigger trg_menu_role_access_reject_group
    before insert or update of menu_id
    on menu_role_access
    for each row
execute procedure check_menu_role_access_not_group();

comment on trigger trg_menu_role_access_reject_group on menu_role_access is 'menu_id가 GROUP 타입 메뉴를 가리키면 매핑 삽입/변경을 거부한다.';
