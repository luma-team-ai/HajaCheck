-- Flyway V36 — Flyway 이전 수동 증분 SQL 중 prod에 적용되지 않은 누락분 반영 (#1311)
--
-- 왜 필요한가:
--   V1(baseline)은 캐노니컬 DDL(docs/design/db/HajaCheck_script.sql) 기준으로 만들어졌는데,
--   prod(arm1 전용 postgres, DB hajacheck)는 2026-07-22 baseline-on-migrate 로 V1을 **실행하지 않고
--   스탬프만** 했다. 그래서 V1에는 있지만 prod 실스키마에는 없는 객체들이 "적용됨" 상태로 묻혔다.
--   #1308(menus·menu_role_access 부재로 기동 실패, V35로 복구)이 같은 뿌리의 사고였다.
--
--   그 누락분의 출처는 Flyway 이전 수동 증분 SQL(docs/design/db/migrations/) 중
--   prod가 받은 적 없는 두 묶음이다:
--     · 20260716_01/02_ha25_expand/finalize.sql (HAJA-25) — 부분 UNIQUE 3종·FK·updated_at 트리거 3종
--     · 20260719_01_ap020_notification_history_index.sql (AP-020) — 알림 이력 조회 인덱스
--   추가로 V35에서 빠뜨린 idx_menus_parent 를 함께 보완한다.
--
-- 적용 방향 (2026-07-30 arm1 실측 기준):
--   이 파일의 모든 객체는 **신규 설치(V1~V35 전량 실행) 에는 이미 존재**하고 **prod 에만 없다**.
--   따라서 빈 DB·팀 로컬·공유 dev 에서는 전부 no-op 이고, prod 재기동 때만 실제로 생성된다.
--
-- 원본과의 차이 — CREATE INDEX CONCURRENTLY 를 쓰지 않는다:
--   Flyway 는 마이그레이션을 트랜잭션 안에서 실행하므로 CONCURRENTLY 가 불가하다.
--   원본이 CONCURRENTLY 를 쓴 이유는 운영 트래픽 중 대형 테이블 락 회피였는데, 2026-07-30 prod 실측으로
--   user_plans 8행 · counsel_tickets 0행 · notifications 0행 · menus 29행 이라 일반 CREATE INDEX 의
--   락 시간이 무의미하다. 아래 lock_timeout 으로 혹시 모를 장기 대기도 차단한다.
--
-- 데이터 전제 (prod·공유 dev 양쪽 실측, 2026-07-30):
--   ACTIVE user_plans 중복 0 · ACTIVE 회사 구독 중복 0 · counsel_tickets.session_id 중복 0
--   · chat_message_citations 0행(snippet/locator NULL 0) → 아래 UNIQUE·NOT NULL 이 데이터로 실패할 수 없다.
--
-- 이 파일에 **넣지 않은** 것과 그 이유:
--   ① check_inspection_assigned_inspector_company() + trg_inspections_check_assigned_inspector_company
--      (#604) — 이 트리거는 앱(AuthService.validateAssignableInspector)보다 **엄격**하다: 회사가
--      APPROVED **이면서** verification_status=VERIFIED 여야 통과하는데 앱은 회사 상태·진위확인을 보지
--      않는다. 진위확인은 fail-open(UNAVAILABLE→PENDING)이라 "APPROVED + PENDING" 조합이 실제로 생기고,
--      그 상태에서 앱은 점검 생성을 허용하는데 트리거가 거부해 **프로덕션 쓰기가 막힌다**.
--      (2026-07-30 prod 실측: companies 2건 모두 PENDING_REVIEW/PENDING, company_memberships 0행.)
--      앱↔DB 의미를 먼저 정렬한 뒤 별도 마이그레이션으로 반영한다 — #604 유지.
--   ② prod 에만 있는 레거시 중복 UNIQUE(uk_users_social 등 6종, V1 의 *_key 와 같은 컬럼 조합 중복)와
--      orphan 컬럼 usage_counters.lock_version(UsageCounter 엔티티는 의도적으로 @Version 을 두지 않는다),
--      소문자 orphan enum 23종 — 전부 pre-Flyway Hibernate 잔재다. 제거는 DROP 이라 destructive 이므로
--      사용처 0건 실측을 붙여 별도 마이그레이션으로 처리한다.
--   ③ rag_documents.target_collection 의 prod-only DEFAULT 'REGULATIONS' — 정본 방향 확정 후 별도.

-- 운영 트래픽을 무기한 대기시키지 않는다(V7 과 동일 방침).
set local lock_timeout = '5s';

-- ─────────────────────────────────────────────────────────────────────────────
-- (1) HAJA-25 finalize — 부분 UNIQUE 3종
--     출처: docs/design/db/migrations/20260716_02_ha25_finalize.sql
-- ─────────────────────────────────────────────────────────────────────────────
create unique index if not exists uq_user_plans_active_user
    on user_plans (user_id)
    where status = 'ACTIVE'::user_plan_status_type;

create unique index if not exists uq_user_plans_active_company
    on user_plans (company_id)
    where status = 'ACTIVE'::user_plan_status_type;

create unique index if not exists uq_counsel_tickets_session
    on counsel_tickets (session_id)
    where session_id is not null;

comment on index uq_user_plans_active_user is
    '동일 사용자에게 ACTIVE 구독이 둘 이상 존재하는 것을 방지한다(중복 과금·엔타이틀먼트 혼선 차단).';
comment on index uq_user_plans_active_company is
    '동일 회사에 ACTIVE 구독이 둘 이상 존재하는 것을 방지한다(중복 과금·엔타이틀먼트 혼선 차단).';
comment on index uq_counsel_tickets_session is
    '하나의 전문상담 세션이 여러 상담 티켓에 중복 배정되는 것을 방지한다.';

-- ─────────────────────────────────────────────────────────────────────────────
-- (2) HAJA-25 — inspections.assigned_inspector_id FK
--     출처: 20260716_01_ha25_expand.sql(NOT VALID 추가) + 02_finalize.sql(validate)
--     prod inspections 0행 실측이라 NOT VALID 2단계 없이 곧바로 validated FK 로 만든다.
--     회사 경계는 이 FK 로 강제되지 않는다(위 "넣지 않은 것 ①" 참조) — users(id) 참조 정합성만 보장.
-- ─────────────────────────────────────────────────────────────────────────────
do $$
begin
    if not exists (
        select 1 from pg_constraint
         where conname = 'fk_inspections_assigned_inspector'
           and conrelid = 'public.inspections'::regclass
    ) then
        alter table inspections
            add constraint fk_inspections_assigned_inspector
            foreign key (assigned_inspector_id) references users (id);
    end if;
end
$$;

-- ─────────────────────────────────────────────────────────────────────────────
-- (3) HAJA-25 expand — updated_at 자동 갱신 트리거 3종
--     출처: 20260716_01_ha25_expand.sql
--     company_memberships·plans·reports·bot_scenarios 트리거는 prod 에 이미 있어 대상이 아니다.
--     set_updated_at() 은 prod·V1 양쪽에 이미 존재한다.
-- ─────────────────────────────────────────────────────────────────────────────
do $$
begin
    if not exists (
        select 1 from pg_trigger
         where tgname = 'trg_users_set_updated_at'
           and tgrelid = 'public.users'::regclass and not tgisinternal
    ) then
        create trigger trg_users_set_updated_at before update on users
            for each row execute procedure set_updated_at();
    end if;

    if not exists (
        select 1 from pg_trigger
         where tgname = 'trg_companies_set_updated_at'
           and tgrelid = 'public.companies'::regclass and not tgisinternal
    ) then
        create trigger trg_companies_set_updated_at before update on companies
            for each row execute procedure set_updated_at();
    end if;

    if not exists (
        select 1 from pg_trigger
         where tgname = 'trg_facilities_set_updated_at'
           and tgrelid = 'public.facilities'::regclass and not tgisinternal
    ) then
        create trigger trg_facilities_set_updated_at before update on facilities
            for each row execute procedure set_updated_at();
    end if;
end
$$;

comment on trigger trg_users_set_updated_at on users is
    'users 행 수정 시 updated_at을 현재 시각으로 갱신한다.';
comment on trigger trg_companies_set_updated_at on companies is
    'companies 행 수정 시 updated_at을 현재 시각으로 갱신한다.';
comment on trigger trg_facilities_set_updated_at on facilities is
    'facilities 행 수정 시 updated_at을 현재 시각으로 갱신한다.';

-- ─────────────────────────────────────────────────────────────────────────────
-- (4) AP-020 — 알림 센터 목록 조회 인덱스
--     출처: docs/design/db/migrations/20260719_01_ap020_notification_history_index.sql
--     기존 idx_notifications_user_unread 는 is_read=false 부분 인덱스라 읽음/미읽음 전체를
--     최신순으로 읽는 30초 폴링 조회를 커버하지 못한다(매 폴링 seq scan + sort).
-- ─────────────────────────────────────────────────────────────────────────────
create index if not exists idx_notifications_user_history
    on notifications (user_id, created_at desc, id desc);

-- ─────────────────────────────────────────────────────────────────────────────
-- (5) V35 보완 — menus 부모 조회 인덱스
--     V1 에는 있으나 V35(#1308) 작성 시 누락했다.
-- ─────────────────────────────────────────────────────────────────────────────
create index if not exists idx_menus_parent
    on menus (parent_id);

-- ─────────────────────────────────────────────────────────────────────────────
-- (6) HAJA-25 finalize — chat_message_citations NOT NULL 확정
--     출처: 20260716_02_ha25_finalize.sql. prod 0행 실측이라 NOT VALID CHECK 2단계 없이 직접 확정한다.
--     locator 는 prod 도 이미 NOT NULL 이라 대상이 아니다(멱등하게 함께 선언해도 무해하므로 snippet 만 다룬다).
-- ─────────────────────────────────────────────────────────────────────────────
do $$
begin
    if exists (
        select 1 from pg_attribute
         where attrelid = 'public.chat_message_citations'::regclass
           and attname = 'snippet'
           and not attisdropped
           and not attnotnull
    ) then
        alter table chat_message_citations alter column snippet set not null;
    end if;
end
$$;

-- ─────────────────────────────────────────────────────────────────────────────
-- (7) lock_version DEFAULT 0 정합
--     V1 은 @Version 컬럼에 default 0 을 두지만 prod 는 default 없이 NOT NULL 만 있다.
--     Hibernate 가 항상 값을 채우므로 런타임 영향은 없고, 환경 간 스키마 동일성을 위한 정합이다.
--     (NOT NULL 자체는 prod 도 이미 만족한다 — 여기서는 DEFAULT 만 맞춘다.)
-- ─────────────────────────────────────────────────────────────────────────────
alter table companies       alter column lock_version set default 0;
alter table counsel_tickets alter column lock_version set default 0;
alter table defects         alter column lock_version set default 0;
alter table notifications   alter column lock_version set default 0;
alter table rag_documents   alter column lock_version set default 0;
alter table reports         alter column lock_version set default 0;
