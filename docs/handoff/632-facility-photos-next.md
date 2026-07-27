# handoff: #632 시설물 대표 사진 저장 — Option B 구현

## 이슈/사이클
- GitHub #632, 이어지는 구현 이슈 #652("시설물 대표 사진 실제 업로드(멀티파트) 엔드포인트")
- Jira: HAJA-377
- **사이클: Critical** — 인가(authorization) 로직 변경이 포함됨(아래 필수처리 1번)
- 워크트리: `C:\AISOURCE\hajacheck\HajaCheck-wt-632-facility-photos`
- 브랜치: `feature/632-facility-photos-media`

## 결정된 설계 (Option B, 유병현 조건부 승인 완료 — #632 코멘트 참고)
`Media` 엔티티에 **nullable `facility_id` 컬럼**을 추가한다. 기존 `inspection_id`(점검 중 사진)와 폴리모픽 관계 — 한 `Media` 로우는 `inspection_id` 또는 `facility_id` 중 **정확히 하나만** 채워진다(둘 다 null이거나 둘 다 non-null인 로우는 금지). 새 테이블은 만들지 않고 기존 media 인프라(파일 검증·저장·썸네일 생성)를 그대로 재사용한다.

## 필수 처리 3건 (모두 같은 PR에 포함 — 메인 세션이 사전 리스크 감사로 확정한 조건, 생략 불가)

### 1. `MediaService.loadOwnedMedia` 인가 분기 추가 (가장 중요 — 보안 관련)
현재 `loadOwnedMedia`(썸네일 `/api/media/{id}/thumbnail`·상세이미지 `/api/media/{id}/detail` 다운로드의 유일한 인가 관문)는 `inspectionService.getInspection(userId, companyId, media.getInspectionId())`만 호출한다. `facility_id`만 채워진 로우(`inspection_id=null`)가 이 경로로 조회되면 null FK 조회로 500 또는 인가 누락이 발생한다.

`inspection_id`/`facility_id` 유무로 분기해서, facility 전용 로우는 `FacilityService`의 기존 `companyId` 스코프 조회 패턴(`FacilityService.get(userId, companyId, facilityId)` 계열, `FacilityService.java:118-127` 참고)으로 인가하도록 새 분기를 추가한다.

### 2. `Media.java` 엔티티 애노테이션 동시 변경 (부팅 실패 방지 — #531 재발 방지 원칙)
`Media.inspectionId`의 `@Column(name="inspection_id", nullable = false)`와 `@ManyToOne(optional = false)`를 **마이그레이션과 같은 PR/같은 커밋 그룹**에서 nullable/optional로 변경해야 한다. 어긋나면 `ddl-auto=validate`가 기동 자체를 막는다(과거 #531 프로덕션 다운 재발 방지 원칙과 동일 사유 — CLAUDE.md Flyway 워크플로우 참고).

### 3. DB CHECK 제약 추가
새 마이그레이션에 `CHECK ((inspection_id IS NOT NULL) <> (facility_id IS NOT NULL))` 형태의 제약을 포함해, 폴리모픽 FK가 둘 다 비거나 둘 다 채워지는 오염 로우를 DB 레벨에서 막는다. 현재 계획엔 이게 없었던 것이 리스크 감사에서 지적된 갭이다.

## 마이그레이션 번호
착수 직전 `ls backend/src/main/resources/db/migration/`로 최신 번호 재확인 필수(다른 워크트리가 먼저 V19를 썼을 수 있음 — 이 handoff 작성 시점 기준 최신은 V18, 즉 다음은 V19이지만 반드시 재확인할 것).

## 구현 범위 (#652 기준)
1. Flyway 마이그레이션: `media.facility_id` nullable FK(`facilities` 참조) + CHECK 제약(위 3번)
2. `Media.java`: `facilityId` nullable 컬럼 추가, `inspectionId`/`inspection` nullable/optional로 변경(위 2번)
3. `MediaService`: 시설물 대표 사진 업로드용 신규 메서드(예: `uploadFacilityPhoto(userId, companyId, facilityId, MultipartFile)`) — 기존 `storeAndBuild`의 파일검증/EXIF/썸네일 로직 재사용, 마지막 `Media.builder()` 호출만 `facilityId(facilityId)`로 분기. **최대 4장 제한**은 애플리케이션 레벨 카운트 검증(업로드 전 `mediaRepository.countByFacilityId(facilityId)` 등으로 확인).
4. `MediaService.loadOwnedMedia`: 위 1번 인가 분기 추가
5. `MediaController`: 신규 업로드 엔드포인트(멀티파트) — 기존 `/api/inspections/{id}/media` 패턴 참고하되 시설물 스코프로 신설
6. `MediaRepository`: `countByFacilityId`, `findByFacilityIdOrderByIdAsc` 등 신규 조회 메서드

## 테스트 (현재 커버리지 0 — 반드시 추가)
- `MediaServiceTest`: facility 전용 `Media`(`inspectionId=null`) 픽스처로 `loadOwnedMedia` 인가 분기 테스트(성공/타사 접근 거부 양쪽)
- `MediaRepositoryTest`: facility 전용 로우 조회 + CHECK 제약 위반 시 DB 예외 발생 테스트(Testcontainers)
- 업로드 4장 제한 테스트(4장 성공, 5번째 거부)
- 기존 inspection 전용 경로(`getMediaByInspection`, AI 분석 파이프라인, 하자 조치사진 매칭) 회귀 없음 확인 — 기존 테스트 전부 그린 유지가 최소 기준

## 책임 (이것만)
1. 워크트리+브랜치 이미 준비됨(위 경로/브랜치 그대로 사용)
2. 코드 + 단위/통합 테스트 (Testcontainers 기반, `PostgresTestSupport` 패턴 재사용)
3. 빌드/테스트 PASS: `./gradlew compileJava compileTestJava` + `./gradlew test`(관련 테스트: `*Media*`), 오류 0
4. 단계별 커밋(feat: 마이그레이션+엔티티 → 서비스 → 컨트롤러 → 테스트 순), **push 금지**
5. 메인에 보고: 변경파일 절대경로 + 의도 + 빌드결과

## 금지 (메인이 함)
self-review·security-review 호출 / PR 작성·생성 / push / 머지 / STATUS.md 수정. 보고 후 대기(메인이 SendMessage로 후속 지시 가능).
