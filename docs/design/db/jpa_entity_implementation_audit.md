# JPA Entity 구현 사전 감사

> **문서 버전:** v0.1 · **최종 수정:** 2026-07-16
> 대상: HAJA-25 및 하위 작업 HAJA-198~206
> 기준일: 2026-07-16
> 기준 파일: `docs/design/db/HajaCheck_script.sql`, `docs/design/db/table_design.md`,
> `docs/conventions/SpringBoot_코드_컨벤션.md`

## 1. 착수 시점 구현 현황

최신 통합 DDL에는 PostgreSQL named enum 23개와 테이블 20개가 정의되어 있다.
현재 백엔드에는 그중 9개 테이블에 대응하는 JPA Entity가 구현되어 있다.

| 영역 | 테이블 | 현재 Entity | HAJA-25 작업 |
|---|---|---:|---|
| 계정·인증 | `users` | O | 기존 매핑 정합성 검증 |
| 계정·인증 | `companies` | O | 기존 매핑 정합성 검증 |
| 계정·인증 | `company_memberships` | X | 신규 구현 |
| 계정·인증 | `user_consents` | O | 기존 매핑 정합성 검증 |
| 과금·정책 | `plans` | O | 기존 매핑 정합성 검증 |
| 과금·정책 | `user_plans` | O | 기존 매핑 정합성 검증 |
| 과금·정책 | `usage_counters` | O | 기존 매핑 정합성 검증 |
| 시설·점검 | `facilities` | O | 기존 매핑 정합성 검증 |
| 시설·점검 | `inspections` | O | 기존 매핑 정합성 검증 |
| 시설·점검 | `media` | X | 신규 구현 |
| 결함·보고서 | `defects` | O | 기존 매핑 정합성 검증 |
| 결함·보고서 | `defect_revisions` | X | 신규 구현 |
| 결함·보고서 | `reports` | X | 신규 구현 |
| 채팅·상담 | `chat_sessions` | X | 신규 구현 |
| 채팅·상담 | `chat_messages` | X | 신규 구현 |
| 채팅·상담 | `counsel_tickets` | X | 신규 구현 |
| 채팅·상담 | `bot_scenarios` | X | 신규 구현 |
| RAG | `rag_documents` | X | 신규 구현 |
| RAG | `chat_message_citations` | X | 신규 구현 |
| 공통 알림 | `notifications` | X | 신규 구현 |

따라서 신규 구현 대상은 11개이며, 기존 9개도 최신 DDL의 컬럼·제약과 다시 대조해야 한다.

### HAJA-25 진행 반영

- `CompanyMembership`과 `CompanyMembershipStatus` 구현 완료
- `Media`와 `MediaFileType` 구현 완료
- `DefectRevision`, `Report`, 채팅·상담·RAG 6개 Entity, `Notification` 구현 완료
- 최신 DDL의 `Inspection.assignedInspectorId` 누락 보완 완료
- 현재 Entity 구현 수: 20개(DDL 테이블 20개 전체 대응)
- PostgreSQL 17 빈 DB에 통합 DDL 적용 및 Hibernate `ddl-auto=validate` 통과
- 최신 `origin/dev` 통합 및 리뷰 수정 후 전체 Gradle 테스트 179개 통과

### 1.1 기존 Entity 필드 대조 결과

