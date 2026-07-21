# API 계약 반영 요청 — GET/PATCH /api/admin/users (#405)

> 이 문서는 `docs/api-contract/contract.md`(별도 담당자 관리)에 반영해달라는 요청 초안이다.
> `contract.md`/`openapi.yaml`은 직접 수정하지 않았다 — 아래 내용을 그대로 옮기거나 필요에 맞게 조정해서 반영해주면 된다.

- 원 이슈: #405 (frontend #378 대응)
- 구현 완료: `AdminUserController`/`AdminUserService`/`AdminUserRepository` (`backend/src/main/java/com/hajacheck/admin/**`)
- 테스트: `AdminUserControllerTest`, `AdminUserRepositoryTest` (`./gradlew test` 통과 확인)

---

## GET /api/admin/users — 관리자 사용자 목록 조회

**인가**: ADMIN role 전용. 세션 인증 O + `role != ADMIN`이면 필터 단계에서 즉시 차단(컨트롤러 진입 전, SecurityConfig `hasRole("ADMIN")`). 프론트 `AdminRoute` 가드는 UX 편의일 뿐 실제 차단은 이 엔드포인트가 책임진다.

**요청 쿼리 파라미터** (전부 optional, 미지정 시 필터 없음):

| 파라미터 | 타입 | 설명 |
|---|---|---|
| `keyword` | string | 이름·이메일 부분일치(대소문자 무시) |
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
- `stats`는 **검색/필터 조건과 무관하게 전체 사용자 기준**으로 집계한다(계약) — 페이지를 넘기거나 필터를 걸어도 카드 숫자는 고정.
- `newThisWeekGrowthRate`: 달력 주(월요일 시작) 대신 조회 시각 기준 **롤링 7일** 윈도우로 이번 주/직전 주 신규가입을 비교한 증감률(%). 직전 주가 0건이면 이번 주도 0건일 때 0%, 1건 이상이면 100%로 표현(나눗셈 불가 케이스의 이산 정책).

**실패**: `401 UNAUTHORIZED`(미인증) · `403 FORBIDDEN`(인증됐으나 ADMIN 아님)

**신규 ErrorCode**: 없음 — 기존 `UNAUTHORIZED`(401)·`FORBIDDEN`(403) 재사용.

---

## PATCH /api/admin/users/{id}/role — 사용자 역할 변경

**인가**: GET과 동일(ADMIN 전용, SecurityConfig `hasRole("ADMIN")`).

**요청 바디**: `{ "role": "ADMIN" | "INSPECTOR" | "USER" | "COUNSELOR" }` (`role` 필수, `@NotNull`)

**성공 200** `data`: `{ "id": 1, "role": "INSPECTOR" }`

**실패**: `400 BAD_REQUEST`(role 누락) · `401 UNAUTHORIZED` · `403 FORBIDDEN` · `404 NOT_FOUND`(대상 사용자 없음, `USER_NOT_FOUND`)

**신규 ErrorCode**: 없음 — 기존 `USER_NOT_FOUND`(마이페이지 도메인에서 이미 정의됨) 재사용.

**주의**: 자기 자신의 역할을 변경하거나(관리자 권한 자기 박탈), 마지막 남은 ADMIN을 강등하는 경우에 대한 별도 가드는 이번 범위에 없다 — 필요 시 후속 이슈로 분리.

---

## PATCH /api/admin/users/{id}/status — 사용자 상태 변경

**인가**: 위와 동일.

**요청 바디**: `{ "status": "ACTIVE" | "SUSPENDED" }` (`status` 필수, `@NotNull`)

**성공 200** `data`: `{ "id": 1, "status": "SUSPENDED" }`

**실패**: `400 BAD_REQUEST`(status 누락) · `401 UNAUTHORIZED` · `403 FORBIDDEN` · `404 NOT_FOUND`(`USER_NOT_FOUND`)

**신규 ErrorCode**: 없음.

**주의**: 정지 사유(변경 사유 입력) 필드는 프론트 모달에서 제외됐다(사용자 지시) — 운영 로그용 사유 기록은 이번 범위에 없다. 자기 자신을 정지시키는 경우에 대한 가드도 없다.

---

## 프론트 연동 시 주의 (참고용, contract.md에는 불필요)

프론트 UI 페이지 상태(`AdminUsersPage.tsx`, `TableFooterPagination`)는 1-base 관례를 쓰므로, `frontend/src/features/admin/api/adminApi.ts`에서 `page - 1`로 변환해 전송하도록 이미 맞춰뒀다. `contract.md`에 반영할 때 "0-base"라는 점만 명확히 남겨두면 향후 다른 프론트 화면에서 재사용할 때 같은 실수를 막을 수 있다.
