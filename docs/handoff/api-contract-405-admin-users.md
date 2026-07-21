# API 계약 반영 요청 — GET/POST/PATCH /api/admin/users (#405)

> 이 문서는 `docs/api-contract/contract.md`(별도 담당자 관리)에 반영해달라는 요청 초안이다.
> `contract.md`/`openapi.yaml`은 직접 수정하지 않았다 — 아래 내용을 그대로 옮기거나 필요에 맞게 조정해서 반영해주면 된다.

- 원 이슈: #405 (frontend #378 대응)
- 구현 완료: `AdminUserController`/`AdminUserService`/`AdminUserRepository` (`backend/src/main/java/com/hajacheck/admin/**`)
- 테스트: `AdminUserControllerTest`, `AdminUserRepositoryTest`
- **2026-07-21 갱신**: 최초 설계는 전사(全社) 조회였으나, 이 화면이 "기업 관리자 콘솔"로 확정되면서 **요청 관리자의 `companyId` 소속 사용자만** 조회·등록·변경 가능하도록 전체 엔드포인트에 회사 스코프를 적용했다(사용자 지시). `companyId`가 없는 관리자(개인 회원 등)는 이 화면 대상이 아니며 `403 FORBIDDEN`. 플랫폼 전체 관리자용 전사 조회는 별도 화면/엔드포인트로 예정(이번 범위 아님).

---

## GET /api/admin/users — 관리자 사용자 목록 조회

**인가**: ADMIN role 전용(SecurityConfig `hasRole("ADMIN")`) + **요청 관리자와 같은 회사(`companyId`) 소속 사용자만** 응답에 포함된다. 프론트 `AdminRoute` 가드는 UX 편의일 뿐 실제 차단·스코프는 이 엔드포인트가 책임진다.

**요청 쿼리 파라미터** (전부 optional, 미지정 시 필터 없음):

| 파라미터 | 타입 | 설명 |
|---|---|---|
| `keyword` | string | 이름·이메일 부분일치(대소문자 무시, LIKE 와일드카드 `%`/`_`는 리터럴로 이스케이프 처리) |
| `role` | `ADMIN\|INSPECTOR\|USER\|COUNSELOR` | 정확히 일치 |
| `plan` | `FREE\|STANDARD\|ENTERPRISE` | 활성 구독(`user_plans.status=ACTIVE`) 기준. 활성 구독이 없는 사용자는 응답상 `FREE`로 표시되므로 `plan=FREE` 필터에도 포함됨 |
| `status` | `ACTIVE\|SUSPENDED` | 정확히 일치 |
| `page` | int (기본 0) | **0-base** (Spring `Pageable` 관례) |
| `size` | int (기본 10, 최대 100) | 초과 요청 시 100으로 clamp |

**성공 200** `data`:
```json
{
  "content": [
    {
      "id": 1,
      "name": "김지수",
      "email": "jisoo.kim@example.com",
      "avatarUrl": null,
      "role": "USER",
      "plan": "FREE",
      "joinedAt": "2023-10-12T00:00:00",
      "lastAccessAt": "2026-07-19T09:00:00Z",
      "status": "ACTIVE"
    }
  ],
  "page": 0,
  "size": 10,
  "totalElements": 42,
  "stats": {
    "totalMembers": 42,
    "active": 39,
    "suspended": 3,
    "newThisWeek": 5,
    "newThisWeekGrowthRate": 25.0
  }
}
```

- `plan`은 활성 구독이 없으면 `FREE`로 표시된다(users 컬럼이 아니라 `user_plans → plans` 조인 결과이며, 조인 결과가 없을 때 응답 매핑 단계에서 `FREE`로 기본값 처리; 사용자당 활성 구독은 `uq_user_plans_active_user` 제약으로 최대 1건).
- `lastAccessAt`은 한 번도 로그인하지 않은 계정이면 `null`.
- `joinedAt`은 `LocalDateTime`(오프셋 없음), `lastAccessAt`은 `Instant`(`Z` 오프셋 포함) — 기존 엔티티 매핑을 그대로 따른 것으로 이번 PR에서 새로 만든 불일치는 아니다.
- `stats`는 **요청 관리자의 회사 소속 사용자 전체 기준**으로 집계한다(계약) — 검색/필터/페이지를 바꿔도 카드 숫자는 고정, 단 다른 회사 사용자는 집계에 포함되지 않는다.
- `newThisWeekGrowthRate`: 달력 주(월요일 시작) 대신 조회 시각 기준 **롤링 7일** 윈도우로 이번 주/직전 주 신규가입을 비교한 증감률(%). 직전 주가 0건이면 이번 주도 0건일 때 0%, 1건 이상이면 100%로 표현(나눗셈 불가 케이스의 이산 정책).

