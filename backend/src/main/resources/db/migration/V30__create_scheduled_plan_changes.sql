-- Flyway V30 — 플랜 하향 예약(scheduled_plan_changes) 신설(#1105 / HAJA-526).
--
-- 배경: 지금까지 관리자 콘솔의 요금제 변경(AdminPlanService#changePlan)은 즉시 전이만 지원했다. 하향을
-- 신청하는 순간 초과 좌석이 그 자리에서 SUSPENDED 되므로, 이미 낸 요금 기간이 남아 있어도 권한이 바로
-- 내려간다. 이 테이블이 "지금 신청하고 다음 결제 주기에 적용"을 표현하는 예약 원장이 되어, 잔여 기간
-- 동안은 현재 요금제를 그대로 유지하고 current_period_end(#1104 / V27)에 스케줄러가 무결제 전이한다.
--
-- 번호(2026-07-29 재번호): 착수 시점 dev 최신이 V28(#1145 notification_type PLAN_EXPIRED)이라 처음엔
-- V29로 잡았으나, 다른 팀원이 V29(reports.deleted_at)를 선점한 것이 확인돼 V30으로 밀었다(뒤따르는
-- notification_type 라벨 파일도 V30→V31). 그 V29는 아직 dev에도 열린 PR에도 없고 공유 dev DB
-- (hajacheck_dev)에만 찍혀 있는 상태라, 지금 이 브랜치만 놓고 보면 [29]가 비어 있다.
--
-- ⚠️ 따라서 이 브랜치는 그 V29 PR이 dev에 머지된 뒤에야 결번 없이 들어갈 수 있다. spring.flyway.
-- out-of-order 가 기본 false 라, 결번 상태로 배포되면 나중에 V29가 도착하는 순간 validate 실패로 앱
-- 기동이 거부된다(#531 형태) — FlywayMigrationVersionSequenceTest 가 그 상태를 CI에서 막는다.
-- 그때까지 이 테스트가 [29] 결번으로 빨간 것은 예상된 상태이며, 번호를 당기거나 테스트를 우회하지 않는다.
--
-- 멱등 가드(IF NOT EXISTS / DO 블록): 캐노니컬 DDL(HajaCheck_script.sql)에 이 스키마를 함께 반영하므로,
-- baseline-on-existing 경로(이미 캐노니컬 전체 스키마가 적재된 DB)에서 이 파일이 처음 적용될 때
-- 'already exists'로 기동을 깨뜨리지 않아야 한다(V20·V27과 동일 패턴).

do
$$
    begin
        if not exists (select 1 from pg_type where typname = 'scheduled_plan_change_status_type') then
            create type scheduled_plan_change_status_type as enum ('PENDING', 'APPLIED', 'CANCELED', 'FAILED');
        end if;
    end
$$;

comment on type scheduled_plan_change_status_type is '플랜 하향 예약 상태(대기/적용됨/취소됨/실패)';

create table if not exists scheduled_plan_changes
(
    id             bigint generated always as identity
        primary key,
    user_plan_id   bigint                                                                          not null
        references user_plans,
    target_plan_id bigint                                                                          not null
        references plans,
    effective_at   timestamp with time zone                                                        not null,
    keep_user_ids  bigint[]                         default '{}'::bigint[]                         not null,
    confirmed_seat_overflow integer                  default 0                                      not null,
    status         scheduled_plan_change_status_type default 'PENDING'::scheduled_plan_change_status_type not null,
    created_by     bigint                                                                          not null
        references users,
    created_at     timestamp with time zone         default now()                                  not null,
    applied_at     timestamp with time zone,
    failure_reason text
);

comment on table scheduled_plan_changes is '플랜 하향 예약 원장(#1105). 하향을 즉시 반영하지 않고 예약만 남겨, 잔여 결제 기간에는 현재 요금제를 유지하고 effective_at(=예약 시점의 user_plans.current_period_end)에 스케줄러가 무결제 전이한다. 상향은 예약 대상이 아니며(결제 경로 전용) 상향·즉시 변경 시 PENDING 예약은 CANCELED 로 무효화된다.';

comment on column scheduled_plan_changes.id is '예약 식별자';

comment on column scheduled_plan_changes.user_plan_id is '예약을 건 시점의 구독 식별자(user_plans). 이 구독이 다른 경로로 전이(결제 승인·즉시 변경·만료 강등)되면 예약은 실행 시점에 무효(CANCELED)로 판정된다.';

comment on column scheduled_plan_changes.target_plan_id is '하향 대상 요금제 식별자. ⚠️ 무료 요금제(price_monthly = 0)만 허용한다 — 정기결제(빌링키)가 없어 유료 대상을 허용하면 청구 없이 유료 1개월이 발급되고, 티어를 한 단계씩 내릴 때마다 무상 기간을 반복 취득할 수 있다(#1105 보안 리뷰 P1).';

comment on column scheduled_plan_changes.effective_at is '예약 실행 기준 시각. 예약 생성 시점의 user_plans.current_period_end(#1104)를 그대로 복사한다 — 잔여 기간이 끝나는 순간이 곧 적용 시점이다.';

comment on column scheduled_plan_changes.keep_user_ids is '하향으로 좌석이 넘칠 때 관리자가 유지하도록 선택한 구성원 id(#890 Phase 2와 같은 의미). 빈 배열이면 자동 규칙(owner + id 오름차순). ⚠️ 예약은 이 목록을 한 달 가까이 보관하므로 실행 시점에 반드시 재검증한다(퇴사·정지 id 드롭 → 자동 규칙 보충 → ACTIVE ADMIN 잔존 재확인).';

comment on column scheduled_plan_changes.confirmed_seat_overflow is '신청 시점에 관리자가 confirmOverflow=true 로 확인한 정지 예정 인원 수(#1105 리뷰 P2-4). confirmOverflow 는 "그 시점 미리보기"에 대한 동의이므로, 실행 시점 정지 인원이 이 값을 넘으면 동의 범위를 벗어난 것이다 — 그때는 적용하지 않고 FAILED 로 종료해 상위 요금제를 유지한다(아무도 잘못 정지되지 않는 fail-safe).';

comment on column scheduled_plan_changes.status is '예약 상태. PENDING 만 실행 대상이며 전이는 UPDATE ... WHERE status = PENDING 조건부로만 이뤄진다(중복 실행 차단).';

comment on column scheduled_plan_changes.created_by is '예약을 생성한 사용자 식별자(회사 owner). 오예약 추적용 감사 정보다.';

comment on column scheduled_plan_changes.created_at is '예약 생성 시각';

comment on column scheduled_plan_changes.applied_at is '예약이 실제로 적용(APPLIED)된 시각. 실패·취소 건은 NULL이다.';

comment on column scheduled_plan_changes.failure_reason is '실행이 FAILED 로 끝났거나 무효(CANCELED)로 판정된 사유. 사람이 원인을 특정하기 위한 값이라 개인정보(이메일·이름 등)를 담지 않는다.';

create unique index if not exists uq_scheduled_plan_changes_pending
    on scheduled_plan_changes (user_plan_id)
    where (status = 'PENDING'::scheduled_plan_change_status_type);

comment on index uq_scheduled_plan_changes_pending is '한 구독에 대기 중(PENDING) 예약이 둘 이상 만들어지는 것을 방지한다. 애플리케이션의 "조회 후 없으면 생성"은 비원자적이라 동시 요청 두 건이 서로를 못 보고 각각 예약될 수 있는데, 그 상태가 되면 같은 주기에 하향이 두 번 실행돼 좌석 정지 결과가 예측 불가능해진다 — 그 경합을 DB 레벨에서 직렬화한다.';

create index if not exists idx_scheduled_plan_changes_due
    on scheduled_plan_changes (effective_at)
    where (status = 'PENDING'::scheduled_plan_change_status_type);

comment on index idx_scheduled_plan_changes_due is '스케줄러의 실행 대상 조회(status = PENDING AND effective_at <= now)를 위한 부분 인덱스. 적용·취소된 예약은 인덱스에서 빠지므로 원장이 누적돼도 조회 비용이 늘지 않는다.';
