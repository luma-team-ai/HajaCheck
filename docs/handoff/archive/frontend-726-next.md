# frontend-726-next — 하자 목록·상세 개편 (FE)

## 이슈
- GitHub #726 · Jira HAJA-394
- 선행: #17(HAJA-26, CLOSED) · 연관(중복 확인 필요): #527, #630

## 사이클
**Normal** (sonnet, typescript-reviewer/react-reviewer 1회, cap 1)

## 사전 준비 — Figma
**메인 세션이 3개 노드를 이미 실사 확인 완료**했고 `docs/api-contract/contract.md` §"하자 목록·상세 화면 개편"에 화면 구조·모달 필드가 전부 정리돼 있음 — 먼저 그 문서부터 읽을 것.

⚠️ **이 Figma 팀은 Starter 플랜이라 Dev Mode/MCP 호출이 금방 rate limit에 걸린다**(`get_metadata` 2회 만에 발생 확인). `figma-design-to-code` 스킬로 `get_design_context`를 호출하는 건 **정확한 spacing/색상/토큰 등 픽셀 단위 구현이 실제로 필요한 노드에 한해서만, 아껴서** 사용할 것. 이미 계약 문서에 정리된 필드·구조를 재확인하려고 반복 호출하지 말 것.

1. Figma MCP 인증이 안 돼 있으면 claude.ai 커넥터 설정 또는 `/mcp`에서 연결.
2. 노드: 목록 `node-id=1-2099` · 카드형 상세 `node-id=1547-2912` · 모달 `node-id=1562-3682` (fileKey `0NUC2R7VZ2pAFeqiMjPjZp`)
3. contract.md에 이미 정리됨:
   - 목록(node 1-2099)은 원래 하자 단건 플랫 테이블이지만 **점검 단위로 재해석**해야 함(시각 디자인은 유지)
   - 카드형 상세(node 1547-2912)는 설명과 그대로 일치 — KPI 4종 + 하자 카드 그리드 + 우측 활동기록 사이드바
   - 모달(node 1562-3682)의 "조치 결과 등록" 필드(조치후사진/조치내용/조치일/담당자, 전부 필수)는 contract.md 표에 정리됨 — 그대로 구현
4. rate limit에 안 걸리고 실제로 필요한 경우에만 `get_design_context`로 정확한 spacing/토큰/에셋을 가져올 것. 걸리면 잠시 후 재시도하거나, 이미 정리된 정보만으로 먼저 구현하고 나중에 세부 스타일을 다듬어도 됨.

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