**실패**: `401 UNAUTHORIZED`(미인증) · `403 FORBIDDEN`(인증됐으나 ADMIN 아님, 또는 ADMIN이지만 `companyId`가 없는 계정)

**신규 ErrorCode**: 없음 — 기존 `UNAUTHORIZED`(401)·`FORBIDDEN`(403) 재사용.

---

## POST /api/admin/users — 관리자 사용자 등록

**인가**: GET과 동일(ADMIN 전용 + `companyId` 필수). 등록된 계정은 **요청 관리자와 같은 회사**로 배선된다(요청 바디에 companyId를 받지 않음 — 클라이언트가 임의 회사에 배선하지 못하도록 서버가 강제).

**요청 바디**:

| 필드 | 타입 | 제약 |
|---|---|---|
| `email` | string | 필수, 이메일 형식 |
| `password` | string | 필수, 8자 이상, 영문+숫자 포함 (비밀번호 확인 일치는 클라이언트 검증에서 종료 — 서버로 `password`만 전달, `CompanySignupRequest`와 동일 정책) |
| `name` | string | 필수, 100자 이하 |
| `role` | `ADMIN\|INSPECTOR\|USER` | 필수. **2026-07-21 갱신**: `COUNSELOR`는 이 화면(프론트 `ROLE_CHANGE_OPTIONS`)이 노출하지 않는 역할이라 서버도 화이트리스트로 거부한다(크래프팅된 요청으로도 부여 불가 — 리뷰 P2) |

**성공 201** `data`: `AdminUserResponse`(GET 목록의 개별 항목과 동일 스키마). 방금 생성된 계정이라 `plan`은 항상 `"FREE"` 고정.
```json
{
  "id": 43,
  "name": "홍길동",
  "email": "gildong@example.com",
  "avatarUrl": null,
  "role": "INSPECTOR",
  "plan": "FREE",
  "joinedAt": "2026-07-21T19:00:00",
  "lastAccessAt": null,
  "status": "ACTIVE"
}
```

**실패**: `400 BAD_REQUEST`(검증 실패, 또는 화이트리스트 밖 role `ADMIN_ROLE_NOT_ASSIGNABLE`) · `401 UNAUTHORIZED` · `403 FORBIDDEN` · `409 CONFLICT`(이메일 중복, `AUTH_EMAIL_DUPLICATED` — 저장 전 선검사 + DB unique 제약 경합 시 이중 방어)

**신규 ErrorCode**: `ADMIN_ROLE_NOT_ASSIGNABLE`(400, 리뷰 P2) — 그 외는 기존 `AUTH_EMAIL_DUPLICATED`(409, 회원가입 도메인에서 이미 정의됨) 재사용.

**의도된 동작(정책 확정, 2026-07-21)**: `existsByEmail`은 회사 스코프 없이 전역으로 확인한다. 이메일이 전역 유니크(다른 회사에 등록된 이메일 재사용 불가)라, 다른 회사에 이미 존재하는 이메일로 등록을 시도하면 `409 AUTH_EMAIL_DUPLICATED`가 그대로 반환되어 "해당 이메일이 플랫폼 어딘가에 존재함"이 요청 관리자에게 노출된다(리뷰 P3). 로그인·계정찾기 화면의 계정 비노출 정책과는 다르지만, 이메일 전역 유니크 정책상 등록 차단 자체가 불가피하므로 **응답을 그대로 두기로 결정**했다 — 후속 변경 없음.

---

## PATCH /api/admin/users/{id}/role — 사용자 역할 변경

