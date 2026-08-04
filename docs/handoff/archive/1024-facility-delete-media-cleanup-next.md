# handoff: #1024 시설물 삭제 시 대표 사진 정리 (앱-레벨, 마이그레이션 없음)

> **문서 버전:** v0.1 · **최종 수정:** 2026-07-27

## 이슈/사이클
- GitHub #1024, 관련 #632/#1017/#652
- **사이클: Normal** — DB 스키마 변경 없음, DB 소유자(유병현) 승인 불필요(앱-레벨 방식으로 확정)
- 워크트리: `C:\AISOURCE\hajacheck\HajaCheck-wt-1024-facility-delete`
- 브랜치: `fix/1024-facility-delete-media-cleanup` (origin/dev 기준, #1017/#632 이미 포함)

## 버그
`FacilityService.delete()`(`backend/src/main/java/com/hajacheck/core/facility/service/FacilityService.java:210-217`)가 `facilityRepository.delete(...)`로 시설물을 물리 삭제하는데, V19(#1017)가 추가한 `fk_media_facility`(`media.facility_id → facilities.id`, `ON DELETE` 절 없음=기본 `NO ACTION`) 때문에 **대표 사진이 1장이라도 있는 시설물은 삭제 시 FK 위반 → 처리되지 않은 500**이 난다.

## 확정된 해결 방식 — 앱-레벨 정리 (마이그레이션 없음)
`facilityRepository.delete(...)` **호출 전에** 같은 트랜잭션에서:
1. `mediaRepository.findByFacilityIdOrderByIdAsc(facilityId)`로 해당 시설물의 대표 사진 로우 전부 조회
2. 각 로우의 스토리지 파일(`originalUrl`, `thumbnailUrl`, `detailUrl` — 전부 `FileStorageService`의 storageKey) 삭제
3. media 로우 삭제(`mediaRepository.deleteAll(...)`)
4. 그 다음 `facilityRepository.delete(...)`

## ⚠️ 순환 의존성 — 반드시 지킬 것
**`MediaService`를 `FacilityService`에 주입하지 말 것.** `MediaService`가 이미 `FacilityService`를 주입하고 있어(`uploadFacilityPhotos`/`loadOwnedMedia`의 companyId 스코프 체크용), 역방향으로 `MediaService`를 넣으면 `FacilityService → MediaService → FacilityService` 순환참조로 스프링 부팅이 실패한다(생성자 주입, `@Lazy` 미사용).

대신 **`MediaRepository`와 `FileStorageService`를 `FacilityService`에 직접 주입**한다(둘 다 `FacilityService`에 역의존성 없음, 순환 없음) — `MediaService` 자신이 이 두 컴포넌트를 직접 쓰는 것과 동일한 패턴.

## 구현 범위
1. `FacilityService.java`: 생성자에 `MediaRepository mediaRepository`, `FileStorageService fileStorage` 필드 추가(`@RequiredArgsConstructor`라 필드만 추가하면 됨)
2. `delete()` 메서드 수정 — `facilityRepository.delete(...)` 호출 직전에 위 정리 로직 삽입
3. `FileStorageService.delete(String storageKey)`는 best-effort/never-throws(blank key no-op, IOException은 catch+warn 로그) — 개별 try/catch 불필요, `MediaService.uploadMedia`/`uploadFacilityPhotos`의 기존 호출 패턴(`storedKeys.forEach(fileStorage::delete)`) 그대로 재사용
4. `thumbnailUrl`/`detailUrl`은 nullable — `fileStorage.delete(null)`도 안전(no-op)하니 별도 null 체크 불필요, 그냥 전달
5. `MediaRepository`에 신규 메서드 불필요(`findByFacilityIdOrderByIdAsc` 이미 있음, `deleteAll(Iterable)`은 `JpaRepository` 기본 제공)

## 테스트
- `FacilityServiceTest.java`: 기존 `delete_본인시설_저장소에서삭제()` 테스트가 Media 상호작용을 전혀 mock/검증하지 않음 — 새 생성자 의존성(`MediaRepository`, `FileStorageService`) 때문에 `@Mock` 필드 추가 안 하면 `@InjectMocks` 해석이 깨진다. 반드시 두 개 다 `@Mock` 추가.
- 신규 테스트 최소 2건:
  - "대표 사진이 있는 시설물 삭제 시 media 로우 + 스토리지 파일이 함께 정리되고 시설물도 정상 삭제된다"(각 media의 storageKey로 `fileStorage.delete`가 호출됐는지, `mediaRepository.deleteAll`/삭제가 `facilityRepository.delete`보다 먼저 호출됐는지 순서 검증)
  - "대표 사진이 없는 시설물은 media 관련 호출 없이 정상 삭제된다"(빈 리스트 stub, 회귀 없음 확인)
- 가능하면 `MediaRepositoryTest`(Testcontainers) 쪽에도 실제 DB 레벨 통합테스트 1건 — 대표 사진 있는 시설물을 실제로 delete()해서 FK 위반 없이 성공하는지 확인(이전에는 이 시나리오 테스트 자체가 전혀 없었음)

## 완료 기준
- `./gradlew compileJava compileTestJava` + `./gradlew test --tests "*Facility*" --tests "*Media*"` 전부 PASS
- 기존 시설물 CRUD 회귀 없음
- 순환 의존성 없이 컨텍스트 정상 기동(테스트가 통과한다는 것 자체가 증거)

## 책임 (이것만)
1. 워크트리+브랜치 이미 준비됨(위 경로/브랜치 그대로 사용)
2. 코드 + 단위/통합 테스트
3. 빌드/테스트 PASS, 오류 0
4. 단계별 커밋(fix: FacilityService 수정 → test: 테스트 추가 순), **push 금지**
5. 메인에 보고: 변경파일 절대경로 + 의도 + 빌드결과

## 금지 (메인이 함)
self-review·security-review 호출 / PR 작성·생성 / push / 머지 / STATUS.md 수정. 보고 후 대기.
