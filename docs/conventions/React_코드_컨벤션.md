# hajaCheck — React 코드 컨벤션

> 대상: 프론트엔드 코드를 작성하는 전체 팀원 (메뉴 담당제 — 전원이 화면을 직접 구현)
> 관리: Frontend 리드 (구조·공통 컴포넌트·컨벤션 총괄, 리뷰 시 준수 점검)
> 기준: React 18 + Vite / Axios / React Router
> 연관 문서: PRD_hajaCheck.md §6, SpringBoot_코드_컨벤션.md(API 규약), AI_개발_컨벤션.md(폴백 규약)

---

## 0. 원칙

- 함수형 컴포넌트 + Hooks만 사용 (클래스 컴포넌트 금지)
- API 호출은 공통 axios 인스턴스와 feature별 api 모듈을 통해서만 — 컴포넌트에서 axios 직접 import 금지
- 공통 컴포넌트(Button, Modal, Table 등)를 우선 사용 — 각자 유사 컴포넌트 중복 제작 금지, 필요 시 Frontend 리드와 협의 후 공통에 추가
- 언어: **TypeScript 권장** (포트폴리오 가치·자동완성·API 타입 안정성). 팀 숙련도에 따라 착수 회의에서 JS(JSX) 선택 가능 — 단, 혼용 금지

## 1. 디렉토리 구조 (feature 기반 — 메뉴 담당제와 1:1)

```
src/
├─ app/                      # 앱 진입·전역 설정
│  ├─ App.tsx  router.tsx  main.tsx
├─ shared/                   # 도메인 무관 공통 (Frontend 리드 관리)
│  ├─ api/
│  │  ├─ axios.ts            # 공통 인스턴스 (baseURL, 인터셉터)
│  │  └─ types.ts            # ApiResponse<T> 등 공통 타입
│  ├─ components/            # Button, Modal, Table, Pagination, ErrorFallback...
│  ├─ hooks/                 # useDebounce, usePolling...
│  ├─ utils/  constants/
├─ features/                 # 메뉴(도메인)별 — 담당자 소유 영역
│  ├─ auth/                  # 로그인·마이페이지
│  ├─ dashboard/             # 대시보드 (+AI 브리핑)
│  ├─ facility/              # 시설물 관리
│  ├─ inspection/            # 점검 A(업로드·분석) + B(뷰어·검수)
│  ├─ defect/                # 하자 관리 (+자연어 검색)
│  ├─ report/                # 보고서
│  ├─ support/               # 고객지원 (RAG 챗봇·상담)
│  └─ admin/                 # 관리자 (+모니터링)
│     └─ 각 feature 내부: components/  hooks/  api/  types.ts
└─ styles/                   # 전역 스타일·테마
```

- feature 간 직접 import 금지 — 공유가 필요해지면 `shared/`로 승격 (Frontend 리드 협의)
- `shared/` 수정은 Frontend 리드 리뷰 필수

## 2. 네이밍

| 대상 | 규칙 | 예 |
|---|---|---|
| 컴포넌트 파일/함수 | PascalCase | `DefectDetailPanel.tsx` |
| 훅 | `use` 접두 camelCase | `useDefectList.ts` |
| api 모듈 | `{도메인}Api.ts` | `defectApi.ts` |
| 유틸/일반 파일 | camelCase | `formatDate.ts` |
| 상수 | UPPER_SNAKE_CASE | `MAX_UPLOAD_SIZE` |
| 이벤트 핸들러 | `handle{대상}{동작}` / prop은 `on{동작}` | `handleGradeChange` / `onSelect` |
| boolean | `is/has/can` 접두 | `isLoading`, `hasError` |

- 페이지 컴포넌트는 `~Page` 접미: `DefectListPage.tsx` (라우트 대상 식별)

## 3. API 레이어

- `shared/api/axios.ts` 단일 인스턴스: `baseURL=/api`, `withCredentials: true`(세션 쿠키), 응답 인터셉터에서 공통 envelope 해제 및 에러 정규화
- 백엔드 envelope(`{ success, data, error }`)은 인터셉터에서 처리 — 컴포넌트/훅은 `data`만 다루고, 실패는 `error.code` 기반 처리

```ts
// features/defect/api/defectApi.ts — feature별 api 모듈 예
export const defectApi = {
  getList: (params: DefectListParams) => api.get<DefectListResponse>('/defects', { params }),
  updateStatus: (id: number, body: StatusUpdateRequest) => api.patch(`/defects/${id}/status`, body),
};
```

- 401 응답 → 인터셉터에서 로그인 페이지 리다이렉트 일괄 처리
- 분석 잡 등 폴링은 공통 훅 `usePolling` 사용 (각자 setInterval 구현 금지)

