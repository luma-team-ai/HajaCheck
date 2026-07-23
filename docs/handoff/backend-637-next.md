# facilities 회사 소유 전환 handoff

> **문서 버전:** v0.1 · **최종 수정:** 2026-07-23

## 이슈

- GitHub: #637
- Jira: HAJA-353
- 사이클: Critical — 회사 스코프 인가 경계 및 기존 운영 데이터 변환
- 기준 브랜치: `origin/dev` (`fbe24b44`)
- 작업 브랜치: `backend/637-facility-company-scope`

## 목표

- `facilities.owner_id` (`users.id`)를 `facilities.company_id` (`companies.id`)로 전환한다.
- 기존 시설 행은 `facilities.owner_id = users.id`를 통해 `users.company_id`로 변환한다.
- 개인 사용자처럼 `users.company_id IS NULL`인 기존 시설이 있으면 데이터를 임의 삭제하거나 임의 회사에
  배정하지 말고 V8을 명시적으로 실패시킨다.
- 시설 조회·인가 범위를 로그인 사용자 개인이 아니라 로그인 사용자의 회사로 통일한다.

## 구현 범위

1. 기존 V1~V7은 수정하지 않고
   `backend/src/main/resources/db/migration/V8__migrate_facilities_to_company.sql`을 추가한다.
2. V8은 두 경로를 모두 안전하게 지원한다.
   - 빈 DB: V1의 `owner_id` 스키마를 V8에서 `company_id`로 변환
   - baseline-on-existing DB: 최신 캐노니컬 DDL에 이미 `company_id`가 있으면 의미 검증 후 no-op
3. 마이그레이션은 기존 FK/인덱스를 회사 FK/인덱스로 교체하고, 컬럼 타입·NOT NULL·FK 대상·인덱스
   의미를 검증한다. 매핑 불가능한 기존 행이 있으면 구체적인 예외로 중단한다.
4. `Facility` 엔티티와 `FacilityRepository`를 `companyId` 기준으로 변경하고, 시설을 경유하는 JPQL/QueryDSL
   쿼리(`f.ownerId`)도 모두 회사 기준으로 바꾼다.
5. 컨트롤러에서는 시설 스코프 인자로 `LoginUser.companyId`를 사용한다. 단, 점검 `createdBy`,
   하자 수정자, 알림 수신자 같은 액터 FK는 계속 `LoginUser.userId`를 사용한다. 한 메서드에서 두 값이
   모두 필요하면 시그니처를 분리해 의미를 숨기지 않는다.
6. `companyId == null`인 로그인 사용자는 시설 생성·조회·수정·삭제 및 시설 경유 리소스 접근을
   명시적인 도메인 예외(기존 `FORBIDDEN` 우선)로 거부한다. DB 제약 예외에 맡기지 않는다.
7. 현재 캐노니컬 스키마와 개발 시드도 갱신한다.
   - `docs/design/db/HajaCheck_script.sql`
   - `docs/design/db/seed_dev_facilities.sql`
   - `docs/design/db/table_design.md`: released v0.5를 `archive/table_design_v0.5.md`로 원문 스냅샷한 뒤
     root를 v0.6 / 2026-07-23으로 갱신한다.
8. Facility 응답 계약의 `ownerId` 처리 여부는 기존 프론트 호환성을 확인한다. 내부 소유 의미가 회사로
   바뀌었는데 사용자 ID처럼 오해되는 값을 새로 노출하지 않는다. 계약명을 바꿔야 하면 관련 백엔드 테스트와
   최소 프론트 타입/fixture까지 함께 정합시킨다.

## 필수 테스트

- V8 전용 Testcontainers 통합 테스트:
  - 기존 `owner_id` 행이 소유 사용자의 `company_id`로 정확히 이관됨
  - 회사 없는 소유자의 시설이 있으면 마이그레이션 실패
  - 최종 컬럼/FK/인덱스 의미 검증
- `FlywayBaselineIntegrationTest`와 `FlywayBaselineOnExistingDbIntegrationTest`의 V8 기대값 갱신
- 변경된 Facility/Repository/Service/Controller 및 시설 경유 도메인 테스트 갱신
- `./gradlew compileJava`
- `./gradlew test`

## G1 자체 검수

- PASS — 최신 원격에 V7이 존재하므로 다음 번호를 V8로 확정했다.
- PASS — 운영 회귀 패턴인 Hibernate `ddl-auto=validate` 누락과 baseline-on-existing 경로를 필수 테스트에 포함했다.
- PASS — 사용자 ID가 액터 FK로 남는 경로와 회사 ID가 인가 스코프가 되는 경로를 구분했다.

## 책임

1. 지정 워크트리 안에서만 코드·테스트를 수정한다.
2. 빌드/테스트를 통과시킨다.
3. 단계별 한국어 커밋을 만든다. `Co-Authored-By`는 넣지 않는다.
4. push, PR 생성·머지, Jira/GitHub 상태 변경, `docs/STATUS.md` 수정은 하지 않는다.
5. 완료 시 변경 파일 절대경로, 설계 판단, 테스트 결과, 커밋 해시를 보고하고 대기한다.