| Entity | DDL 대조 결과 | 필요한 조치 |
|---|---|---|
| `User` | 업무 컬럼과 named enum은 일치한다. `created_at`/`updated_at`은 DDL `timestamptz`, Java `LocalDateTime`이다. | 시간 타입 정책 확정 후 auditing 타입 검토 |
| `Company` | 업무 컬럼, jsonb, named enum은 일치한다. auditing 시간 타입 차이가 있다. | 시간 타입 정책 확정 후 auditing 타입 검토 |
| `UserConsent` | 컬럼과 `Instant` 시간 타입은 일치한다. 복합 UQ와 FK 삭제 정책은 DDL에서만 관리한다. | 객체 관계 정책 확정 후 `userId` 매핑 유지 여부 결정 |
| `Plan` | 업무 컬럼과 named enum은 일치한다. auditing 시간 타입 차이가 있다. | 시간 타입 정책 확정 후 auditing 타입 검토 |
| `UserPlan` | 컬럼과 `Instant` 시간 타입은 일치한다. XOR CHECK와 ACTIVE 부분 UQ는 DDL에서만 관리한다. | 객체 관계 정책 확정 후 FK 매핑 범위 결정 |
| `UsageCounter` | 업무 컬럼은 일치한다. `created_at`은 DDL `timestamptz`, Java `LocalDateTime`이다. 복합 UQ와 CHECK는 DDL에서만 관리한다. | 시간 타입 및 FK 매핑 정책 적용 |
| `Facility` | 업무 컬럼은 일치한다. auditing 시간 타입 차이가 있다. | 시간 타입 및 소유자 FK 매핑 정책 적용 |
| `Inspection` | 최신 DDL의 필수 컬럼 `assigned_inspector_id bigint not null`이 Entity와 생성자에 없다. `created_at` 시간 타입 차이도 있다. | 담당 검사자 필드 추가가 필수이며 생성 API·테스트 영향 검토 |
| `Defect` | 업무 컬럼과 named enum은 일치한다. `created_at` 시간 타입 차이가 있다. | 시간 타입 및 점검 FK 매핑 정책 적용 |

기존 9개 중 즉시 확인되는 기능 컬럼 누락은 `Inspection.assignedInspectorId` 한 건이다.
나머지 차이는 공통 시간 타입과 FK 객체 매핑 정책에 집중되어 있다.

### 1.2 DB에서만 강제되는 주요 제약

다음 제약은 현재 Entity annotation으로 재정의하지 않고 통합 DDL에서 강제한다.

- `users.ck_users_auth_method`
- `user_consents (user_id, policy_type, policy_version)` UNIQUE
- `user_plans.ck_user_plans_owner_xor`
- 사용자·회사별 ACTIVE 구독 부분 UNIQUE 인덱스
- `usage_counters` 월 시작일·음수 방지 CHECK와 `(user_plan_id, period)` UNIQUE
- `inspections (facility_id, round_no)` UNIQUE

Hibernate의 annotation 기반 스키마 생성 결과를 운영 스키마로 사용하지 않고 `ddl-auto=validate`만 사용한다면,
이 제약들의 권위 있는 원본은 `HajaCheck_script.sql`이다.

### 1.3 신규 Entity 구현 매트릭스

아래 패키지는 현재 package-by-feature 컨벤션에 따른 제안이다. 도메인 간 Entity 직접 참조를 피하기 위해
다른 영역을 가리키는 FK는 우선 `Long` 식별자로 매핑한다.

| 테이블 | 클래스·제안 패키지 | 추가 enum | 특수 매핑·제약 |
|---|---|---|---|
| `company_memberships` | `auth.entity.CompanyMembership` | `CompanyMembershipStatus` | 복합 UQ, APPROVED 부분 UQ, 상태별 시각 CHECK; 구현 완료 |
| `media` | `core.media.entity.Media` | `MediaFileType` | `source_video_id`는 DDL에 FK가 없으므로 `Long`; json 없음; 구현 완료 |
| `defect_revisions` | `core.defect.entity.DefectRevision` | 없음 | append-only, `field_changed` 길이 50, 값·사유 길이 제약 |
| `reports` | `core.report.entity.Report` | `ReportStatus` | `content_json`/`grounding_warnings` jsonb, `(inspection_id, version)` UQ |
| `chat_sessions` | `counsel.entity.ChatSession` | `ChatSessionType` | 시작·종료 `timestamptz`, 사용자 FK |
| `chat_messages` | `counsel.entity.ChatMessage` | `ChatSenderType` | `content` text, nullable `scenario_id`, 세션·시나리오 인덱스 |
| `counsel_tickets` | `counsel.entity.CounselTicket` | `CounselTicketStatus` | 사용자·상담사·세션 FK, 대기 순번, 종료 시각 |
| `bot_scenarios` | `counsel.entity.BotScenario` | 없음 | 자기 참조 `parent_id`, self-parent CHECK, updated_at 트리거 |
| `rag_documents` | `core.rag.entity.RagDocument` | `RagDocumentSourceType`, `RagTargetCollection`, `RagDocumentVerificationStatus`, `RagEmbeddingStatus` | Chroma 메타데이터, nullable 검증 상태·시행일·작성일, 임베딩 상태 전이 |
| `chat_message_citations` | `core.rag.entity.ChatMessageCitation` | 없음 | Chroma `chunk_ref` 문자열 외부 참조, 복합 UQ, 메시지 삭제 CASCADE |
| `notifications` | `notification.entity.Notification` | `NotificationType` | `payload_json` jsonb, 읽지 않은 알림 부분 인덱스 |

