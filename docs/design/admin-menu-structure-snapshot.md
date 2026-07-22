# 관리자 메뉴 구성 스냅샷 (기업 관리자 ↔ 플랫폼 관리자 분리 전)

> **문서 버전:** v0.1
> **최종 수정:** 2026-07-22
> 대상: `frontend/src/shared/components/SideNavBar/SideNavBar.tsx`의 `DEFAULT_ADMIN_ITEM.subItems`

## 배경

관리자 역할이 **기업 관리자**(자기 회사 스코프)와 **플랫폼 관리자**(hajaCheck 운영 측, 전사 스코프)로
나뉜다. 지금까지는 이 구분 없이 `관리자 페이지` 메뉴 하나에 7개 항목이 전부 걸려 있었고, 그중
실제 구현된 라우트는 **사용자 관리**·**플랜·쿼터 관리** 2개뿐이었다(`app/implementedRoutes.ts` 기준,
나머지는 `isRouteImplemented` 가드가 "아직 구현되지 않은 페이지입니다" 토스트로 막던 placeholder).

플랫폼 관리자 기능을 새로 구현하기 전에, 기업 관리자 메뉴를 실제 구현된 2개 항목만 남기고 정리한다
(2026-07-22, PR #507 팔로우업). 아래는 정리 **전** 원본 구성 — 플랫폼 관리자 메뉴 설계 시 참고용.

## 원본 구성 (정리 전, 7개 항목)

```ts
const DEFAULT_ADMIN_ITEM: SideNavItem = {
  label: '관리자 페이지',
  href: '/admin',
  icon: adminIcon,
  subItems: [
    { label: '사용자 관리', href: '/admin/users' },
    { label: '플랜·쿼터 관리', href: '/admin/plans-quota' },
    { label: '하자 유형·등급 관리', href: '/admin/defect-types' },
    { label: '상담 관리', href: '/admin/counsels' },
    { label: 'RAG 문서 관리', href: '/admin/rag-documents' },
    { label: '서비스 통계', href: '/admin/stats' },
    { label: '시스템 모니터링', href: '/admin/monitoring' },
  ],
};
```

## 정리 후 (기업 관리자 메뉴, 2개 항목만 유지)

- `사용자 관리` (`/admin/users`) — 구현 완료(#405/#506)
- `플랜·쿼터 관리` (`/admin/plans-quota`) — 구현 완료(#507/#508)

## 플랫폼 관리자 메뉴 후보 (미구현 5개, 스코프 재검토 필요)

아래 5개는 라우트/페이지 자체가 없던 placeholder였다 — 플랫폼 관리자 기능을 새로 설계할 때 이 라벨을
그대로 재사용할지, 스코프를 다시 정의할지 검토가 필요하다.

- `하자 유형·등급 관리` (기존 `/admin/defect-types`)
- `상담 관리` (기존 `/admin/counsels`)
- `RAG 문서 관리` (기존 `/admin/rag-documents`)
- `서비스 통계` (기존 `/admin/stats`)
- `시스템 모니터링` (기존 `/admin/monitoring`)

참고: `docs/handoff/backend-plan-quota-contract-request.md`가 언급한 "업그레이드 문의 승인/반려"
(플랫폼 관리자 책임으로 확정, 별도 이슈 #511/HAJA-305)도 플랫폼 관리자 메뉴에 포함될 후보다 — 위
7개 원본 목록에는 없던 신규 항목.
