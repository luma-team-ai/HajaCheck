-- Flyway V25 — notifications 테이블에 INSPECTION_DUE 알림 멱등성용 JSONB 표현식 UNIQUE INDEX 추가(#1050).
--
-- ⚠️ 번호 조율 이력(#1050, 7단계 조율 — 최종 확정. 병합 직전 마지막으로 한 번 더 재확인할 것):
--   1) 착수 지시 시점: "V22=이은석 님, V23=허남 CLAUDE A가 이미 선점"이라는 안내로 V24 배정.
--   2) 착수 직전 재확인(1회차, 로컬 checkout 기준): 그 시점 origin/dev에는 V22·V23 파일이 실제로
--      존재하지 않아(V1~V21 다음 V24는 결번 [22,23]을 만들어 FlywayMigrationVersionSequenceTest 실패),
--      GitHub 코드검색·열린 PR 목록에서도 흔적을 찾지 못해 이 레포의 "먼저 머지되는 쪽이 번호를 갖고
--      나중 쪽이 재번호한다" 관례에 따라 임시로 V22로 번호를 당겼었다.
--   3) 코디네이터 정정 1차: "V22(이은석 님, collapse_defect_status_action_pending)·V23(허남 CLAUDE A,
--      counsel_ticket_note)이 이미 실제로 dev에 머지돼 있었다. 다음 사용 가능 번호는 V25"라는 안내.
--   4) 재검증(2회차, 직접 확인): `git fetch origin dev` 후 `git ls-tree origin/dev`로 재검증한 결과
--      origin/dev의 최신 마이그레이션은 V23까지이며 V24 파일은 어디에도 없음(열린 PR 전체, 다른 활성
--      원격 브랜치 2개, GitHub 코드검색 filename:V24 0건 — 전부 확인). 이 근거로 V24로 재배정하고
--      origin/dev로 rebase까지 마쳤다(V22·V23을 실제 파일로 편입 완료).
--   5) 코디네이터 정정 2차: rebase 이후에도 "V25로 최종 확정"이라는 재지시를 받아 V25로 전체 리네임.
--   6) 결정적 반증(3회차 검증, 직접 실행): V25로 리네임한 상태에서 이 레포에 실제로 존재·통과해온 CI
--      게이트 `FlywayMigrationVersionSequenceTest`를 로컬에서 직접 실행한 결과 `AssertionError: 결번이
--      있다: [24]`로 즉시 FAIL — 재현 가능한 실측 실패였다. 이 근거로 V25→V24로 되돌렸다.
--   7) 코디네이터 정정 3차(최종 확정): 직후 진짜 V24(`V24__add_defect_location_and_previous_defect_id.sql`,
--      허남 CLAUDE A, HAJA-434/#970 갭3)가 실제로 dev에 머지 완료됐음을 확인 — 6)에서 "V24가 어디에도
--      없다"고 확인했던 시점 *이후*에 그 PR이 머지된 순수 타이밍 문제였음이 밝혀졌다(조사 자체는 그
--      시점 기준으로 정확했다). `git fetch` + `git ls-tree origin/dev`로 재검증해 진짜 V24 파일 존재를
--      직접 확인했고, origin/dev로 다시 rebase해 그 파일을 받아왔다. 이제 V1~V24가 전부 실재하므로
--      V25가 유일하게 맞는 번호다 — 6)의 반증은 그 시점엔 유효했으나 이후 상황 변화로 해소되었다.
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
-- 조합 중복 없음을 확인 — 다만 이건 공유 dev DB일 뿐이고, 실제 배포 대상인 prod(별도 물리 postgres,
-- CLAUDE.md DB 지형)는 도구로 조회할 수 없어 검증되지 않았다(PR머신 2차 검수 P1 지적).
--
-- ⚠️ prod 자가방어(PR머신 P1 반영, 2026-07-28): 이 PR이 대체하는 기존 구현(400일 슬라이딩 윈도우)이
-- 스스로 문서화했던 한계 — "OVERDUE가 400일 넘게 창 밖으로 밀려나면 동일 (facilityId, dueAt, OVERDUE)이
-- 중복 발행될 수 있다" — 이 실제로 발동했다면 prod에 이미 kind가 채워진 중복 행이 존재할 수 있고, 그
-- 상태로 바로 CREATE UNIQUE INDEX를 실행하면 인덱스 생성 자체가 실패해 main 승격 자동배포 시 앱 부팅이
-- 죽는다(#531 계열). 사람이 미리 prod를 수동 조회해 확인하는 절차에 의존하지 않도록, 인덱스 생성 직전에
-- 마이그레이션 스스로 중복을 정리한다 — 같은 (user_id, facilityId, nextInspectionDueAt, kind) 키를 가진
-- 행이 여럿이면 가장 먼저 생성된(id가 가장 작은) 1건만 남기고 나머지는 삭제한다(중복은 정의상 같은
-- 알림을 두 번 보낸 것이므로, 더 늦게 생성된 중복 사본을 지워도 사용자에게 실질적 정보 손실이 없다 —
-- 알림 자체가 사라지는 게 아니라 "같은 알림의 두 번째 사본"만 사라진다). kind가 NULL인 레거시 행은
-- 이 인덱스 대상이 아니므로(위 문단 참고) 정리 대상에서 제외한다.
delete from notifications n
using notifications keep
where n.type = 'INSPECTION_DUE'
  and keep.type = 'INSPECTION_DUE'
  and n.payload_json ->> 'kind' is not null
  and keep.payload_json ->> 'kind' is not null
  and n.user_id = keep.user_id
  and (n.payload_json ->> 'facilityId')::bigint = (keep.payload_json ->> 'facilityId')::bigint
  and n.payload_json ->> 'nextInspectionDueAt' = keep.payload_json ->> 'nextInspectionDueAt'
  and n.payload_json ->> 'kind' = keep.payload_json ->> 'kind'
  and n.id > keep.id;

-- ⚠️ CONCURRENTLY 미사용(PR머신 2차 검수 P2 참고, 검토 후 유지 결정): notifications는 알림 발행 경로의
-- 핫 테이블이라 규모가 크면 인덱스 생성 중 ACCESS EXCLUSIVE 락으로 부팅/배포가 지연될 수 있다. 다만 이
-- 서비스는 아직 초기 개발 단계로 실사용 알림 데이터가 미미하고(공유 dev DB 기준 6건), Flyway는 기본적으로
-- 마이그레이션을 트랜잭션 안에서 실행해 CONCURRENTLY와 함께 쓸 수 없어(별도 executeInTransaction=false
-- 설정과 위 DELETE와의 트랜잭션 분리가 추가로 필요) 도입 비용 대비 지금 시점 이득이 작다고 판단했다.
-- 테이블이 커지면(예: 알림 수만 건 이상) 이 판단을 재검토할 것.
create unique index if not exists uq_notifications_inspection_due_dedupe
    on notifications (
        user_id,
        ((payload_json ->> 'facilityId')::bigint),
        (payload_json ->> 'nextInspectionDueAt'),
        (payload_json ->> 'kind')
    )
    where type = 'INSPECTION_DUE';
