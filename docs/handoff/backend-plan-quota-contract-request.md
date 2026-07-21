# 백엔드 #507 ↔ 프론트 #508 플랜·쿼터 API 계약 조율 요청

> **문서 버전:** v0.2
> **최종 수정:** 2026-07-21
> 대상: `docs/api-contract/contract.md` / `openapi.yaml`에 아직 반영되지 않은 관리자 플랜·쿼터 영역
> v0.1 → v0.2 변경: 스코프 확정(담당자 확인 완료) — Figma 시안의 "Hyundai Motors/TechCorp Inc." 등
> 여러 회사 나열은 **임의 목업 데이터**였고, 실제 요구사항은 **로그인한 관리자 소속 회사 하나** +
> **그 회사에 등록된 멤버(개인 계정)별 쿼터 사용량 목록**이다. v0.1의 "슈퍼관리자가 전체 고객사를
> 본다"는 가정은 폐기. 프론트는 이미 이 스코프로 재작업 완료(아래 3절).

## 결론 요약
백엔드 #507(`GET/PATCH /api/admin/plan`, `/api/admin/plan/history`)의 **"본인 회사 단건"** 스코프 자체는 맞는 방향입니다. 다만 현재 구현은 **회사 전체 집계 1건**만 반환하고, 화면이 필요로 하는 **"회사 소속 멤버별" 목록**(멤버 이름·이메일·개인 사용량, 페이징·검색)은 아직 없습니다. → **완전 신규 API 1개**가 필요합니다.

## 1. 화면이 실제로 필요로 하는 것
Figma node-id 1197-3519 레이아웃(검색창 + KPI 카드 2장 + 표 + 우측 "현재 플랜" 카드 + 페이지네이션)은 유지하되, 표의 각 행 = **다른 회사가 아니라 내 회사에 속한 멤버 한 명**입니다.

| 항목 | 내용 |
|---|---|
| KPI 카드 1 "전체 활성 사용자" | **내 회사**의 활성 멤버 수 |
| KPI 카드 2 "전체 쿼터 사용률" | **내 회사** 멤버들의 사용량 합계 / 회사 플랜의 공용 월 한도 |
| 표 한 행 | 멤버 이름 · 이메일 · 그 멤버의 이번 달 개인 사용량 / 공용 한도(회사 플랜 값 그대로, 모든 행 동일) |
| 검색 | 멤버 이름·이메일 키워드 필터 |
| 우측 "현재 플랜" 카드 | 선택한 멤버가 속한(=내 회사) 플랜 상세. 활성 구독 없으면 안내만 |

## 2. 요청 API (신규)
```
GET /api/admin/plan-quota?page=&size=&keyword=
```
- **인가**: `/api/admin/**` 매처로 ADMIN 강제(SecurityConfig, #507과 동일 원칙). **응답 스코프는 요청자의 회사로 서버가 고정** — 쿼리 파라미터로 회사/사용자 id를 받지 않는다(다른 회사 조회 경로 자체를 없앰, IDOR 원천 차단).
- **응답**:
```json
{
  "success": true,
  "data": {
    "content": [
      { "id": 1, "name": "김민준", "email": "...", "plan": "STANDARD", "quotaUsed": 1450, "quotaLimit": 5000 }
    ],
    "page": 1, "size": 4, "totalElements": 8,
    "stats": { "activeUsers": 7, "totalQuotaUsagePercent": 100, "companyPlan": "STANDARD" }
  }
}
```
- `plan`/`quotaLimit`은 **회사 단위로 동일한 값**이 모든 행에 반복된다(company_memberships 상속 — #507 스코프와 일치). `quotaUsed`만 멤버별로 다르다.
- 활성 구독이 없는 멤버(초대만 되고 미승인 등)는 `plan: null, quotaLimit: null`.
- `stats.companyPlan`(신규, 2026-07-21 확정) — **"현재 플랜" 카드 전용 필드**. 우측 카드는 표의 행 선택과 무관하게 로그인한 관리자(company_id)의 플랜 하나만 고정 표시한다. 값 자체는 각 행의 `plan`과 동일하지만(회사 단위 단일 플랜이므로), 프론트가 "카드는 선택 상태에 의존하지 않는다"는 걸 API 형태로도 드러내기 위해 목록과 분리된 필드로 요청. 활성 구독 없으면 `null`.

## 3. 프론트 측 반영 완료
- `frontend/src/features/admin/planQuota.types.ts`, `mocks/planQuotaUsers.mock.ts`, `pages/PlanQuotaPage.tsx` — 단일 회사·멤버 목록 스코프로 주석·목 데이터 수정 완료.
- **삭제**: v0.1에서 지적했던 "전체 고객사 목록 + 업그레이드 문의 전체 승인/반려"(`AdminPlansQuotaPage.tsx`, `adminPlanApi.ts`, `planTypes.ts` 등) — 어느 라우트에도 연결 안 된 죽은 코드였고 스코프도 이번에 폐기된 가정(슈퍼관리자 콘솔)에 기반해 통째로 제거.
- 현재 라우팅된 `PlanQuotaPage.tsx`(`/admin/plans-quota`)만 유지, `GET /admin/plan-quota` 하나만 호출.

## 4. 남은 확인 사항 (담당자 확인 완료)
- 업그레이드 문의(`POST /api/me/plan/upgrade-inquiry`로 접수) **승인·반려는 플랫폼 관리자**(HajaCheck 운영 측, 회사 관리자가 아님)가 처리한다 — 확정. 즉 #508(이 화면, 회사 관리자·자기 회사 스코프)의 책임이 아니며, **별도 플랫폼 관리자 전용 화면/API로 분리**해야 한다.
  - 관련 기존 이슈 확인: **#210**("마이페이지 멤버십 후속 하드닝")이 "upgrade-inquiry 회사 승인 게이팅"을 언급하지만, 이건 회사 가입 승인(`company.status=PENDING_REVIEW`) 게이팅 얘기이지 **플랫폼 관리자가 업그레이드 문의를 승인하는 기능 자체**는 다루지 않음 — 중복 아님, 신규 이슈 필요.
  - v0.1에서 삭제한 `UpgradeInquiryPanel`/`useResolveUpgradeInquiry` 등은 이 플랫폼 관리자 화면을 만들 때 참고용으로 재사용 가능(다만 스코프를 "전체 고객사"로 다시 여는 것이므로 그 화면에서는 맞는 설계였음 — #508이 아니라 별도 티켓으로).
- 위 1~3 확정되면 `docs/api-contract/openapi.yaml`(`info.version` bump)과 `contract.md`에 `GET /api/admin/plan-quota` 반영 — **A(메타)만 수정**.
