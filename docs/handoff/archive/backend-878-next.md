# backend-878-next — 점검 목록 자연어(하자조건) 필터 API

## 이슈
- GitHub #878
- 선행: #725(BE, awaiting-promotion — 이미 dev 머지) · #726(FE, OPEN)

## 사이클
**Normal** (sonnet, code-reviewer 1회, cap 1) — 회사 스코프 인가(IDOR)가 걸린 영역이라 기존 `InspectionController`/`InspectionService`의 `loginUser.getCompanyId()` 패턴을 반드시 그대로 유지할 것.

## 브랜치·워크트리
```
git worktree add ../hajacheck-backend-878 -b backend/878-inspection-defect-filter
```

## 구현 범위
`docs/api-contract/contract.md` §"GET /api/inspections — 하자 조건(자연어) 필터 확장 (신규, 2026-07-26)" 참조. 요약:

1. **`GET /api/inspections`에 선택 파라미터 추가**: `defectType`(array), `defectGrade`(array), `defectStatus`(array).
   - 기존 파라미터 `status`(점검 상태)·`facilityId`와 이름이 겹치지 않게 주의 — 하자 상태는 `defectStatus`로 명확히 구분.
   - enum 값은 기존 `DefectType`/`DefectGrade`/`DefectStatus`와 동일한 걸 재사용(신규 enum 만들지 말 것).
2. **매칭 조건**: 위 파라미터가 1개 이상 주어지면, 해당 점검에 속한 하자 중 하나라도 조건을 만족하는 점검만 결과에 포함. `EXISTS` 서브쿼리 권장(점검 단위 페이지네이션 결과가 하자 단위 JOIN으로 뻥튀기되지 않도록 — distinct 점검 기준 유지).
3. **회사 스코프(IDOR) 검증**: 기존 `GET /api/inspections` 구현이 `loginUser.getCompanyId()`로 스코프 거는 패턴을 그대로 유지 — 새 파라미터 추가가 이 스코프 검증을 우회하지 않는지 반드시 확인.
4. 이 이슈는 **점검 조회 API 확장만** 다룬다 — `POST /api/defects/nl-search`(자연어→filters 변환)는 이미 존재하며 수정 대상 아님. 프론트가 그 결과를 이 API의 쿼리 파라미터로 실어 호출하는 조합이다(프론트 작업은 별도 세션).

## 완료 기준
- `./gradlew compileJava` + `./gradlew test` PASS
- 신규 파라미터 Swagger `@Operation`/`@Parameter` 문서화
- 기존 `GET /api/inspections` 테스트(상태/시설물 필터) 회귀 없는지 확인 + 신규 파라미터 조합 테스트(단일/복수/미매칭 케이스) 추가
- 스키마 변경 없음(엔티티 변경 없이 쿼리만 확장하는 게 기본 전제 — 만약 인덱스 추가가 필요하다고 판단되면 `V{n}__*.sql` 신규 마이그레이션, 기존 마이그레이션 수정 금지)
- 단계별 커밋(DTO/파라미터 → 리포지토리 쿼리 → 서비스 → 컨트롤러 → 테스트 순), **push 금지**
- 완료 후 메인 세션(사용자)에게 변경파일 절대경로 + 빌드결과 보고, 대기

## 금지
self-review·security-review 호출 / PR 작성·생성 / push / 머지 / STATUS.md 수정 / api-contract.md 임의 수정(계약과 다르게 구현해야 한다고 판단되면 `[CONTRACT-CHANGE-REQUEST]`로 표시만)
