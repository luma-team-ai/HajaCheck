-- Flyway V22 — defect_status_type 에서 ACTION_PENDING(조치대기) 제거(5단계 → 4단계).
--
-- 번호 조율: V19(media.facility_id, #632/#652)·V20(payments, #988)·V21(warn_on_overdue_enabled
-- 기본값, #540/HAJA-498)이 dev 에 먼저 들어갔으므로 다음 빈 번호인 V22 를 쓴다. 갭3(#970)·HAJA-437 이
-- 번호를 구두 예약해 두었더라도, 그 PR 이 dev 에 없는 동안 예약분을 비워두고 더 큰 번호를 잡으면
-- 결번이 되어 FlywayMigrationVersionSequenceTest(결번 금지)가 즉시 실패한다. spring.flyway.out-of-order
-- 가 기본 false 라, 결번 상태로 배포되면 나중에 그 번호가 도착하는 순간 validate 실패로 기동이
-- 거부된다(#531 형태). 따라서 먼저 머지되는 쪽이 빈 번호를 순서대로 가져가고 후행 PR 이 밀린다.
--
-- 상태머신을 DETECTED → CONFIRMED → IN_PROGRESS → RESOLVED 로 축소한다.
-- CONFIRMED(검수확정)가 "검수완료 + 아직 조치 착수 전"(구 조치대기)의 의미를 겸하므로,
-- 기존 ACTION_PENDING 행은 전부 CONFIRMED 로 이관한다.
--
-- ⚠️ 순서 주의: Postgres enum 은 해당 값을 쓰는 행이 남아 있으면 타입을 좁힐 수 없다.
--    반드시 (1) 데이터 이관 → (2) 타입 교체 순서로 수행한다.
--
-- ⚠️ default 주의: 컬럼 default 는 구 타입에 묶여 있어 타입 변경 전에 drop 해야 한다
--    (drop 하지 않으면 "default for column cannot be cast automatically" 로 실패).
--
-- 재적용 안전성: 비교를 status::text 로 하므로 5라벨(구 스키마)·4라벨(이미 적용된 스키마) 어디서
-- 실행해도 실패하지 않는다 — 4라벨 DB 에서는 매칭 0행 no-op 으로 지나간다. 'ACTION_PENDING' 을
-- enum 리터럴로 두면 4라벨 DB 에서 "invalid input value for enum" 으로 기동이 막힌다.

-- (1) 데이터 이관 — 타입을 좁히기 전에 먼저 실행해야 한다.
update defects
set status = 'CONFIRMED'
where status::text = 'ACTION_PENDING';

-- (2) ACTION_PENDING 이 빠진 새 enum 타입 생성.
--     수동 재시도(앞선 시도가 rename 전에 중단된 경우) 대비로 잔존 타입을 먼저 정리한다.
drop type if exists defect_status_type_new;

create type defect_status_type_new as enum ('DETECTED', 'CONFIRMED', 'IN_PROGRESS', 'RESOLVED');

-- (3) 구 타입에 묶인 default 제거 → 컬럼 타입 전환 → default 재설정
alter table defects
    alter column status drop default;

alter table defects
    alter column status type defect_status_type_new
        using status::text::defect_status_type_new;

alter table defects
    alter column status set default 'DETECTED'::defect_status_type_new;

-- (4) 구 타입 drop 후 새 타입을 원래 이름으로 rename
drop type defect_status_type;

alter type defect_status_type_new rename to defect_status_type;

comment on type defect_status_type is '결함 조치 상태(탐지됨/확인됨/조치중/해결됨)';
