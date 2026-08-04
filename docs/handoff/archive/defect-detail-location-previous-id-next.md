# handoff: 하자 정보 패널 실연동 — location(#970) + previous_defect_id(HAJA-437)

> **문서 버전:** v0.1 · **최종 수정:** 2026-07-27

## 이슈/사이클
- GitHub #970(갭3, location) + HAJA-437(Jira, previous_defect_id) — 같은 `defects` 테이블이라 한 PR로 묶음
- **사이클: Normal** — auth/트랜잭션/트리거 없음, sonnet 기본
- 워크트리: `C:\AISOURCE\hajacheck\HajaCheck-wt-defect-detail-fields`
- 브랜치: `feature/defect-detail-location-previous-id` (origin/dev 기준, 착수 시점 최신 마이그레이션 = V20)

## 마이그레이션 번호 — 착수 직전 반드시 재확인
V19(`media.facility_id`)의 마이그레이션 코멘트에 이미 팀 합의가 적혀있음: **V21=location, V22=previous_defect_id**. 단, 이 handoff 작성 시점 이후 다른 PR이 먼저 V21/V22를 가져갔을 수 있으니 **구현 착수 직전 `ls backend/src/main/resources/db/migration/`로 실제 최신 번호 재확인 필수**. 특히 #1032(V21, `inspection_notification_settings` 관련)와 이은석 님의 V22 작업이 먼저 머지될 예정이므로, **이 두 PR이 머지된 뒤 최신 dev로 리베이스한 후에 실제 번호를 V23/V24 등으로 재조정**해야 할 가능성이 높다(사용자가 이미 이 순서를 지시함 — 리베이스 전에는 로컬 검증용으로 V21/V22를 그대로 쓰고, 리베이스 후 실제 값으로 재번호).

## 승인된 설계 (Polalise, #970 코멘트 확정)
- `location`: nullable text 컬럼. 조치 등록 시가 아니라 **사후 관리자 편집**(별도 가벼운 엔드포인트, 회사 스코프 인가만) — "조치 등록 시 입력"으로 묶으면 활성 하자 대부분에서 계속 비어있어 실효성이 낮다는 이유로 반려됨.
- `previousDefectId`: nullable self-referencing FK(`defects.id`). 값은 **검수자가 화면에서 확정한 것만 저장**(자동 매칭 알고리즘이 후보를 제안하는 건 이번 스코프 밖 — 아래 "이번 PR 스코프 밖" 참고).
- `foundCycle`(회차)은 **이미 #985로 완료**(`DefectResponse.foundCycle`, `Inspection.roundNo` 노출) — 이 PR에서 손댈 필요 없음.
- `assigneeName`은 신규 컬럼이 아니라 **`Facility.assigneeUserId` 재사용**으로 확정됨(팀 결정) — `defects` 테이블 변경 없음.

## 구현 범위

### 1. 마이그레이션 (착수 시점 기준 V21/V22, 리베이스 후 재번호)
`V21__add_defect_location.sql`:
```sql
alter table defects
    add column if not exists location text;
comment on column defects.location is '하자 위치 텍스트(예: 외벽 동측 12층 부근) — 검수자 사후 편집';
```
`V22__add_defect_previous_defect_id.sql`:
```sql
alter table defects
    add column if not exists previous_defect_id bigint references defects (id);
comment on column defects.previous_defect_id is '이전 회차 대응 하자 id(검수자 확정, self-referencing) — 회차 간 비교용';
```
V6/V12 스타일(단순 `add column if not exists ... references ...`)을 따른다 — V19처럼 named CHECK 제약이 필요한 케이스가 아니므로 `do $$` 가드 블록 불필요. self-reference라도 `defects.id`가 이미 PK로 존재해 문제 없음.

