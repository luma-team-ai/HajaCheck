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
`docs/api-contract/contract.md` §"하자 목록·상세 화면 개편"을 참조 — **Figma 3개 노드를 메인 세션이 실사 확인 완료**(Starter 플랜이라 이 세션은 Figma MCP 호출을 아낄 것). 요약:

1. **`GET /api/inspections`** 신규 — 로그인 사용자 소유(회사 스코프) 점검 목록, 페이지네이션 + 상태/시설물 필터. `InspectionController`에 추가. 하자 목록 화면(node `1-2099`)은 원래 하자 단건 플랫 테이블이지만 **점검 단위로 재해석**하기로 확정됨 — 응답에 점검일·시설물·하자 건수·등급분포 등 집계 포함 검토(대시보드 `RecentInspectionsResponse`/`UpcomingInspectionResponse` 참고).
2. **`GET /api/inspections/{id}/defects`** — 이미 존재(`DefectRevisionController`, `DefectDetailItem`). **새로 만들지 말 것.** 카드 UI(썸네일/등급/상태/AI신뢰도/최대폭)에 필드가 부족하면 `DefectDetailItem`을 확장.
3. **하자 상세 모달 "조치 결과 등록" 필드** (Figma node `1562-3682` 실사 확정) — `Defect` 엔티티에 아래 컬럼이 없어 **Flyway V5** 필요(V1~V4 절대 수정 금지):
   - `조치 후 사진` — 파일 업로드(4번 참조, JPG/PNG 최대 10MB)
   - `조치 내용` — 텍스트
   - `조치일` — 날짜
   - `담당자` — 사용자 FK, 드롭다운 옵션은 **`GET /api/facilities/assignable-users`(#690, `FacilityController.java:102`, `AssignableUserResponse`) 재사용** — 신규 엔드포인트 만들지 말 것
   - "조치 완료 등록" 버튼이 상태전이(`RESOLVED` 추정)와 위 필드 저장을 동시에 하는 것으로 보임 — `PATCH /api/defects/{id}/status`(`DefectStatusUpdateRequest`)를 확장할지, 별도 엔드포인트(`PATCH /api/defects/{id}/action` 등)로 분리할지는 **직접 판단**하고 결정 즉시 `docs/api-contract/contract.md`의 "잔여 TBD"를 갱신(문서 버전 bump — root v0.8→v0.9 + `archive/contract_v0.8.md` 스냅샷, 헤더 라인 보존)
4. **파일 업로드("조치 후 사진")** — 기존 `POST /api/inspections/{id}/media` 그대로 사용. 신규 엔드포인트 만들지 않음.

## 완료 기준
- `./gradlew compileJava` + `./gradlew test` PASS
- 신규 엔드포인트 Swagger `@Tag`/`@Operation` 필수
- 스키마 변경 시 `V{n}__*.sql`(다음 번호 확인 후 진행), 기존 마이그레이션 수정 금지
- 단계별 커밋(엔티티/DTO → 서비스 → 컨트롤러 → 테스트 순), **push 금지**
- 완료 후 메인 세션(사용자)에게 변경파일 절대경로 + 빌드결과 보고, 대기

## 금지
self-review·security-review 호출 / PR 작성·생성 / push / 머지 / STATUS.md 수정 / api-contract.md의 TBD 외 섹션 임의 수정(프론트와 충돌 시 `[CONTRACT-CHANGE-REQUEST]`로 표시만)