### 1.4 신규 Entity 구현 시 보존할 DDL 의미

- `DefectRevision`은 수정 또는 삭제 메서드를 제공하지 않는 append-only 모델로 구현한다.
- `Report.contentJson`, `Report.groundingWarnings`, `Notification.payloadJson`은 기존 `Company`와 같은
  `@JdbcTypeCode(SqlTypes.JSON)` 기반 jsonb 매핑을 우선 적용한다. Java 표현을 `String` 또는 `JsonNode`로
  통일하는 결정은 구현 전에 확정한다.
- `ChatMessageCitation.chunkRef`는 PostgreSQL FK가 아니라 Chroma 청크 식별자다.
- `Media.sourceVideoId`도 현재 DDL에는 자기 참조 FK가 없으므로 JPA 객체 관계로 만들지 않는다.
- `BotScenario.parentId`는 실제 FK가 있으나 같은 도메인의 자기 참조이므로 객체 관계 적용 후보이다.
  전체 연관관계 정책이 결정되기 전에는 ID 매핑을 유지하는 편이 기존 코드와 일관된다.
- `RagDocument.targetCollection`은 NULL 불가이며 문서 등록 시 명시적으로 전달해야 한다.
- `RagDocument.verificationStatus`는 nullable이다. `UNVERIFIED`를 자동 기본값으로 넣어 NULL 의미를
  훼손하지 않는다.
- `Notification`의 미확인 조회 인덱스는 부분 인덱스이므로 JPA `@Index`로 완전하게 표현할 수 없고
  DDL에서 관리한다.

## 2. 확정 가능한 Entity 규약

기존 프로젝트 문서에서 다음 규약은 명시적으로 확정되어 있다.

- Entity 이름은 도메인 명사 단수, 테이블 이름은 snake_case 복수형을 사용한다.
- Entity에 `@Setter`, `@Data`, public `@AllArgsConstructor`를 사용하지 않는다.
- 기본 생성자는 `@NoArgsConstructor(access = AccessLevel.PROTECTED)`를 사용한다.
- 상태 변경은 의도가 드러나는 도메인 메서드로 제공한다.
- 연관 로딩이 필요한 경우 기본 fetch 전략은 `LAZY`로 한다.
- API에서 Entity를 직접 반환하지 않는다.
- PostgreSQL named enum은 `@JdbcTypeCode(SqlTypes.NAMED_ENUM)`과 정확한
  `columnDefinition`으로 매핑하고 Java enum 라벨을 DDL 라벨과 일치시킨다.
- DDL에 `created_at`과 `updated_at`이 모두 있는 테이블만 `BaseTimeEntity` 적용 대상이 될 수 있다.
  `created_at`만 있는 테이블에는 별도 auditing 필드를 둔다.
- 스키마 정합성의 최종 검증은 실제 PostgreSQL과 `ddl-auto=validate` 조합으로 수행한다.

## 3. 구현 전에 결정해야 할 충돌

