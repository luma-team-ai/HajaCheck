-- Flyway V19 — FREE 요금제 좌석 한도 상향(1 → 2, #843 / HAJA-441).
--
-- 배경: #843에서 plans.max_seats 강제를 켜자 FREE(max_seats=1)는 대표(owner) 한 명이 유일한 좌석을
-- 이미 점유하고 있어, 초대 코드 redeem(POST /api/users/me/invite-code)이 항상
-- PLAN_SEAT_QUOTA_EXCEEDED(403)로 막혔다 — 무료 회사의 온보딩 자체가 불가능해진다.
-- 제품 결정: FREE = 2석(대표 1 + 점검자 1). 초대 온보딩을 되살리는 최소값이며 STANDARD(3석)와의
-- 차등은 그대로 유지된다.
--
-- ⚠️ 조건절(and max_seats = 1)을 거는 이유: plans.max_seats 는 플랫폼 관리자 "플랜 정책 설정"
-- (PlatformAdminPlanPolicyService → Plan#updatePolicy)으로 운영 중에 바뀔 수 있는 값이다. 무조건
-- UPDATE 하면 운영자가 이미 조정해 둔 값을 이 마이그레이션이 조용히 덮어쓴다. "아직 시드 기본값(1)인
-- DB만" 올리도록 좁히고, 그 외에는 0행 갱신으로 멱등하게 no-op 성공한다.
-- 캐노니컬 DDL(docs/design/db/HajaCheck_script.sql)로 새로 설치한 DB는 이미 2라 여기서도 no-op 이다
-- — 신규 설치 경로와 기존 DB 증분 경로가 같은 값으로 수렴한다.
update plans
   set max_seats = 2
 where name = 'FREE'::plan_name_type
   and max_seats = 1;
