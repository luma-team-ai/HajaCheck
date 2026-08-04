# GradeBadge 공통화 제안 (1차)

> **문서 버전:** v0.1 · **최종 수정:** 2026-07-27

| 항목 | 내용 |
|---|---|
| 배경 | 김관영 요청 — 통계 페이지 Figma 구현 후 허남과 정합성 검토 과정에서 등급 표시 공통화 선행의견, #952/#960(ResultViewer 등급 라디오 색상 수정) 이력 근거로 1차 담당=오영석 |
| 작성 | 오영석 |
| 상태 | 제안 — 팀 확인 전, 코드 변경 없음 |

---

## 1. 현황 조사 (최근 커밋 기준)

등급(A~E) 표시 컴포넌트가 feature마다 독립적으로 존재한다.

| 위치 | 형태 | grade prop | 색상 소스 | 알 수 없는 등급 |
|---|---|---|---|---|
| `dashboard/components/GradeBadge.tsx` | 원형(28px) solid | `DefectGrade \| null` | `dashboard/colors.ts` GRADE_BG_CLASS | "등급 미분류" |
| `map/components/GradeBadge.tsx` | 알약형 solid | `DefectGrade \| null` | `map/constants.ts` GRADE_COLOR | "등급 미정" |
| `facility/components/FacilityGradeBadge.tsx` | 알약형 soft(연한배경) + `withChevron` 옵션 | `FacilityDefectGrade \| null` | `facility/facilityDefectColors.ts` | "-" |
| `report/components/ReportGradeBadges.tsx` | 분포(등급별 개수 다중 배지) soft | `ReportGradeDistribution` (단일 grade 아님) | 로컬 `GRADE_CLASSES` ⚠️ | 개수 0 → "-" |
| `inspection/pages/ResultViewerPage.tsx` | 컴포넌트 없음, 라디오 옆 인라인 색상 점 | `DefectGrade` (인라인) | 로컬 `GRADE_DOT_COLORS` | 해당없음 |