### 3.1 마이그레이션 방식

- `docs/README.md`와 과거 DB 설계 결정은 Flyway 없이 통합 DDL을 수동 관리한다고 설명한다.
- 최신 `table_design.md`는 일부 변경에 대해 운영 DB 증분 마이그레이션 절차가 필요하다고 설명한다.
- HAJA-205에는 프로젝트 마이그레이션 도구 설정이 완료 기준으로 포함되어 있으나, 이번 작업에서는
  Flyway 도입을 보류하기로 결정했다.

따라서 기존 수동 DDL 정책을 유지하고 HAJA-205의 “초기 마이그레이션”은 신규 DB용 통합 SQL과
그 검증 절차로 해석한다. 권위 있는 스키마 원본은 `HajaCheck_script.sql` 하나로 유지한다.

### 3.2 객체 연관관계 범위

2026-07-16 HAJA-199~204 구현 과정에서 다음 정책으로 확정했다.

- 모든 FK는 `Long` 식별자 필드를 영속화의 쓰기 소스로 사용한다.
- 같은 도메인 안에서 읽기 탐색이 필요한 관계만 `LAZY` 연관관계를 함께 제공한다. 이 관계는 같은 FK 컬럼을
  `insertable = false, updatable = false`로 매핑하는 읽기 전용 shadow 관계다.
- 도메인 경계를 넘는 관계는 기존 컨벤션대로 `Long` 식별자만 유지하고 Entity를 직접 참조하지 않는다.
- 연관관계가 FK의 유일한 쓰기 소스가 되는 매핑은 사용하지 않는다. 따라서 FK 변경은 식별자 필드를 통해서만 한다.

`Company`, `CompanyMembership`, `UserConsent`, `Defect`, `DefectRevision`, `Inspection`, `Media`, `Report`,
`ChatMessageCitation`, `UsageCounter`, `UserPlan`과 counsel Entity의 모든 `@ManyToOne`은 이 정책에 맞춰
스칼라 FK와 읽기 전용 지연 관계를 병행한다. 객체 연관관계 범위에 관한 미확정 사항은 없다.

### 3.3 시간 타입

- DDL의 시각 컬럼은 대부분 `timestamp with time zone`이다.
- 현재 일부 Entity는 `Instant`, 일부 auditing Entity는 `LocalDateTime`을 사용한다.

신규 Entity 구현 전에 `timestamptz`의 Java 기준 타입을 확정하고 `BaseTimeEntity`까지 동일 기준으로
정리할지 결정해야 한다.

### 3.4 테스트 초기화 방식

현재 PostgreSQL Repository 테스트는 `db/testcontainers-users-init.sql`의 축약 스키마를 사용한다.
수동 복제본을 계속 관리하면 통합 DDL과 테스트 스키마가 다시 어긋날 수 있으므로, PR 체크리스트만으로
막기보다 Gradle 테스트 리소스 처리 단계에서 `HajaCheck_script.sql`을 복사해 Testcontainers가 직접
사용하도록 구성하는 방식을 우선 적용한다. 전체 Entity에 대한 `ddl-auto=validate`도 같은 경로에서 검증한다.

**PostgreSQL 버전 불일치**: `PostgresTestSupport`의 Testcontainers는 `postgres:16` 이미지를 사용하는 반면,
위 1절의 수동 검증(`PostgreSQL 17 빈 DB에 통합 DDL 적용`)은 PG 17에서 수행했다. 두 경로 모두 표준 SQL·PG
named enum·jsonb만 사용하고 PG17 전용 기능에 의존하지 않아 당장 정합성 문제는 없지만, 버전이 갈라져
있다는 사실 자체는 향후 PG17 전용 기능(예: 특정 옵티마이저 동작) 도입 시 재검증이 필요함을 의미한다.
운영 DB 목표 버전이 확정되면 Testcontainers 이미지 태그를 그에 맞춰 통일한다.

### 3.5 `feature/inspection-create` 충돌 범위