**인가**: GET과 동일(ADMIN 전용, `companyId` 필수). **대상 사용자가 요청 관리자와 다른 회사 소속이면 `404`**(리소스 존재 여부를 열거하지 않기 위해 403이 아닌 404로 응답 — `FacilityService` 등 기존 cross-owner 패턴과 동일).

**요청 바디**: `{ "role": "ADMIN" | "INSPECTOR" | "USER" }` (`role` 필수, `@NotNull`. **2026-07-21 갱신**: `COUNSELOR` 등 화이트리스트 밖 값은 `400 ADMIN_ROLE_NOT_ASSIGNABLE`)

**성공 200** `data`: `{ "id": 1, "role": "INSPECTOR" }`

**실패**: `400 BAD_REQUEST`(role 누락 또는 화이트리스트 밖 role, `ADMIN_ROLE_NOT_ASSIGNABLE`) · `401 UNAUTHORIZED` · `403 FORBIDDEN` · `404 NOT_FOUND`(대상 사용자 없음 **또는 다른 회사 소속**, `USER_NOT_FOUND`) · `409 CONFLICT`(자기 자신 강등 또는 회사의 마지막 활성 ADMIN 강등, `ADMIN_PROTECTED_ACCOUNT`)

**신규 ErrorCode**: `ADMIN_ROLE_NOT_ASSIGNABLE`(400) · `ADMIN_PROTECTED_ACCOUNT`(409) — 그 외는 기존 `USER_NOT_FOUND`(마이페이지 도메인에서 이미 정의됨) 재사용.

**2026-07-21 갱신 — 자기 자신·마지막 ADMIN 보호(리뷰 P2)**: 대상이 현재 `ADMIN`이고 새 role이 `ADMIN`이 아니면(강등), 다음 중 하나라도 해당하면 `409 ADMIN_PROTECTED_ACCOUNT`로 차단한다 — ① 대상이 요청 관리자 자신, ② 대상을 강등하면 회사의 활성(`ACTIVE`) ADMIN이 0명이 되는 경우(회사 내 활성 ADMIN 수가 이미 1명뿐). 최초 설계 시 "가드 없음"으로 명시했던 항목이나, 되돌릴 수 없는 회사 잠금(availability) 위험으로 이번 PR에서 반영했다.

---

## PATCH /api/admin/users/{id}/status — 사용자 상태 변경

**인가**: 위와 동일(회사 스코프 벗어난 대상은 `404`).

**요청 바디**: `{ "status": "ACTIVE" | "SUSPENDED" }` (`status` 필수, `@NotNull`)

**성공 200** `data`: `{ "id": 1, "status": "SUSPENDED" }`

**실패**: `400 BAD_REQUEST`(status 누락) · `401 UNAUTHORIZED` · `403 FORBIDDEN` · `404 NOT_FOUND`(`USER_NOT_FOUND`, 다른 회사 소속 포함) · `409 CONFLICT`(자기 자신 정지 또는 회사의 마지막 활성 ADMIN 정지, `ADMIN_PROTECTED_ACCOUNT`)

**신규 ErrorCode**: `ADMIN_PROTECTED_ACCOUNT`(409, PATCH role과 공유).

**주의**: 정지 사유(변경 사유 입력) 필드는 프론트 모달에서 제외됐다(사용자 지시) — 운영 로그용 사유 기록은 이번 범위에 없다. **2026-07-21 갱신**: 대상이 `ADMIN`이면서 `SUSPENDED`로 바꾸려는 요청은 role 변경과 동일한 규칙(자기 자신 또는 회사의 마지막 활성 ADMIN이면 `409`)으로 차단한다 — 나머지 role(USER/INSPECTOR)의 정지는 기존대로 가드 없이 허용된다.

---

## 프론트 연동 시 주의 (참고용, contract.md에는 불필요)

프론트 UI 페이지 상태(`AdminUsersPage.tsx`, `TableFooterPagination`)는 1-base 관례를 쓰므로, `frontend/src/features/admin/api/adminApi.ts`에서 `page - 1`로 변환해 전송하도록 이미 맞춰뒀다. `contract.md`에 반영할 때 "0-base"라는 점만 명확히 남겨두면 향후 다른 프론트 화면에서 재사용할 때 같은 실수를 막을 수 있다.
