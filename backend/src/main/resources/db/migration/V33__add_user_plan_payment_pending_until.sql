-- Flyway V33 — user_plans 미결제 유예 표식 신설(#1177 / 유료→유료 하향 C안 "유예 후 강등").
--
-- 번호 배분(2026-07-29): dev 최신은 V31이지만 **V32는 다른 작업자가 선점**해(그쪽 브랜치에서 작업 중,
-- dev·공유 dev DB에는 아직 도착하지 않았다) 이 작업은 V33을 쓴다.
--   ⚠️ 그 결과 V32가 dev에 머지되기 전까지 FlywayMigrationVersionSequenceTest 가 **결번 [32]**로 실패한다.
--      예상된 상태이며(#1105에서 V29 선점으로 같은 상황을 겪었고 선점 PR 머지로 해소됐다) 번호를 당기거나
--      테스트를 고쳐서 우회하지 않는다.
--   ⚠️ spring.flyway.out-of-order 가 기본 false 라, 이 브랜치가 V32 PR보다 **먼저 머지되면** 나중에 V32가
--      도착하는 순간 validate 실패로 앱 기동이 거부된다(#531 형태). 머지 순서는 V32 → V33 이어야 한다.
--
-- 배경: #1105(V30)로 도입한 플랜 하향 예약은 대상을 무료 요금제(FREE)로 제한했다. ENTERPRISE→STANDARD
-- 같은 유료→유료 하향은 적용 시점이 무인 배치라 그 순간 결제창을 띄울 수 없고, 그렇다고 새 유료 주기를
-- 그냥 열면 어떤 경로로도 청구되지 않는 유료 1개월이 발급된다(#1105 보안 리뷰 P1 = 결제 경로 우회).
--
-- C안: 유료 대상 전이를 허용하되 "미결제 유예" 상태로 발급한다.
--   ENT→STD 예약 → current_period_end 도달
--     → STANDARD 발급하되 payment_pending_until(= 유예 마감) 설정, 유예 중 엔타이틀먼트는 FREE 기준
--     → 유예 안에 결제 → 정상 1개월 주기로 새 구독 발급(표식 없음)
--     → 유예 만료      → FREE 강등
-- 무결제로 발급되는 것은 "유료 티어 이름"뿐이고 실제 엔타이틀먼트는 FREE라, 반복 예약으로 무상 기간을
-- 취득하는 우회로가 생기지 않는다.
--
-- ⚠️ 왜 파생 판정이 아니라 컬럼인가(1차 구현 회귀): 처음에는 스키마 변경을 피하려고 "유료 요금제 AND
-- scheduled_plan_changes APPLIED 이력 AND 연결된 PAID 결제 없음"으로 파생 판정했는데, 리뷰에서 네 가지
-- 결함이 드러났다.
--   (1) 조인 축이 `applied_at == current_period_start` 라는 암묵적 시각 일치 불변식에 의존했다.
--   (2) `payments.user_plan_id` 는 플랜 전이가 성공한 뒤에만 채워지는데 유예 행은 그 전이 분기를 타지
--       않으므로, "연결된 PAID 없음"이 유예 행에 대해 <b>항상 참</b>이었다(죽은 조건). 그 결과 승인은
--       됐지만 전이가 끝나지 않은 대사 대상(#1010, PAID + user_plan_id IS NULL) 고객이 유예로 오판정돼
--       강등될 수 있었다.
--   (3) 관리자 즉시 변경이 주기를 승계하면 plan_id 가 바뀌어 판정이 영구히 거짓이 됐다 —
--       청구 없는 유료 구독이 무기한 지속(#1105가 막은 구멍의 재개방).
--   (4) 한도 판정 hot path 가 status='APPLIED' 조회인데 기존 인덱스는 둘 다 PENDING 부분 인덱스라
--       seq scan 이었다.
-- 명시 컬럼 하나로 네 문제가 동시에 사라진다 — 판정은 `payment_pending_until IS NOT NULL` 한 줄이 된다.
--
-- ⚠️ 값 규약: NULL = 정상 구독. NOT NULL = 미결제 유예 중이며 그 값이 결제 마감이다.
--    유예 중 행은 current_period_end 도 같은 값으로 맞춘다(UserPlan#startPaymentGracePeriod) — 유예 만료
--    강등을 기존 만료 강등 경로(PlanExpiryWriter#expireToFreePlan, 재검증 조건이 current_period_end <
--    기준시각)로 그대로 재사용하기 위해서다.
--
-- notification_type enum은 건드리지 않는다 — 유예 진입 통지는 기존 PLAN_DOWNGRADED(V31) payload에
-- paymentPendingUntil 을 실어 재사용하고, 유예 만료 강등 통지는 기존 PLAN_EXPIRED(V28)를 그대로 쓴다
-- (강등 실행 자체가 PlanExpiryWriter 재사용이라 사용자에게는 정확히 같은 사건이다).
--
-- 멱등 가드(IF NOT EXISTS): 캐노니컬 DDL(HajaCheck_script.sql)에 이 컬럼·인덱스를 함께 반영하므로,
-- baseline-on-existing 경로(이미 캐노니컬 전체 스키마가 적재된 DB)에서 이 파일이 처음 적용될 때
-- 'already exists'로 기동을 깨뜨리지 않아야 한다(V20·V27·V30과 동일 패턴).
--
-- 백필 없음: 이 마이그레이션 시점에 유예 상태인 구독은 정의상 0건이다(기능이 아직 없다). 기존 행은 전부
-- NULL(정상)이 정답이라 UPDATE 를 두지 않는다.

alter table user_plans
    add column if not exists payment_pending_until timestamptz;

comment on column user_plans.payment_pending_until is '미결제 유예 마감 시각(#1177). NULL = 정상 구독. NOT NULL = 유료→유료 하향(C안)으로 결제 없이 발급된 유료 구독이며, 이 시각까지 결제되지 않으면 FREE로 강등된다. 유예 중에는 티어 이름과 무관하게 FREE 엔타이틀먼트(한도 3종 + 상담사 연결·AI 부가기능)가 적용된다 — 무결제 유료 혜택 차단. 유예 중 행은 current_period_end 도 같은 값을 갖는다(유예 만료 강등이 기존 만료 강등 경로를 그대로 재사용하기 때문). 관리자 즉시 변경으로 다른 유료 요금제로 옮겨도 이 값은 승계된다(유예 세탁 방지).';

-- 유예 만료 강등 배치(ScheduledPlanChangeScheduler 2단계)의 대상 조회 전용 부분 인덱스.
-- 쿼리: status in (ACTIVE, UPGRADE_REQUESTED) and payment_pending_until < :now and id > :lastId order by id
-- 유예 중 구독은 극소수(사람이 만든 예약의 결과)라 정상 구독 전체가 인덱스에서 빠진다. 선두 컬럼을
-- payment_pending_until 로 두어 범위 조건이 인덱스를 타고, id 를 뒤에 붙여 keyset 페이징의 정렬까지
-- 인덱스에서 해결한다. status 는 카디널리티가 낮고 후보가 이미 극소수라 넣지 않는다.
create index if not exists idx_user_plans_payment_pending
    on user_plans (payment_pending_until, id)
    where (payment_pending_until is not null);

comment on index idx_user_plans_payment_pending is '미결제 유예(#1177) 만료 강등 배치의 대상 조회(payment_pending_until < now, id keyset 순회)를 위한 부분 인덱스. 정상 구독은 payment_pending_until 이 NULL 이라 인덱스에서 제외된다.';
