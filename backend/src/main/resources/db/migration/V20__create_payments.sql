-- Flyway V20 — 토스페이먼츠 샌드박스 결제 원장(payments) 신설(#988 / HAJA-489).
--
-- 배경: 지금까지 플랜 업그레이드는 모의 결제(MembershipService#checkout)라 카드 청구도 결제 레코드도
-- 없었다. 그래서 결제 내역 화면이 항상 비어 있었고(#864), 소유자가 무결제로 플랜을 왕복해 월 분석
-- 한도를 리셋할 수 있었다(#851). 이 테이블이 "주문 사전 등록(READY) → 승인(PAID) → 플랜 전이"의 원장이
-- 되어 금액 위변조·중복 승인·무결제 전이를 모두 DB 레벨에서 막는다.
--
-- 번호: V19 는 다른 작업이 선점해 이 변경은 V20 을 쓴다(직전 파일이 V18 이라 중간 번호가 비어 있으나,
-- Flyway 는 번호 연속성이 아니라 순서만 보므로 문제 없다).
--
-- 멱등 가드(IF NOT EXISTS / DO 블록): 캐노니컬 DDL(HajaCheck_script.sql)에 이 스키마를 함께 반영하므로,
-- baseline-on-existing 경로(이미 캐노니컬 전체 스키마가 적재된 DB)에서 이 파일이 처음 적용될 때
-- 'already exists'로 기동을 깨뜨리지 않아야 한다(V12·V18과 동일 패턴).

do
$$
    begin
        if not exists (select 1 from pg_type where typname = 'payment_status_type') then
            create type payment_status_type as enum ('READY', 'PAID', 'FAILED', 'CANCELED');
        end if;
    end
$$;

do
$$
    begin
        if not exists (select 1 from pg_type where typname = 'payment_method_type') then
            create type payment_method_type as enum ('CARD');
        end if;
    end
$$;

comment on type payment_status_type is '결제 상태(주문생성/승인완료/실패/취소)';

comment on type payment_method_type is '결제 수단(이번 범위는 카드 단일 — 간편결제·계좌이체는 범위 밖)';

create table if not exists payments
(
    id              bigint generated always as identity
        primary key,
    order_id        varchar(64)                                              not null,
    user_id         bigint                                                   not null
        references users,
    company_id      bigint
        references companies,
    user_plan_id    bigint
        references user_plans,
    plan_id         bigint                                                   not null
        references plans,
    plan_name       plan_name_type                                           not null,
    amount          numeric(10, 2)                                           not null,
    status          payment_status_type      default 'READY'::payment_status_type not null,
    method          payment_method_type,
    payment_key     varchar(200),
    receipt_url     text,
    requested_at    timestamp with time zone default now()                   not null,
    approved_at     timestamp with time zone,
    failure_code    varchar(100),
    failure_message text
);

comment on table payments is '플랜 구독 결제 원장. 주문 사전 등록(READY)→승인(PAID) 2단계로 관리하며, 금액·요금제명을 결제 시점 스냅샷으로 보관해 이후 요금제 가격이 바뀌어도 과거 이력이 소급 변경되지 않는다.';

comment on column payments.id is '결제 식별자';

comment on column payments.order_id is '서버가 발급한 주문 식별자(UUID 기반). 클라이언트 제공 값을 신뢰하지 않는다.';

comment on column payments.user_id is '결제를 요청한 사용자 식별자(구독 소유자)';

comment on column payments.company_id is '회사 구독 결제일 때의 기업 계정 식별자(개인 구독은 NULL)';

comment on column payments.user_plan_id is '승인 성공으로 발급된 구독 식별자. READY 단계에서는 NULL이며, PAID인데 NULL이면 승인은 됐으나 플랜 전이가 끝나지 않은 상태(운영 대사 신호)다.';

comment on column payments.plan_id is '결제 대상 요금제 식별자';

comment on column payments.plan_name is '결제 시점 요금제명 스냅샷(요금제 개명·재정의와 무관하게 이력 보존)';

comment on column payments.amount is '결제 시점 청구 금액 스냅샷(서버가 plans.price_monthly로 결정하며 클라이언트 전달값을 쓰지 않는다)';

comment on column payments.status is '결제 상태';

comment on column payments.method is '결제 수단(승인 성공 시 기록, 카드 외 수단은 NULL)';

comment on column payments.payment_key is 'PG(토스페이먼츠)가 발급한 결제 키. 승인 성공 시 기록하며 부분 유니크 인덱스로 중복 승인을 최종 차단한다.';

comment on column payments.receipt_url is 'PG 영수증 URL(승인 성공 시 기록)';

comment on column payments.requested_at is '주문 생성 시각';

comment on column payments.approved_at is '결제 승인 시각(승인 성공 시 기록)';

comment on column payments.failure_code is 'PG가 반환한 실패 코드(승인 실패 시 기록)';

comment on column payments.failure_message is 'PG가 반환한 실패 사유(승인 실패 시 기록)';

create unique index if not exists uq_payments_order_id
    on payments (order_id);

create unique index if not exists uq_payments_payment_key
    on payments (payment_key)
    where (payment_key is not null);

create index if not exists idx_payments_user
    on payments (user_id);

create index if not exists idx_payments_company
    on payments (company_id);

comment on index uq_payments_order_id is '동일 주문 식별자가 둘 이상 존재하는 것을 방지한다(주문 사전 등록의 유일성 보장).';

comment on index uq_payments_payment_key is '동일 PG 결제 키로 두 건 이상이 승인되는 것을 방지한다(중복 승인·중복 청구 최종 방어선).';

comment on index idx_payments_user is '사용자별 결제 이력 조회를 위한 인덱스';

comment on index idx_payments_company is '회사별 결제 이력 조회를 위한 인덱스';