- 원격 브랜치는 `origin/dev`보다 33커밋 뒤이고 고유 커밋은 1개이며, 열린 PR은 없다.
- 고유 커밋 작성자는 Brian이고 2026-07-15에 푸시되었다.
- 해당 브랜치의 `Inspection`도 최신 DDL의 `assigned_inspector_id`를 반영하지 않아 재정합화가 필요하다.

`assignedInspectorId` 추가는 DDL·Entity 정합화인 HAJA-25 범위에서 처리하되, Brian의 점검 생성
서비스·컨트롤러는 수정하지 않는다. 변경 사실을 공유한 뒤 Brian이 최신 `dev`로 리베이스하면서 생성 API에
필드를 반영하도록 조율한다.

### 3.6 구현 범위와 브랜치 분리

HAJA-25의 현재 목표는 하위 작업 전체 구현이다. 다만 한 기능·한 이슈·한 PR 규칙에 따라 HAJA-198
브랜치에는 분석 문서만 두고, `CompanyMembership`과 `Media` 구현은 각각 HAJA-199와 HAJA-201
브랜치로 분리한다. 아직 착수하지 않은 나머지 9개 Entity는 해당 하위 작업을 실제 시작할 때 구현한다.

**계획 대비 실제 범위 확장(의도적 예외)**: 위 계획과 달리 실제로는 6개 도메인(계정·인증, 과금·정책,
시설·점검, 결함·보고서, 채팅·상담, RAG, 공통 알림) 11개 Entity 전체가 `feature/haja-25-jpa-entity-migration`
한 브랜치·한 PR에 통합 구현되었다. 이는 CLAUDE.md의 "한 기능=한 이슈=한 PR" 원칙과 형식적으로
충돌하지만, 신규 Entity 11개가 서로 강하게 얽힌 스키마 정합화 작업(§3.2 연관관계 정책, §3.3 시간 타입
정책, §3.4 테스트 초기화 방식)을 공유하고 있어 분리 시 동일 정책 결정을 여러 PR에서 중복 반영해야
하는 비용이 더 크다고 판단해 리뷰 과정에서 의도적 예외로 승인되었다(2026-07-16). 향후 유사하게 여러
Entity가 하나의 정책 결정에 강하게 종속되는 경우, 분리보다 통합 구현이 합리적일 수 있음을 참고한다.

## 4. 현재 테스트 기준선

2026-07-16 최초 로컬 실행에서는 Docker CLI/엔진이 없어 Testcontainers의 PostgreSQL 컨테이너를
시작하지 못했고, 당시 129개 중 49개가 실패했다. 이 결과는 환경 실패 기록이며 현재 기준선이 아니다.

이후 로컬 PostgreSQL 17 격리 DB를 외부 테스트 DB 경로로 연결해 동일한 전체 테스트를 다시 실행했다.

- 명령: `backend/gradlew.bat test`
- 전체: 179개
- 성공: 179개
- 실패/오류/건너뜀: 0개
- 통합 DDL 적용 및 Hibernate `ddl-auto=validate`: 통과

따라서 HAJA-206 완료 기준인 “`./gradlew test`가 통과한다”를 2026-07-16 기준으로 충족했다.
Testcontainers 기본 이미지(`postgres:16`)와 운영 목표 PostgreSQL 버전의 통일은 §3.4에 기록한 후속 사항이다.

## 5. 권장 구현 순서

1. 3절의 정책 충돌을 확정한다.
2. 기존 9개 Entity를 최신 DDL과 정합화한다.
3. 같은 도메인 단위로 신규 Entity 11개와 enum을 구현한다.
4. 선택된 마이그레이션 정책에 따라 신규 DB 초기화 경로를 구현한다.
5. Testcontainers가 실제 초기화 경로를 사용하도록 변경한다.
6. PostgreSQL `ddl-auto=validate`, Repository 테스트, 전체 `gradlew test`를 순서대로 통과시킨다.
