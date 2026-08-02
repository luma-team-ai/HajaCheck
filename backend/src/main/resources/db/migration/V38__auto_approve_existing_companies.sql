-- Flyway V38 — 기존 기업 계정 소급 자동승인 + 오너 멤버십 소급 발급 (#1324)
--
-- 배경:
--   #1324 부터 신규 가입은 같은 트랜잭션에서 회사를 VERIFIED + APPROVED 로 만들고 오너의 APPROVED
--   멤버십을 함께 발급한다(CompanyAccountWriter.createAccount). 그 이전에 가입한 회사들은
--   status=PENDING_REVIEW / verification_status=PENDING 이고 company_memberships 행도 없어서
--   회사 스코프 기능(점검 생성·담당자 배정)이 영구히 닫혀 있다.
--
--   회사 스코프 판정(CompanyMembershipRepository.existsEffectiveApprovedMembership 및 동일 불변식의
--   DB 트리거 trg_inspections_check_assigned_inspector_company — 그 트리거가 호출하는 함수명은
--   check_inspection_assigned_inspector_company 다)은 아래 3+1 조건을 **모두** 요구한다:
--     ① companies.status = 'APPROVED'
--     ② companies.verification_status = 'VERIFIED'
--     ③ 오너의 company_memberships 유효 APPROVED 행(approved_at not null · revoked_at null · 미만료)
--     ④ users.company_id = company_memberships.company_id 이고 users.status = 'ACTIVE'
--   그래서 여기서 ①②③(+④는 null 인 경우에만 보완)을 함께 채운다. 하나라도 빠지면 소급이 무의미하다.
--
-- ⚠️ 대상에서 제외하는 집합 (소급 승인의 보안 경계):
--   · status = 'REJECTED'          — 명시적으로 반려된 이력을 덮지 않는다.
--   · verification_status = 'FAILED' — **국세청이 미등록/불일치/휴업/폐업을 확정 응답한 회사**다.
--     FAILED 는 가입 시점에는 생기지 않는다(가입 경로의 확정 불량은 CompanySignupService
--     .isVerificationBlocked 가 가입 자체를 차단한다). 이 값은 가입 이후 #888 재검증 배치
--     (PendingBusinessReverifyScheduler, 매일 05:30 KST, 기본 enabled=true)가 국세청 확정 응답을
--     받았을 때만 찍는다 → 그래서 DB 에는 (PENDING_REVIEW, FAILED) 조합이 실제로 존재할 수 있다.
--     이미 탐지·격리된 불량 사업자를 소급 승인이 되살려 회사 스코프를 열어주면 안 된다.
--     ("진위확인 결과와 무관하게 전면 자동승인"이라는 운영 결정은 *확인하지 못한*(PENDING) 회사를
--      승인한다는 뜻이지, *불량으로 확정된*(FAILED) 회사까지 승인한다는 뜻이 아니다.)
--
-- 스키마 변경 없음 — 데이터 전이(UPDATE/INSERT)만 한다. 따라서 캐노니컬 DDL
-- (docs/design/db/HajaCheck_script.sql) 갱신 대상이 아니고 ddl-auto=validate 에도 영향이 없다.
--
-- 멱등성: 다섯 문장 모두 WHERE 로 "아직 안 된 행"만 고른다 → 재실행하면 대상 0행(Flyway 는 한 번만
-- 실행하지만, prod baseline 스탬프 사고 이력이 있어 재실행 안전성을 명시적으로 확보한다).
--
-- 운영 트래픽을 무기한 대기시키지 않는다(V7·V36 과 동일 방침).
set local lock_timeout = '5s';

-- ─────────────────────────────────────────────────────────────────────────────
-- (1) PENDING_REVIEW 회사 → APPROVED (단, 국세청 확정 불량 FAILED 는 제외)
--     reviewed_by 는 명시적으로 null 로 덮는다 = "사람 심사자 없음(시스템 자동승인)".
--     컬럼은 nullable 이고, PENDING_REVIEW 인데 reviewed_by 가 남아 있는 행(반려 후 재심사 등)이
--     있어도 "이번 승인은 사람이 하지 않았다"가 사실이어야 하므로 값을 남겨두지 않는다.
--     ⚠️ REJECTED 는 대상에서 제외한다 — 명시적으로 반려된 이력을 소급 승인으로 덮지 않는다.
--     ⚠️ FAILED 도 제외한다 — 위 "대상에서 제외하는 집합" 참조(#888 배치가 확정한 불량 사업자).
--     ⚠️ 이미 APPROVED 인 회사는 reviewed_at/reviewed_by 를 건드리지 않는다(기존 심사 이력 보존).
-- ─────────────────────────────────────────────────────────────────────────────
update companies
set status      = 'APPROVED'::company_status_type,
    reviewed_at = now(),
    reviewed_by = null
where status = 'PENDING_REVIEW'::company_status_type
  and verification_status <> 'FAILED'::business_verification_status_type;

-- ─────────────────────────────────────────────────────────────────────────────
-- (2) 레거시 VERIFIED 스탬프 — "#1324 이전에 진짜 국세청 검증을 통과한 회사"를 표시한다.
--
--     왜 필요한가: 아래 (3) 이 소급 승인분에 ntsOutcome='UNKNOWN_BACKFILL' 을 남기지만, 그것만으로는
--     **ntsOutcome 키가 없는 행**이 두 종류로 섞인 채 남는다:
--       ⓐ #1324 이전에 가입해 국세청 검증을 실제로 통과한 APPROVED+VERIFIED 회사 (= 진짜 검증됨)
--       ⓑ 그 밖의 이유로 키가 붙지 않은 행
--     사용자 대면 "사업자 인증 완료" 배지(Company#isNtsVerified)는 "키 부재 = 증명 불가 = false" 로
--     판정하므로(fail-safe), ⓐ를 구분해 두지 않으면 **진짜 검증된 기존 회사의 배지가 거짓으로 꺼진다**
--     (자동승인분에 배지를 잘못 켜는 것과 정확히 반대 방향의 허위 표시).
--
--     "레거시 VERIFIED = 진짜 검증"이라고 보는 근거: #1324 이전에 verification_status 가 VERIFIED 가
--     되는 **애플리케이션 경로는 두 개뿐**이었다 — 가입 시 국세청 성공(CompanyAccountWriter 의
--     businessVerified 분기)과 #888 재검증 성공(PendingBusinessReverifyWriter.markVerified).
--     둘 다 국세청이 실제로 확인해 준 경우다. (#1324 부터 무조건 VERIFIED 를 찍는 경로가 생겼지만,
--     그 경로로 저장된 행은 애플리케이션이 ntsOutcome 을 함께 남기므로 아래 `is null` 에 걸리지 않는다.)
--
--     ⚠️ 단, **수동 SQL 편집분은 구분할 수 없다** — approve() 는 호출부가 0건인데 prod 에는 APPROVED
--     행이 존재하므로 그 DB 는 수동 편집을 겪었다. 그런 행에도 이 스탬프가 붙지만, 그 행들은 이 PR
--     이전에도 이미 배지가 켜져 있었으므로(당시 판정 근거가 verification_status 자체였다) 여기서는
--     **기존 배지 동작을 보존하는 쪽**을 택한 것이다. 새로 허위 표시를 만드는 게 아니다.
--
--     ⚠️ 실행 순서: 이 문장은 반드시 **(3) 의 UNKNOWN_BACKFILL 병합보다 먼저** 와야 한다. (3) 이 먼저
--     돌면 소급분에 ntsOutcome 이 이미 생겨 이 문장의 `is null` 조건에서 빠지므로 결과 자체는 같지만,
--     "레거시 판별 → 소급 표식" 순서가 코드로 드러나야 나중에 조건을 손댈 때 두 집합이 섞이지 않는다.
--
--     status 로 스코프하지 않는 이유: 이건 승인이 아니라 **사실 기록**이다. REJECTED 회사라도 국세청
--     검증을 통과했던 것은 사실이고, 그 기록이 있어야 아래 감사 쿼리가 정확해진다. 권한은 열리지 않는다
--     (스코프 판정은 status='APPROVED' 를 따로 요구한다).
-- ─────────────────────────────────────────────────────────────────────────────
update companies
set business_registration_ocr_raw =
        coalesce(business_registration_ocr_raw, '{}'::jsonb)
            || jsonb_build_object(
                   'ntsOutcome', 'LEGACY_VERIFIED',
                   'ntsBackfilledAt', to_char(now() at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'))
where verification_status = 'VERIFIED'::business_verification_status_type
  and business_registration_ocr_raw->>'ntsOutcome' is null;

-- ─────────────────────────────────────────────────────────────────────────────
-- (3) APPROVED 회사 중 **PENDING**(= 확인하지 못함) 인 진위확인 상태 → VERIFIED
--     대상을 status='APPROVED' 로 스코프한 이유: (1) 직후이므로 여기엔 "이번에 승인된 회사 +
--     원래부터 APPROVED 였던 회사"만 들어오고 **REJECTED 는 자동으로 제외**된다.
--     (status 를 안 보고 verification_status 만으로 걸면 반려 회사까지 VERIFIED 로 만들어 버린다.)
--
--     조건을 `<> 'VERIFIED'` 가 아니라 **`= 'PENDING'`** 으로 쓴 이유:
--       business_verification_status_type enum 은 PENDING/VERIFIED/FAILED 3개뿐이라 "FAILED 제외"와
--       "PENDING 한정"은 결과가 같지만, 후자는 **의도가 값 자체로 드러난다** — "확인하지 못한 것만
--       승격한다". 나중에 enum 라벨이 추가되어도 새 라벨이 자동으로 승격 대상이 되지 않는다
--       (`<>` 는 미래의 모든 신규 라벨을 조용히 포함해 버린다 = fail-open, 여기서는 위험한 기본값).
--     (1) 에서 FAILED 를 제외했더라도 이 조건이 따로 필요하다 — "원래 APPROVED 였는데 그 뒤 #888
--     배치가 FAILED 를 확정한" 행은 (1) 의 대상이 아니어서 여기로 그대로 흘러들기 때문이다.
--
--     verified_at 은 이미 값이 있으면 보존한다(최초 검증 성공 시각의 의미 — Company#verifiedAt javadoc).
--
--     ⚠️ provenance 기록: 이 UPDATE 가 만드는 VERIFIED 는 **국세청이 확인해 준 값이 아니라 이
--     마이그레이션이 만들어 낸 값**이다. 그 사실을 남기지 않으면 배포 후에는 "진짜 검증된 회사"와
--     구분할 방법이 영원히 사라진다(companies 행이 완전히 동일해진다). 감사·재처리용으로 설계된
--     기존 jsonb 컬럼(business_registration_ocr_raw)에 표식을 병합한다 — 스키마 변경 없음.
--       · `||` 는 기존 키를 보존하며 병합한다(예: {"source":"MANUAL_INPUT"} 유지).
--       · 컬럼이 null 인 행이 있으므로 coalesce 로 '{}' 를 깐다(null || x = null 방지).
--       · 신규 가입 경로는 같은 컬럼에 실제 국세청 결과(ntsOutcome=VERIFIED|SKIPPED|…)를 남긴다
--         (CompanySignupService) → 두 경로가 같은 키를 써서 하나의 쿼리로 집계된다:
--           select count(*) from companies
--            where business_registration_ocr_raw->>'ntsOutcome'
--                  not in ('VERIFIED', 'LEGACY_VERIFIED')
--               or business_registration_ocr_raw->>'ntsOutcome' is null;
--         ⚠️ 이 카운트의 의미는 "미검증 회사 수"가 아니라 **"국세청 검증을 증명할 수 없는 회사 수"**다.
--         SKIPPED(장애·키 미설정으로 확인 못 함)와 UNKNOWN_BACKFILL(V38 소급, 확인한 적 없음)은
--         "불량으로 확정된 것"이 아니다 — 확정 불량은 FAILED 이고 애초에 승인 대상에서 제외된다.
--         (2) 의 LEGACY_VERIFIED 스탬프가 들어가면 레거시 진짜 검증분이 이 집계에서 빠진다.
-- ─────────────────────────────────────────────────────────────────────────────
update companies
set verification_status         = 'VERIFIED'::business_verification_status_type,
    verified_at                 = coalesce(verified_at, now()),
    business_registration_ocr_raw =
        coalesce(business_registration_ocr_raw, '{}'::jsonb)
            || jsonb_build_object(
                   'ntsOutcome', 'UNKNOWN_BACKFILL',
                   'ntsBackfilledAt', to_char(now() at time zone 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'))
where status = 'APPROVED'::company_status_type
  and verification_status = 'PENDING'::business_verification_status_type;

-- ─────────────────────────────────────────────────────────────────────────────
-- (4) 오너의 users.company_id 포인터 보완 (④ 조건)
--     멤버십 쿼리·DB 트리거가 users.company_id = memberships.company_id 를 요구한다.
--     ⚠️ company_id 가 **null 인 경우에만** 채운다 — 이미 다른 회사를 가리키는 포인터는 덮지 않는다
--     (오너가 다른 회사로 옮겨간 데이터를 소급 마이그레이션이 되돌리면 그게 더 큰 사고다).
--     ⚠️ FAILED(국세청 확정 불량) 회사는 (3) 에서 VERIFIED 로 올라가지 않으므로 여기서도 제외된다 —
--     확정 불량 회사에 소속 포인터를 새로 배선하지 않는다(방어 심층화).
--     ⚠️ companies.owner_user_id 에는 UNIQUE 가 없다(V1: 비유니크 idx_companies_owner). 한 사용자가
--     둘 이상의 회사를 소유하면 UPDATE ... FROM 이 어느 회사를 고를지 비결정적이므로, 그런 사용자는
--     **아예 건드리지 않는다** — 어느 회사에 소속시킬지는 데이터 소유자가 정할 문제다.
-- ─────────────────────────────────────────────────────────────────────────────
update users u
set company_id = c.id
from companies c
where c.owner_user_id = u.id
  and c.status = 'APPROVED'::company_status_type
  and c.verification_status = 'VERIFIED'::business_verification_status_type
  and u.company_id is null
  and not exists (select 1
                  from companies other
                  where other.owner_user_id = u.id
                    and other.id <> c.id);

-- ─────────────────────────────────────────────────────────────────────────────
-- (5) 오너 APPROVED 멤버십 소급 발급 (③ 조건)
--     invited_by = null — 오너의 최초 멤버십은 초대자가 없다(V1 컬럼 주석과 정합).
--     approved_at = now() — check 제약 ck_company_memberships_approved_at
--     (status='APPROVED' 이면 approved_at not null)을 만족시킨다.
--     expires_at = null(기본) — 오너 멤버십은 명시적 만료가 없다. ck_company_memberships_expiry 통과.
--
--     대상 조건과 그 근거:
--       · c.status='APPROVED' AND c.verification_status='VERIFIED' — 반려 회사와 국세청 확정 불량
--         (FAILED) 회사에는 멤버십을 만들지 않는다. 스코프 판정이 VERIFIED 도 요구하므로 FAILED 회사에
--         멤버십만 심어두면 아무 권한도 안 열리지만, 나중에 누군가 verification 을 손대는 순간
--         **즉시 스코프가 열리는** 지뢰가 된다 — 애초에 만들지 않는다.
--       · u.company_id = c.id — 포인터가 이 회사를 가리키는 오너만. (4)에서 null 은 이미 보완됐고,
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
  and c.verification_status = 'VERIFIED'::business_verification_status_type
  and u.company_id = c.id
  and not exists (select 1
                  from company_memberships m
                  where m.company_id = c.id
                    and m.user_id = c.owner_user_id)
  and not exists (select 1
                  from company_memberships m2
                  where m2.user_id = c.owner_user_id
                    and m2.status = 'APPROVED'::company_membership_status_type);
