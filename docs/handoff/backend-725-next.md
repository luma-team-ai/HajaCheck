# backend-725-next — 하자 목록·상세 개편 (BE)

## 이슈
- GitHub #725 · Jira HAJA-393
- 선행: #17(HAJA-26, CLOSED) · 연관(중복 확인 필요): #527, #630

## 사이클
**Normal** (sonnet, code-reviewer 1회, cap 1) — 회사 스코프 인가(IDOR)가 걸린 영역이라 기존 `DefectController`/`DefectRevisionController`의 `loginUser.getCompanyId()` 패턴을 반드시 그대로 유지할 것.

## 브랜치·워크트리
```
git worktree add ../hajacheck-backend-725 -b backend/725-defect-redesign
```

## 구현 범위
`docs/api-contract/contract.md` §"하자 목록·상세 화면 개편 (draft, HAJA-393/394)" 참조(이 세션이 작성한 초안). 요약:

1. **`GET /api/inspections`** 신규 — 로그인 사용자 소유(회사 스코프) 점검 목록, 페이지네이션 + 상태/시설물 필터. `InspectionController`에 추가. 응답 필드는 대시보드 `RecentInspectionsResponse`/`UpcomingInspectionResponse`(`DashboardController`)를 참고해 중복 없이 설계.
2. **`GET /api/inspections/{id}/defects`** — 이미 존재(`DefectRevisionController`, `DefectDetailItem`). **새로 만들지 말 것.** 카드 UI에 썸네일 등 필드가 부족하면 `DefectDetailItem`을 확장.
3. **하자 상세 "작업 내용" 필드** — 현재 `Defect` 엔티티엔 담당자/조치 메모 컬럼이 없음. **먼저 `frontend/726` 세션이 Figma 모달 화면에서 정확한 필드를 확정할 때까지 이 부분은 보류**하고, 나머지(1·2번)부터 먼저 구현·커밋해도 됨. 필드가 정해지면:
   - 신규 컬럼은 `V5__*.sql`로 추가(V1~V4 절대 수정 금지, 넘버링은 반드시 이어서)
   - `docs/api-contract/contract.md`의 TBD 항목을 실제 필드로 갱신(문서 버전 bump — root v0.8→v0.9 + `archive/contract_v0.8.md` 스냅샷, 헤더 라인 보존)
4. **파일 업로드** — 기존 `POST /api/inspections/{id}/media` 그대로 사용. 신규 엔드포인트 만들지 않음.
5. 상태 전이 `PATCH /api/defects/{id}/status`는 무변경.

## 완료 기준
- `./gradlew compileJava` + `./gradlew test` PASS
- 신규 엔드포인트 Swagger `@Tag`/`@Operation` 필수
- 스키마 변경 시 `V{n}__*.sql`(다음 번호 확인 후 진행), 기존 마이그레이션 수정 금지
- 단계별 커밋(엔티티/DTO → 서비스 → 컨트롤러 → 테스트 순), **push 금지**
- 완료 후 메인 세션(사용자)에게 변경파일 절대경로 + 빌드결과 보고, 대기

## 금지
self-review·security-review 호출 / PR 작성·생성 / push / 머지 / STATUS.md 수정 / api-contract.md의 TBD 외 섹션 임의 수정(프론트와 충돌 시 `[CONTRACT-CHANGE-REQUEST]`로 표시만)
