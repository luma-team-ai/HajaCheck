-- Flyway V21 — inspection_notification_settings.warn_on_overdue_enabled 기본값을 true로 변경(HAJA-498).
--
-- 배경: #540 ③ 알림설정 게이팅 도입 시 이 컬럼의 기본값(false)을 그대로 애플리케이션 폴백값으로 썼더니,
-- 예정일이 지난(overdue) 시설물에 대해 기존에는 매일 발행되던 INSPECTION_DUE 알림이 더 이상 발행되지
-- 않는 회귀가 발생했다. 유병현(Polalise)님이 "옵션1: DEFAULT true"로 승인해 컬럼 기본값을 되돌린다.
--
-- 적용 시점 확인(2026-07-27, flyway_schema_history 실측): 이 마이그레이션은 dev(V18까지 적용)를 포함해
-- 어디에도 적용된 적이 없고, inspection_notification_settings 테이블은 0행이라 "기존 false 값"
-- 자체가 존재하지 않는다 — 아래 UPDATE는 배포 시점 레이스(그 사이 다른 요청이 false로 행을 만든 경우)에
-- 대비한 방어적 백필일 뿐, 이 마이그레이션이 실제로 되돌릴 기존 데이터는 없다.
alter table inspection_notification_settings
    alter column warn_on_overdue_enabled set default true;

-- 방어적 백필(확인 시점 기준 대상 0건이지만, 배포 시점 레이스 대비 유지) — 기존에 명시적으로 false를
-- 선택한 사용자 설정까지 덮어쓰면 안 되므로, 이 마이그레이션은 "기본값 자체가 false였던" 최초 시드
-- 상태만 되돌리는 목적이며, 사용자가 실제로 앱에서 저장한 명시적 false와 구분할 수 없다는 한계가 있다
-- (해당 시점 데이터가 0행임을 확인했으므로 이번 배포에서는 실질적으로 영향이 없다).
update inspection_notification_settings
   set warn_on_overdue_enabled = true
 where warn_on_overdue_enabled = false;