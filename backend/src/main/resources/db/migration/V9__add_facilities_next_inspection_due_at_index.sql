-- Flyway V9 — facilities.next_inspection_due_at 인덱스 추가(#509, HAJA-273 INSPECTION_DUE 배치 후속).
--
-- InspectionDueNotificationScheduler(NOTI-01, PR #490)가 매일 facilities.next_inspection_due_at <= 오늘
-- 조건으로 전체 시설물을 스캔하는데, 이 컬럼에 인덱스가 없어(idx_facilities_owner만 존재) 매 배치 실행마다
-- 풀스캔이 발생한다. overdue 시설물은 도래일이 갱신되기 전까지 계속 조건에 걸려 스캔 대상에서 빠지지
-- 않으므로, 시간이 지날수록 배치 스캔 비용이 우상향한다.
--
-- 부분 인덱스(WHERE next_inspection_due_at IS NOT NULL) — 점검 주기 미설정 시설물(해당 컬럼 NULL)은
-- 배치의 <= 오늘 조건에 애초에 걸리지 않으므로 인덱스 대상에서 제외해 크기를 줄인다.
create index if not exists idx_facilities_next_inspection_due_at
    on facilities (next_inspection_due_at)
    where next_inspection_due_at is not null;