**⚠️ 리베이스 후 반드시 갱신할 파일 3개(#632에서 실제로 겪은 함정)**: `FlywayBaselineIntegrationTest.java`, `FlywayBaselineOnExistingDbIntegrationTest.java`, `Ha25IncrementalMigrationTest.java` — 전부 마이그레이션 폴더를 자동 스캔하지 않고 파일을 하드코딩 나열한다. 새 버전 파일을 이 3개 테스트의 copy 체인/실행 순서/적용 카운트 단언에 추가하지 않으면 로컬은 통과해도 CI에서만 실패한다.

### 2. `Defect.java` 엔티티
- `location`(nullable `String`) — 빌더에 포함 가능(생성 시점엔 항상 null이지만 필드 자체는 단순 nullable 컬럼)
- `previousDefectId`(nullable `Long`) — `mediaId` 패턴 그대로(빌더엔 포함하지 않음, 조치결과처럼 별도 세터/메서드로만 설정 — 검수자 확정 행위이므로 생성 시점 값이 아님)
- 클래스 상단 필드별 설명 주석 컨벤션 유지(`mediaId` 주석 참고)

### 3. `DefectResponse` DTO
- `location`(String) 필드 추가, `from()`/`from(Defect, String)` 양쪽에 매핑
- `assigneeName`(String, 신규 — `actionAssigneeName`과 별개) 추가 — `DefectService.get()`에서 `defect.getInspection().getFacility().getAssigneeUserId()`로 조회(이미 `findByIdAndCompanyId`가 `join fetch d.inspection i join fetch i.facility f`로 Facility까지 즉시 로딩하므로 추가 쿼리 없음), null이면 `userRepository.findById(...)` 스킵 — `actionAssigneeId → actionAssigneeName` 해소와 동일한 단건 조회 패턴(`DefectService.java:86-87` 참고, 배치 조회인 `FacilityService` 패턴 아님)
- `list()`(`DefectResponse.from(Defect)`, 무인자 오버로드)는 N+1 방지를 위해 assigneeName 조회를 하지 않음 — 상세 조회(`from(Defect, String, String)` 식 오버로드 확장 또는 새 파라미터)에만 채움

### 4. 신규 엔드포인트 2개 (`DefectController`/`DefectService`)
- `PATCH /api/defects/{id}/location` — body `{location: string}`(nullable 허용, 빈 문자열은 null로 정규화 고려), 회사 스코프 인가만(`companyScopeGuard` + `findByIdAndCompanyId` 패턴 재사용), 응답은 `DefectResponse`
- `PATCH /api/defects/{id}/previous-defect` — body `{previousDefectId: Long}`. 검증: previousDefectId가 가리키는 Defect가 (a) 같은 회사 스코프 (b) 같은 시설물(`inspection.facilityId` 일치) (c) 더 이전 회차(`inspection.roundNo` < 현재 defect의 roundNo)를 만족해야 한다 — 불만족 시 `INVALID_INPUT` 또는 적절한 신규 `ErrorCode`. 응답은 `DefectResponse`.

### 5. 이번 PR 스코프 밖 (후속 이슈로 남길 것)
- **자동 후보 제안 알고리즘**(bbox 근접도·유형·등급 유사도로 이전 회차 후보 추천) — HAJA-437 Jira 원문에 언급되지만 별도 AI/알고리즘 작업. 이번 PR은 검수자가 이미 알고 있는 이전 하자 id를 직접 입력해 확정하는 것까지만.
- **"회차 간 비교" 화면 전체**(`GET /facilities/{id}/compare`, KPI·크랙 트렌드·변경목록) — 프론트 계약(`InspectionComparisonResult`/`DefectChangeRow`)에 `previousDefectId` 필드 자체가 아직 없어 계약 변경이 필요한 더 큰 작업. 이번 PR은 `previous_defect_id` 컬럼 존재 + 확정 엔드포인트까지만, 비교 화면 실연동은 후속.

## 프론트엔드 실연동 (하자 정보 패널만, 비교 화면 제외)
- `frontend/src/features/facility/api/facilityDefectApi.ts` — 현재 존재하지 않는 MSW 전용 엔드포인트(`GET /facilities/{facilityId}/defect-detail`) 호출 중. **식별자를 facilityId에서 실제 defect id로 전환**해 `GET /api/defects/{id}`를 호출하도록 변경(`Facility.latestDefectId` 등으로 defect id를 얻는 경로 확인 필요 — 기존 `useFacilityDefectDetail` 훅과 호출부 확인).
- `FacilityDefectDetail` 타입의 `location`/`assigneeName`은 이미 타입에 존재(문자열) — 백엔드 필드가 nullable일 수 있으니 프론트에서 `null`/`undefined` 처리(빈 값 시 "—" 등 placeholder) 필요.
- `widthMm`/`lengthM`은 백엔드가 nullable `Double`이라 균열이 아닌 유형은 null — 프론트도 이 케이스 처리 확인.
- `PATCH /api/defects/{id}/location` 저장 UI(예: 인라인 편집)는 이번 PR 범위에 포함해도 되고, API 연동만 먼저 하고 UI는 후속으로 분리해도 됨 — 서브에이전트 판단에 맡기되 최소한 API 클라이언트 함수는 추가.

## 테스트
- `DefectServiceTest`: location 수정 엔드포인트(성공/타사 거부), previous-defect 확정 엔드포인트(성공/다른 시설물 거부/같은 회차 이후 거부/타사 거부), assigneeName 해소(있음/없음)
- `DefectRepositoryTest` 또는 신규: self-referencing FK 실제 동작(Testcontainers) — previous_defect_id가 다른 Defect를 가리키는 로우 저장/조회
- `DefectControllerTest`: 신규 엔드포인트 2개 MockMvc 테스트
- 프론트: `useFacilityDefectDetail`/`facilityDefectApi` 테스트를 실 엔드포인트 기준으로 갱신(MSW 핸들러도 함께)

## 완료 기준
- `./gradlew compileJava compileTestJava` + `./gradlew test --tests "*Defect*"` 전부 PASS
- 프론트 `npx tsc --noEmit` + 관련 테스트 PASS
- 리베이스 후 Flyway 3종 테스트(위 ⚠️ 참고) 갱신 및 통과

## 책임 (이것만)
1. 워크트리+브랜치 이미 준비됨(위 경로/브랜치 그대로 사용) — **단, V21/V22 머지 확인 및 리베이스는 메인이 지시할 때까지 기다릴 것. 그 전까지는 로컬 검증(compile+test)까지만 진행하고 push하지 말 것.**
2. 코드 + 단위/통합 테스트 (백엔드), 프론트 실연동 + 테스트
3. 빌드/테스트 PASS, 오류 0
4. 단계별 커밋(feat: 마이그레이션+엔티티 → 서비스+컨트롤러 → 프론트 연동 → 테스트 순), **push 금지**
5. 메인에 보고: 변경파일 절대경로 + 의도 + 빌드결과 + 다 끝나면 "V21/V22 실제 확정 및 리베이스 대기 중" 상태로 대기

## 금지 (메인이 함)
self-review·security-review 호출 / PR 작성·생성 / push / 머지 / STATUS.md 수정 / 마이그레이션 번호 최종 확정(메인이 V21/V22 머지 확인 후 지시).