## 4. 상태 관리

- **서버 상태: TanStack Query(React Query)** — 목록·상세 조회, 캐싱, 분석 잡 폴링(`refetchInterval`)에 사용. 서버 데이터를 useState에 복사해 들고 다니지 않는다
- **전역 클라이언트 상태: Zustand** — 로그인 사용자 정보, 전역 UI 상태(사이드바 등) 최소한만
- **지역 상태: useState/useReducer** — 폼 입력, 모달 열림 등 컴포넌트 내부용
- 판단 순서: 지역 상태로 충분한가 → 서버 상태인가(Query) → 정말 전역인가(Zustand)

## 5. 컴포넌트 작성 규칙

- 한 파일 = 한 컴포넌트, 200라인 초과 시 분리 검토
- Props는 타입 정의 필수(TS interface / JS는 JSDoc), `props` 통째로 하위 전달(drilling) 3단계 초과 시 구조 재검토
- 조건부 렌더링: 로딩(`isLoading`) → 에러(`isError`) → 빈 데이터(`empty`) → 정상, 4상태를 항상 처리
- 목록 key에 index 사용 금지 (서버 id 사용)
- `useEffect`는 최소화 — 데이터 fetching은 Query로, 파생값은 렌더 중 계산으로

## 6. AI 기능 UI 규약 (AI_개발_컨벤션.md §5 연동)

- AI 응답 대기: 공통 `AILoadingIndicator` 사용 (스켈레톤/스피너 통일)
- AI 실패 폴백: 공통 `AIErrorFallback` — 표준 문구 "AI 분석을 불러올 수 없습니다. 잠시 후 다시 시도해 주세요." + 재시도 버튼. **AI 실패가 화면의 비-AI 기능을 막지 않아야 함** (예: 브리핑 실패해도 대시보드 통계는 정상 표시)
- 장시간 작업(보고서 생성)은 잡 폴링 + 진행률 표시, 완료 시 알림

## 7. 라우팅

- React Router, 라우트 정의는 `app/router.tsx` 한 곳에 집중
- 경로: kebab-case — `/inspections/:id/viewer`, `/admin/monitoring`
- 인증 가드: `ProtectedRoute`(로그인), `AdminRoute`(관리자) 공통 컴포넌트로 처리
- 페이지 lazy loading(`React.lazy`) 기본 적용

## 8. 스타일

- 방식은 착수 회의에서 택일 (권장: **Tailwind CSS** — Stitch 시안 → 구현 속도 이점). 결정 후 혼용 금지
- 색상·간격 등 디자인 토큰은 테마 파일(또는 tailwind.config)에서만 정의 — 컴포넌트에 hex 하드코딩 금지
- 반응형은 데스크톱 우선 (점검 실무는 PC 사용 전제), 시연 화면 해상도(1920×1080) 기준 확인

## 9. WebSocket (상담)

- STOMP 클라이언트(`@stomp/stompjs`) 연결 관리는 `features/support/hooks/useCounselSocket.ts` 단일 훅으로 캡슐화 — 컴포넌트에서 직접 connect 금지
- 재연결 정책(지수 백오프), 연결 상태 표시(연결 중/끊김) UI 필수

## 10. 환경 변수

- `import.meta.env.VITE_*`만 사용, `.env.local`(gitignore) / `.env.production`
- API 주소 등 환경 의존 값 하드코딩 금지. 시크릿은 프론트에 두지 않음 (빌드 산출물에 노출됨)

## 11. 린트·포맷

- ESLint + Prettier 설정 파일 저장소 커밋 — 저장 시 자동 포맷 (규칙 논쟁 금지, 설정이 곧 규칙)
- CI에서 `lint` 통과 필수 (GitHub Actions)
- console.log 커밋 금지 (`no-console`, 개발 중은 warn 허용)

## 12. Git / PR

- SpringBoot_코드_컨벤션.md §12와 동일 (브랜치·커밋 타입·PR 규칙 공통)
- 화면 변경 PR은 스크린샷 또는 짧은 GIF 첨부 필수

## 13. 리뷰 체크리스트 (리뷰어용)

- [ ] feature 간 직접 import 없음 (공유는 shared 승격)
- [ ] axios 직접 사용 없음 (공통 인스턴스 + feature api 모듈)
- [ ] 서버 상태를 useState로 복제하지 않음 (Query 사용)
- [ ] 로딩/에러/빈 데이터/정상 4상태 처리
- [ ] AI 폴백 규약 준수 (실패가 다른 기능을 막지 않음)
- [ ] 하드코딩(색상, API 주소, 매직넘버) 없음
- [ ] lint 통과, console.log 없음
