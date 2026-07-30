-- Flyway V38 — 기존 기업 계정 소급 자동승인 + 오너 멤버십 소급 발급 (#1324)
--
-- 배경:
--   #1324 부터 신규 가입은 같은 트랜잭션에서 회사를 VERIFIED + APPROVED 로 만들고 오너의 APPROVED
--   멤버십을 함께 발급한다(CompanyAccountWriter.createAccount). 그 이전에 가입한 회사들은
--   status=PENDING_REVIEW / verification_status=PENDING 이고 company_memberships 행도 없어서
--   회사 스코프 기능(점검 생성·담당자 배정)이 영구히 닫혀 있다.
--
--   회사 스코프 판정(CompanyMembershipRepository.existsEffectiveApprovedMembership 및 동일 불변식의
--   DB 트리거 check_inspection_assigned_inspector_company)은 아래 3+1 조건을 **모두** 요구한다:
--     ① companies.status = 'APPROVED'
--     ② companies.verification_status = 'VERIFIED'
--     ③ 오너의 company_memberships 유효 APPROVED 행(approved_at not null · revoked_at null · 미만료)
--     ④ users.company_id = company_memberships.company_id 이고 users.status = 'ACTIVE'
--   그래서 여기서 ①②③(+④는 null 인 경우에만 보완)을 함께 채운다. 하나라도 빠지면 소급이 무의미하다.
--
-- 스키마 변경 없음 — 데이터 전이(UPDATE/INSERT)만 한다. 따라서 캐노니컬 DDL
-- (docs/design/db/HajaCheck_script.sql) 갱신 대상이 아니고 ddl-auto=validate 에도 영향이 없다.
--
-- 멱등성: 네 문장 모두 WHERE 로 "아직 안 된 행"만 고른다 → 재실행하면 대상 0행(Flyway 는 한 번만
-- 실행하지만, prod baseline 스탬프 사고 이력이 있어 재실행 안전성을 명시적으로 확보한다).
--
-- 운영 트래픽을 무기한 대기시키지 않는다(V7·V36 과 동일 방침).
set local lock_timeout = '5s';

-- ─────────────────────────────────────────────────────────────────────────────
-- (1) PENDING_REVIEW 회사 → APPROVED
--     reviewed_by 는 null 로 남긴다 = "사람 심사자 없음(시스템 자동승인)". 컬럼은 nullable 이다.
--     ⚠️ REJECTED 는 대상에서 제외한다 — 명시적으로 반려된 이력을 소급 승인으로 덮지 않는다.
--     ⚠️ 이미 APPROVED 인 회사는 reviewed_at/reviewed_by 를 건드리지 않는다(기존 심사 이력 보존).
-- ─────────────────────────────────────────────────────────────────────────────
update companies
set status      = 'APPROVED'::company_status_type,
    reviewed_at = now()
where status = 'PENDING_REVIEW'::company_status_type;

-- ─────────────────────────────────────────────────────────────────────────────
-- (2) APPROVED 회사의 진위확인 상태 → VERIFIED
--     대상을 status='APPROVED' 로 스코프한 이유: (1) 직후이므로 여기엔 "이번에 승인된 회사 +
--     원래부터 APPROVED 였던 회사"만 들어오고 **REJECTED 는 자동으로 제외**된다.
--     (verification_status <> 'VERIFIED' 만으로 걸면 반려 회사까지 VERIFIED 로 만들어 버린다.)
--
--     PENDING/FAILED/SKIPPED 상태를 VERIFIED 로 올리는 것은 #1324 운영 결정이다 — 진위확인 결과와
--     무관하게 전면 자동승인하되, 확정 불량(MISMATCH/SUSPENDED/CLOSED)은 애초에 가입 자체가 차단되어
--     저장된 적이 없다(CompanySignupService.isVerificationBlocked). 즉 여기 남아 있는 PENDING 은
--     "불량 확정"이 아니라 "국세청 키 미설정·장애로 확인하지 못함"이다.
--
--     verified_at 은 이미 값이 있으면 보존한다(최초 검증 성공 시각의 의미 — Company#verifiedAt javadoc).
-- ─────────────────────────────────────────────────────────────────────────────
update companies
set verification_status = 'VERIFIED'::business_verification_status_type,
    verified_at         = coalesce(verified_at, now())
where status = 'APPROVED'::company_status_type
  and verification_status <> 'VERIFIED'::business_verification_status_type;

-- ─────────────────────────────────────────────────────────────────────────────
-- (3) 오너의 users.company_id 포인터 보완 (④ 조건)
--     멤버십 쿼리·DB 트리거가 users.company_id = memberships.company_id 를 요구한다.
--     ⚠️ company_id 가 **null 인 경우에만** 채운다 — 이미 다른 회사를 가리키는 포인터는 덮지 않는다
--     (오너가 다른 회사로 옮겨간 데이터를 소급 마이그레이션이 되돌리면 그게 더 큰 사고다).
--     ⚠️ companies.owner_user_id 에는 UNIQUE 가 없다(V1: 비유니크 idx_companies_owner). 한 사용자가
--     둘 이상의 회사를 소유하면 UPDATE ... FROM 이 어느 회사를 고를지 비결정적이므로, 그런 사용자는
--     **아예 건드리지 않는다** — 어느 회사에 소속시킬지는 데이터 소유자가 정할 문제다.
-- ─────────────────────────────────────────────────────────────────────────────
update users u
set company_id = c.id
from companies c
where c.owner_user_id = u.id
  and c.status = 'APPROVED'::company_status_type
  and u.company_id is null
  and not exists (select 1
                  from companies other
                  where other.owner_user_id = u.id
                    and other.id <> c.id);

-- ─────────────────────────────────────────────────────────────────────────────
-- (4) 오너 APPROVED 멤버십 소급 발급 (③ 조건)
--     invited_by = null — 오너의 최초 멤버십은 초대자가 없다(V1 컬럼 주석과 정합).
--     approved_at = now() — check 제약 ck_company_memberships_approved_at
--     (status='APPROVED' 이면 approved_at not null)을 만족시킨다.
--     expires_at = null(기본) — 오너 멤버십은 명시적 만료가 없다. ck_company_memberships_expiry 통과.
--
--     대상 조건과 그 근거:
--       · c.status='APPROVED' — 반려 회사에는 멤버십을 만들지 않는다.
--       · u.company_id = c.id — 포인터가 이 회사를 가리키는 오너만. (3)에서 null 은 이미 보완됐고,
--         다른 회사를 가리키는 오너는 **의도적으로 건너뛴다**(잘못된 회사에 소속을 만들지 않는다.
--         그 회사는 오너 멤버십 없이 남고, 정식 승인/소속 이관 경로에서 다룬다).
--       · 기존 행 없음(uk_company_memberships_company_user = (company_id, user_id) UNIQUE 회피).
--         **기존 행이 있고 status <> 'APPROVED'(REJECTED/REVOKED/EXPIRED/PENDING) 인 경우는 그대로
--         둔다** — 회수·반려 이력을 소급 승인으로 덮지 않는다. 필요하면 정식 승인 경로에서 처리한다.
--       · 그 오너에게 다른 APPROVED 멤버십 없음 — 부분 UNIQUE uq_company_memberships_approved_user
--         (user_id 당 APPROVED 1건, V1)를 밟지 않기 위한 조건. 없으면 마이그레이션이 실패한다.
--         (한 사용자가 여러 회사를 소유하더라도 u.company_id = c.id 조건 때문에 이 SELECT 는 사용자당
--          최대 1행만 만든다 → 같은 문장 안에서 부분 UNIQUE 를 자기 자신이 밟는 일은 없다.)
--
--     users.status(ACTIVE 여부)는 조건에 넣지 않는다 — 스코프 판정이 ACTIVE 를 별도로 요구하므로
--     비활성 오너에게 멤버십이 있어도 권한이 열리지 않고, 나중에 계정이 활성화되면 정상 동작한다.
-- ─────────────────────────────────────────────────────────────────────────────
insert into company_memberships (company_id, user_id, invited_by, status, approved_at)
select c.id,
       c.owner_user_id,
       null,
       'APPROVED'::company_membership_status_type,
       now()
from companies c
         join users u on u.id = c.owner_user_id
where c.status = 'APPROVED'::company_status_type
  and u.company_id = c.id
  and not exists (select 1
                  from company_memberships m
                  where m.company_id = c.id
                    and m.user_id = c.owner_user_id)
  and not exists (select 1
                  from company_memberships m2
                  where m2.user_id = c.owner_user_id
                    and m2.status = 'APPROVED'::company_membership_status_type);