색상 소스는 이 위 5곳 외에 `shared/components/charts/palette.ts`의 `CHART_GRADE_COLORS`(A=#16a34a, B=#65a30d, C=#eab308, D=#f97316, E=#dc2626)가 이미 존재하고, dashboard/map/facility/chart/ResultViewer **5곳이 이 값과 일치**한다(ResultViewer는 #960에서 이 값으로 막 정정됨).

### ⚠️ 발견: `report/ReportGradeBadges.tsx`만 색상 방향이 반대

```ts
// report/components/ReportGradeBadges.tsx
const GRADE_CLASSES: Record<ReportGrade, string> = {
  A: 'bg-red-50 text-red-600',      // A가 위험색
  B: 'bg-orange-50 text-orange-600',
  C: 'bg-yellow-50 text-amber-700',
  D: 'bg-green-50 text-green-600',
  E: 'bg-emerald-50 text-emerald-600', // E가 안전색 — 표준과 정반대
};
```

같은 커밋(PR #943, `0cfae8dd`)에서 `report/types.ts`에 추가된 주석은 정반대로 적혀 있다:

> "등급 색상은 Figma 시안(A=빨강...D=초록)이 아니라 SOT 등급 의미(A=양호/E=중대)를 따른다 — 2026-07-21 Figma 등급 색상 3곳 불일치 감사에서 Figma가 아니라 SOT 설계문서가 기준으로 확정됐다."

즉 "Figma 대신 SOT를 따르기로 확정했다"고 문서화해놓고, 실제 구현은 되돌린 Figma 안 그대로 들어간 자체 모순. `docs/conventions/하자_심각도_등급_규칙.md`(정재봉 작성, SOT)도 **A=경미(우수)→E=심각(불량)** 방향이라 이 스펙과 정면으로 충돌한다.

> 이슈 #832(defect/map vs statistics 라벨 **워딩** 불일치 — "양호" vs "경미")와는 별개 건이다. #832는 방향은 같고 단어만 다른 문제라 정재봉 확인 대기 중이지만, 이 건은 방향 자체가 SOT 문서와 반대라 별도 확인 없이 표준 팔레트로 정정 가능한 사안으로 판단한다.

## 2. 제안 A — 색상 토큰 단일화 (선행 필수)

- 이미 4~5곳이 실질적으로 같은 값을 쓰고 있으므로, 새 파일을 만들지 않고 **`shared/components/charts/palette.ts`의 `CHART_GRADE_COLORS`를 canonical source로 채택**한다.
- `ReportGradeBadges.tsx`의 로컬 `GRADE_CLASSES` 삭제 → `CHART_GRADE_COLORS` 참조로 교체 (색상 버그 수정을 겸함).
- `ResultViewerPage.tsx`의 로컬 `GRADE_DOT_COLORS`도 동일 소스 참조로 교체 — 다음 팔레트 변경 시 5곳이 아니라 1곳만 고치면 되게 하여 #957류(팔레트 drift) 재발을 막는다.
- `shared/` 하위 참조라 `React_코드_컨벤션.md` §1 "feature 간 직접 import 금지"에 걸리지 않는다.

## 3. 제안 B — GradeBadge는 "통합"이 아니라 "shared 승격"

`dashboard`/`map`/`facility` 3곳은 구조가 거의 동일(단일 `grade` prop + null 폴백)하다. **이 3개만** `shared/components/GradeBadge.tsx`로 승격한다.

`report`는 목적이 다르다(단일 등급이 아니라 등급별 개수 분포) — 컴포넌트 통합 대상이 아니라 **색상 토큰만 공유**하는 것으로 충분하다.

### Props (제안)

```ts
type GradeBadgeProps = {
  grade: DefectGrade | null;
  /** solid=흰 글자/색 배경(dashboard·map 기존 스타일), soft=연한 배경/색 글자(facility 기존 스타일) */
  variant?: 'solid' | 'soft';
  /** circle=dashboard 28px 원형, pill=map·facility 알약형 */
  shape?: 'circle' | 'pill';
  /** facility 상세 패널 드롭다운 표시용 — 기존 옵션 유지 */
  withChevron?: boolean;
  /** null일 때 문구 — 기본값을 강제하지 않고 호출부가 명시(문맥마다 다른 게 자연스러움) */
  unknownLabel: string;
};
```

기존 3개 호출부는 각자 현재 시각 스타일에 맞는 `variant`/`shape`만 지정하면 되고, 화면에 보이는 결과는 바뀌지 않는다(내부 구현만 통합).

### 등급별 토큰

`CHART_GRADE_COLORS` 그대로 사용(A=#16a34a…E=#dc2626). `soft` variant는 facility가 이미 갖고 있는 밝은 배경 매핑(`facilityDefectColors.ts`)을 재사용한다.

### 기존 ResultViewer와의 호환 범위

ResultViewer는 배지가 아니라 "라디오 버튼 옆 색상 점"이라 형태가 다르므로 `GradeBadge` 컴포넌트로 교체할 필요는 없다고 본다. **색상 값만** `CHART_GRADE_COLORS` 참조로 맞추는 것으로 충분 — 표시 형태(도트 vs 배지) 통일은 이번 범위 밖으로 둔다.

### 알 수 없는 등급의 표현

현재 3곳 텍스트는 다르지만("등급 미분류"/"등급 미정"/"-") 배경색은 이미 동일 계열(#9ca3af)이다. → **색상은 통일**, **텍스트는 `unknownLabel`로 feature가 계속 다르게 지정**할 수 있게 한다(문맥상 다른 문구가 자연스러워 과도한 통일은 지양).

## 3-1. 확정 요청 항목 (관영 2026-07-27 회신 기준)

**영향 화면 (import 기준 확인, 5곳)**

| 화면/파일 | 현재 쓰는 컴포넌트 | 조치 |
|---|---|---|
| `dashboard/components/PendingPriorityCard.tsx` | `dashboard/components/GradeBadge.tsx` | shared 승격 |
| `map/components/FacilityListPanel.tsx` | `map/components/GradeBadge.tsx` | shared 승격 |
| `facility/components/FacilityDefectInfoPanel.tsx`, `facility/components/DefectChangeTable.tsx` | `facility/components/FacilityGradeBadge.tsx` | shared 승격 |
| `report/components/ReportListTable.tsx` | `report/components/ReportGradeBadges.tsx` | 색상 토큰만 교체(구조 유지) |
| `inspection/pages/ResultViewerPage.tsx` | 컴포넌트 없음(인라인 dot) | 색상 토큰만 교체 |

**shared props/API (최종, §3 제안 그대로 확정 요청)**: `shared/components/GradeBadge.tsx`에 `{ grade, variant?: 'solid'|'soft', shape?: 'circle'|'pill', withChevron?, unknownLabel }`.

**토큰 이름**: 새 토큰 파일을 만들지 않고 기존 `shared/components/charts/palette.ts`의 `CHART_GRADE_COLORS`를 canonical source로 그대로 재사용. `soft` variant 밝은 배경은 `facility/facilityDefectColors.ts`의 기존 매핑을 참고해 파생.

**시각 회귀 테스트 범위**: 이 저장소엔 스크린샷 기반 도구(Chromatic/Percy 등)가 없고, 기존에도 RTL로 렌더된 class/style을 직접 assert하는 방식(`ResultViewerPage.test.tsx`의 `backgroundColor` 검증 등)을 써왔다. 같은 패턴으로:
- 갱신 필요(기존 테스트 있음): `dashboard/components/GradeBadge.test.tsx`, `map/components/FacilityListPanel.test.tsx`, `inspection/pages/ResultViewerPage.test.tsx`
- 신규 작성 필요(현재 테스트 없음): `ReportListTable`, `DefectChangeTable`, `FacilityDefectInfoPanel`, `PendingPriorityCard`

## 4. 이번 제안 범위 밖

- 이슈 #832(defect/map vs statistics 라벨 워딩) — 정재봉 확인 전 보류, 이 제안과 무관하게 별도 진행.
- `report`의 분포 배지 구조 자체 변경 — 색상만 정정하고 컴포넌트 모양(개수 표시)은 유지.

## 5. 다음 단계

팀 합의되면: 영향 화면 목록 확정(dashboard·map·facility 호출부 + report 1곳 + ResultViewer 1곳) → 시각 회귀 테스트 범위 산정 → 별도 이슈 등록 후 착수.
