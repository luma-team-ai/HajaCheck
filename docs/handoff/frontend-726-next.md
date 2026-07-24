# frontend-726-next — 하자 목록·상세 개편 (FE)

## 이슈
- GitHub #726 · Jira HAJA-394
- 선행: #17(HAJA-26, CLOSED) · 연관(중복 확인 필요): #527, #630

## 사이클
**Normal** (sonnet, typescript-reviewer/react-reviewer 1회, cap 1)

## 사전 준비 — Figma (필수, 가장 먼저 할 것)
1. Figma MCP 커넥터 인증 필요. 인증 안 돼 있으면 세션에서 `/mcp` 또는 claude.ai 커넥터 설정에서 Figma를 연결.
2. 인증 후 **`figma-design-to-code` 스킬을 먼저 로드**(`get_design_context` 호출 전 필수)한 뒤 아래 3개 노드를 순서대로 확인:
   - 하자 목록(점검 단위): https://www.figma.com/design/0NUC2R7VZ2pAFeqiMjPjZp/HajaCheck?node-id=1-2099&m=dev
   - 하자 상세(카드형 목록): https://www.figma.com/design/0NUC2R7VZ2pAFeqiMjPjZp/HajaCheck?node-id=1547-2912&m=dev
   - 하자 상세 모달: https://www.figma.com/design/0NUC2R7VZ2pAFeqiMjPjZp/HajaCheck?node-id=1562-3682&m=dev
3. **모달 "작업 내용" 필드셋을 확정하는 게 가장 중요** — 백엔드 세션(`backend/725`)이 이 필드를 기다리고 있음. 확정되면:
   - `docs/api-contract/contract.md` §"하자 목록·상세 화면 개편" TBD 항목을 실제 필드로 채워 갱신(문서 버전 bump 필요 — root v0.8→v0.9 + `archive/contract_v0.8.md` 스냅샷)
   - PR 본문에 `[CONTRACT-CHANGE-REQUEST]` 표시 + 백엔드 워크트리(`../hajacheck-backend-725`)에도 알 수 있게 커밋 메시지에 필드 요약

## 브랜치·워크트리
```
git worktree add ../hajacheck-frontend-726 -b frontend/726-defect-redesign
```

## 개편 대상 (기존 코드)
- `frontend/src/features/defect/pages/DefectListPage.tsx` — 점검 단위 목록으로 재작성
- `frontend/src/features/defect/pages/DefectDetailPage.tsx` — 점검에 속한 하자 카드형 목록으로 재작성 (또는 신규 컴포넌트로 대체하고 라우트만 유지)
- 신규: 하자 상세 풀스크린 모달 컴포넌트 — **사이드바(`AppLayout`)와 헤더(`TopBar`)를 제외한 나머지 전체 영역**을 차지. 기존 `shared/` 레이아웃 컴포넌트 재사용, 별도 fullscreen 오버레이 새로 만들지 말 것
- `frontend/src/features/defect/hooks/useDefects.ts`, `frontend/src/features/defect/api/defectApi.ts` — API 계약(§api-contract.md 참조) 맞춰 갱신
- 파일 업로드: `frontend/src/features/inspection/hooks/useUploadMedia.ts` 패턴 재사용(신규 업로드 훅 만들지 말 것)

## API 계약
`docs/api-contract/contract.md` §"하자 목록·상세 화면 개편 (draft, HAJA-393/394)" 참조. 요약:
- 목록: `GET /api/inspections` (백엔드 신규 구현 대기 — 먼저 MSW mock으로 병행 개발 가능)
- 카드 목록: `GET /api/inspections/{id}/defects` (기존, `DefectDetailItem` 응답)
- 모달 상세: `GET /api/defects/{id}` (`DefectResponse`) + TBD 작업내용 필드(위 "사전 준비" 참고)
- 파일 업로드: `POST /api/inspections/{id}/media`

## 완료 기준
- `npm run build` + `npm test` PASS
- MSW 핸들러(`defectApi.handlers.ts`) 갱신
- 반응형 320~1440 확인, 모달이 사이드바/헤더를 침범하지 않는지 확인
- 단계별 커밋, **push 금지**
- 완료 후 메인 세션(사용자)에게 변경파일 절대경로 + 빌드결과 보고, 대기

## 금지
self-review 호출 / PR 작성·생성 / push / 머지 / STATUS.md 수정
