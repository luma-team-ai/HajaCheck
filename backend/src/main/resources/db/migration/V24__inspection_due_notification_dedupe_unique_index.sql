-- Flyway V24 — notifications 테이블에 INSPECTION_DUE 알림 멱등성용 JSONB 표현식 UNIQUE INDEX 추가(#1050).
--
-- ⚠️ 번호 조율 이력(#1050, 3단계 조율 — 최종 확정 전 반드시 재확인할 것):
--   1) 착수 지시 시점: "V22=이은석 님, V23=허남 CLAUDE A가 이미 선점"이라는 안내로 V24 배정.
--   2) 착수 직전 재확인(1회차): 그 시점의 로컬 checkout(origin/dev 기준)에는 V22·V23 파일이 실제로
--      존재하지 않아(V1~V21 다음 V24가 결번 [22,23]을 만들어 FlywayMigrationVersionSequenceTest 실패),
--      GitHub 코드검색·열린 PR 목록에서도 흔적을 찾지 못해 이 레포의 "먼저 머지되는 쪽이 번호를 갖고
--      나중 쪽이 재번호한다" 관례에 따라 임시로 V22로 번호를 당겼었다.
--   3) 코디네이터 정정: 직후 "방금 dev 최신 상태 재확인 — V22(이은석 님, collapse_defect_status_action_
--      pending)·V23(허남 CLAUDE A, counsel_ticket_note)이 이미 실제로 dev에 머지돼 있었다. 다음 사용
--      가능 번호는 V25"라는 안내를 받음.
--   4) 재확인(2회차, 이 커밋 시점): `git fetch origin dev` 후 `git ls-tree origin/dev`로 직접 재검증한
--      결과 origin/dev의 최신 마이그레이션은 V23(counsel_ticket_note)까지이며 V24 파일은 없음. 열린 PR
--      전체(`gh pr list --state open`, #1100 OpenAPI 문서 PR 1건뿐), 다른 활성 원격 브랜치 2개
--      (backend/1025-defect-status-enum-cleanup 최신 V22, ai-server/1073-nl-search-enum-sync 최신
--      V21), GitHub 코드검색(`filename:V24`, 0건) 어디에서도 V24 파일의 흔적을 찾지 못함. 이 레포의
--      결번-거부 테스트(FlywayMigrationVersionSequenceTest) 특성상 실제로 존재하지 않는 V24를 비워두고
--      V25로 건너뛰면 이후 아무도 V24를 채우지 않는 한 병합 시 영구 결번으로 CI가 깨진다 — 그래서 직접
--      확인된 증거(V24 비어있음)를 근거로 이 파일은 V24로 최종 배정했다. 코디네이터가 V25를 지시한
--      근거(예: 아직 어디에도 안 올라간 이은석 님/다른 팀원의 out-of-band V24 예약)를 확인하지 못했으므로,
--      이 판단은 최종 보고에서 코디네이터에게 명확히 재확인을 요청할 사항이다 — 실제로 V24가 다른 곳에
--      예약되어 있다면 병합 직전 V25로 재번호해야 한다.
--
-- 배경: InspectionDueNotificationScheduler의 멱등성(dedup)은 지금까지 "알림 이력 조회 후 애플리케이션
-- 메모리에서 Set 비교" 방식이었다(NOTIFICATION_LOOKBACK_DAYS 400일 슬라이딩 윈도우, PR머신+사람검수
-- P2 #1032). 이 방식은 스캔범위·보존기간 트레이드오프가 계속 발생하고(400일 윈도우 자체가 그 사례),
-- 다중 인스턴스로 스케일아웃하면 레플리카마다 각자 발행 시도를 해 확정적으로 중복 발행된다(read-then-write
-- 레이스 — 조회와 발행 사이 원자성이 없음). 이 인덱스를 도입하면 "조회 없이 바로 발행 시도 → DB가
-- unique violation으로 중복을 원자적으로 거부"하는 패턴으로 전환할 수 있어(애플리케이션 코드는
-- InspectionDueNotificationScheduler 참고), 두 문제(윈도우 트레이드오프·다중 인스턴스 안전성)를 함께
-- 해결한다.
--
-- 승인된 설계(옵션 A, 유병현 PL 승인 완료, 이슈 #1050 코멘트 참고): 신규 테이블/컬럼 없이 기존
-- notifications 테이블에 JSONB 표현식 UNIQUE INDEX만 추가한다. kind(BEFORE/DUE/OVERDUE)는 payload_json
-- 안의 텍스트 필드 그대로 비교하며, enum으로 승격하지 않는다(결정 완료).
--
-- ⚠️ 이 인덱스는 kind 필드가 있는(#540 이후 생성된) payload만 원자적으로 방어한다. payload_json->>'kind'가
-- NULL인 #540 이전 레거시 payload는 PostgreSQL unique index가 NULL끼리 서로 다른 값으로 취급해 잡지
-- 못한다 — 이 레거시 케이스는 애플리케이션 레벨에서 별도로 방어한다(InspectionDueNotificationScheduler.
-- findLegacyKindLessInspectionDueByUserIdIn 참고, 이 집합은 #540 배포 시점 이후로 늘어나지 않는
-- 유한 집합이다).
--
-- 착수 전 안전 확인(2026-07-28, 공유 dev DB read-only 조회): notifications 테이블의 type='INSPECTION_DUE'
-- 행(총 6건, 전부 kind 필드 없는 레거시 payload)에서 (user_id, facilityId, nextInspectionDueAt, kind)
-- 조합 중복 없음을 확인 — UNIQUE INDEX 생성이 기존 데이터로 인해 실패할 위험 없음.
create unique index if not exists uq_notifications_inspection_due_dedupe
    on notifications (
        user_id,
        ((payload_json ->> 'facilityId')::bigint),
        (payload_json ->> 'nextInspectionDueAt'),
        (payload_json ->> 'kind')
    )
    where type = 'INSPECTION_DUE';
