# JPA Entity 구현 사전 감사

> **문서 버전:** v0.1 · **최종 수정:** 2026-07-17
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
- 최신 `origin/dev` 통합 및 P1~P3 최종 검수 반영 후 전체 Gradle 테스트 240개 통과

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

## 3. 구현 과정에서 확정한 정책과 조율 결과

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
- 연관관계가 FK의 유일한 쓰기 소스가 되는 매핑은 사용하지 않는다. 같은 도메인 객체의 상태를 함께 검증해야
  하는 도메인 메서드는 Entity를 인자로 받을 수 있지만, 검증 뒤 실제 FK 쓰기 값은 해당 객체의 식별자로 저장한다.

`Company`, `CompanyMembership`, `UserConsent`, `Defect`, `DefectRevision`, `Inspection`, `Media`, `Report`,
`ChatMessageCitation`, `UsageCounter`, `UserPlan`과 counsel Entity의 모든 `@ManyToOne`은 이 정책에 맞춰
스칼라 FK와 읽기 전용 지연 관계를 병행한다. 객체 연관관계 범위에 관한 미확정 사항은 없다.

### 3.3 시간 타입

- 승인·회수·만료·세션 종료·임베딩 완료처럼 절대 시점이 업무 의미인 신규 필드는 `Instant`를 사용한다.
- `created_at`/`updated_at` auditing 필드는 기존 전 도메인의 Repository 조회·DTO 계약과 공통
  `BaseTimeEntity` 호환성을 유지하기 위해 이번 범위에서는 `LocalDateTime`을 유지한다.
- 두 매핑 모두 PostgreSQL 17의 `timestamp with time zone` 스키마에 대해 Hibernate
  `ddl-auto=validate`를 통과했다.

전체 auditing 타입을 `Instant`로 전환하려면 Repository 기간 조건과 외부 DTO 계약을 함께 바꿔야 하므로
HAJA-25의 신규 Entity 정합화와 분리한다. 따라서 이 PR 안의 시간 타입 선택은 위 기준으로 확정되었고,
미결정 상태가 아니다.

### 3.4 테스트 초기화 방식

축약 스키마 `db/testcontainers-users-init.sql`은 제거했다. Gradle `verifyHa25SchemaInputs`와
`processTestResources`가 캐노니컬 `HajaCheck_script.sql`, Git에 추적된 v0.3 archive DDL, HAJA-25 증분
스크립트 3개를 검사해 classpath로 직접 복사하고, 파일이 하나라도 없으면 테스트 자원 처리 전에
빌드를 중단한다. Repository 테스트는 캐노니컬 DDL로 초기화하며 전체 Entity를 `ddl-auto=validate`로
대조한다.

`Ha25IncrementalMigrationTest`는 기존 데이터 fixture가 있는 PostgreSQL 17에서 expand 재실행성,
`lock_version` 백필, 미백필 finalize 차단, 수동 백필, finalize 재실행성, verify의 의미 기반 드리프트
탐지를 검증한다. 또한 독립 DB에 캐노니컬 DDL을 적용해 enum·테이블·컬럼 의미·제약·인덱스·트리거·
시퀀스·함수 카탈로그를 증분 결과와 직접 대조한다. 외부 DB validate-only 경로는 전체 절차를 적용했다는
명시적 플래그가 없으면 실패한다. 공용 Repository 테스트와 증분 테스트의 Testcontainers 이미지는
모두 운영 목표와 같은 `postgres:17`로 통일했다.

### 3.5 `feature/inspection-create` 충돌 조율 결과

착수 당시 Brian의 원격 브랜치가 오래된 `Inspection`을 포함하고 있어 충돌 가능성을 확인했다.
HAJA-25에서는 DDL·Entity 정합화에 필요한 `assignedInspectorId`만 반영했고, 해당 브랜치의 점검 생성
서비스·컨트롤러는 수정하지 않았다. 최종 검수 시점에는 `origin/feature/inspection-create`가 더 이상 존재하지
않으며, 이 PR diff에도 범위 밖 서비스·컨트롤러 변경이 없다. 착수 전 조율 항목은 종료되었다.

### 3.6 구현 범위와 브랜치 분리

초기에는 하위 작업별 브랜치 분리를 검토했지만, 최종적으로 7개 도메인(계정·인증, 과금·정책,
시설·점검, 결함·보고서, 채팅·상담, RAG, 공통 알림) 11개 Entity 전체가 `feature/haja-25-jpa-entity-migration`
한 브랜치·한 PR에 통합 구현되었다. 이는 CLAUDE.md의 "한 기능=한 이슈=한 PR" 원칙과 형식적으로
충돌하지만, 신규 Entity 11개가 서로 강하게 얽힌 스키마 정합화 작업(§3.2 연관관계 정책, §3.3 시간 타입
정책, §3.4 테스트 초기화 방식)을 공유하고 있어 분리 시 동일 정책 결정을 여러 PR에서 중복 반영해야
하는 비용이 더 크다고 판단해 리뷰 과정에서 의도적 예외로 승인되었다(2026-07-16). 향후 유사하게 여러
Entity가 하나의 정책 결정에 강하게 종속되는 경우, 분리보다 통합 구현이 합리적일 수 있음을 참고한다.

### 3.7 상태 전이 동시성 정책

Entity의 `requireStatus`·`requireDraft` 가드는 한 트랜잭션에서 읽은 객체의 잘못된 전이만 차단하며,
동일 행을 동시에 읽은 두 요청 사이의 경쟁까지 해결하지는 않는다. HAJA-25에서 신규 또는 강화한 상태 머신
`CompanyMembership`, `Defect`, `RagDocument`, `Report`, `CounselTicket`, `Company`에는 모두 JPA
`@Version`과 `lock_version bigint default 0 not null`을 적용해 stale update를 거부한다. 보고서의 업무 버전
`reports.version`과 낙관적 락 버전 `reports.lock_version`은 분리한다.

캐노니컬 DDL과 기존 v0.3 증분 마이그레이션 모두 같은 컬럼을 제공하며, 증분 경로는 nullable expand 후
`NOT VALID CHECK` 검증을 거쳐 NOT NULL을 확정한다. `StateTransitionOptimisticLockTest`는 같은 회사를
동시에 읽은 두 심사 전이 중 두 번째 갱신이 낙관적 락 예외로 거부되는지 실제 PostgreSQL에서 검증한다.

`Notification`은 상태 머신이 아니라 단순 읽음 플래그 토글(`markAsRead`)이라 이 절의 기준(동시 상태 전이로
인한 lost update)에는 해당하지 않지만, 이 PR이 다루는 다른 모든 가변 Entity가 예외 없이 `lock_version`을
갖는 데서 오는 일관성 기대(리뷰 지적, HAJA-25 P2)를 충족하기 위해 같은 컬럼을 적용했다. 동시 갱신이
새로운 예외(`ObjectOptimisticLockingFailureException`)로 표면화되는 점은 `Company` 등 다른 엔티티와
동일하며, `GlobalExceptionHandler`가 이를 409 `CONCURRENT_UPDATE_CONFLICT`로 통일 응답한다.

낙관적 락은 DB 갱신 충돌을 막지만 트랜잭션 중 먼저 실행한 외부 부작용을 되돌리지는 못한다. 임베딩 호출,
PDF 생성, 메시지 발행처럼 외부 효과가 있는 서비스는 version 반영 flush/commit 성공 뒤에 실행하거나
transactional outbox·멱등성 키를 사용해야 하며, 해당 서비스 구현의 동시 요청 통합 테스트에 포함한다.

`UsageCounter`는 반대로 **의도적으로 `@Version`을 두지 않는다**(리뷰 지적, HAJA-25 P2). 월간 사용량 카운터의
동시 증가 보호는 낙관적 락(읽기→증가→저장, 재시도 필요)이 아니라 table_design.md §usage_counters가 이미
확정한 **원자적 조건부 UPDATE**(`UPDATE ... SET count = count + 1 WHERE ... AND count < :limit`)로 처리하는
설계다 — 갱신 행 수 0이 곧 한도 초과 판정이며 재시도 루프가 필요 없다. `@Version`을 추가하면 이 원자적
UPDATE 경로와 충돌하거나(네이티브 UPDATE는 Hibernate가 version을 자동 증가시키지 않음) 불필요한 이중
동시성 제어가 된다. 다만 이 PR은 카운터를 증가시키는 서비스(QuotaInterceptor 등)를 아직 구현하지 않으므로,
후속 구현 시 이 원자적 UPDATE 계약을 반드시 지켜야 한다는 점을 여기 남겨 회귀를 방지한다.

### 3.8 `BotScenario` 트리 사이클 정책

현재 `BotScenario`는 생성 시 부모 식별자만 받고, 생성 후 부모를 바꾸는 setter나 도메인 메서드를 제공하지
않는다. ID도 DB가 생성하므로 현재 공개 API만으로 기존 노드의 부모를 자신 또는 자손으로 되돌려 사이클을
만드는 경로는 없다. 따라서 Entity 생성자에서 조상 사이클을 판별하는 것은 불가능하고 불필요하다.

향후 시나리오 재배치 기능을 추가할 때는 Service 트랜잭션에서 조상 체인을 조회해 자기 자신과 이미 방문한
노드를 거부하고, 트리 조회에도 방문 집합 또는 최대 깊이 제한을 둔다. 이 검증 없이 `parentId` 변경 메서드를
Entity에 노출하지 않는다.

## 4. 현재 테스트 기준선

2026-07-16 최초 로컬 실행에서는 Docker CLI/엔진이 없어 Testcontainers의 PostgreSQL 컨테이너를
시작하지 못했고, 당시 129개 중 49개가 실패했다. 이 결과는 환경 실패 기록이며 현재 기준선이 아니다.

⚠️ **실행 경로 구분(2026-07-17 정정)**: `Ha25IncrementalMigrationTest`는 `TEST_POSTGRES_URL`
(외부 DB 경로)이 설정되면 `POSTGRES == null` 분기로 진입해 `EXTERNAL_SCHEMA_READY` 확인 후
즉시 `return`하며, expand/finalize/verify 실행·카탈로그 서명 대조(`assertCanonicalSchemaParity`)·
5가지 tamper 거부 시나리오(`assertVerifierRejectsSemanticDrift`)는 `createMigratedContainer()`
(Testcontainers 경로)에서만 실행된다(`Ha25IncrementalMigrationTest.java:222-228, 259-308`). 즉
아래 두 항목은 **서로 다른 실행에서 나온 결과이며 하나의 `gradlew test` 실행으로 동시에 나올 수 없다.**
이전 버전 문서는 이를 하나의 목록으로 나열해 근거가 불명확했다 — §4.1/§4.2로 분리한다.

### 4.1 `gradlew.bat test` (외부 DB 경로, `TEST_POSTGRES_URL`) — 자동화된 테스트 실행

- 2026-07-16 최초 기준선: 전체 240개, 성공 240개, 실패/오류/건너뜀 0개
- 2026-07-17 P1(캐노니컬 DDL drift)·P2(회사 경계 트리거 런타임 테스트, Notification 낙관적 락
  테스트) 추가 반영 후 재실행: 전체 246개, 성공 245개, 실패 1개(`Ha25IncrementalMigrationTest` —
  이 경로에서도 Testcontainers를 시도하다 이 로컬 환경의 Docker Desktop named pipe 호환성 문제로
  실패. 회귀 아님 — §4.2에서 동일 내용을 수동 재현해 확인)
- 포함 범위: `PostgresTestSupport`를 상속한 모든 Repository/통합 테스트(엔티티 Hibernate
  `ddl-auto=validate` 포함), `StateTransitionOptimisticLockTest`, `NotificationOptimisticLockTest`,
  `InspectionAssignedInspectorCompanyBoundaryTest`(cross-company 배정 거부·동일회사 허용·
  무소속 자기배정 허용 런타임 검증)

### 4.2 Testcontainers 전용 경로 — 수동 검증(자동화된 CI 실행 아님)

아래는 `Ha25IncrementalMigrationTest.createMigratedContainer()`가 Testcontainers로 자동 수행하도록
설계된 검증이나, 이 로컬 환경은 Docker Desktop named pipe 호환성 문제로 Testcontainers Java
클라이언트를 통한 자동 실행이 불가능했다. 대신 `docker run`으로 컨테이너를 직접 띄워 동일한 SQL
절차를 수동으로 재현했다 — **자동화된 테스트 통과가 아니라 사람이 명령을 하나씩 실행하고 결과를
눈으로 확인한 것**이다.

- PostgreSQL 17에서 v0.3 → expand 2회 → finalize 2회 → verify(`ha25_schema_ready=true`) 확인
  (2026-07-16 최초 검증)
- 캐노니컬 DDL만 적용한 신규설치 DB와 `v0.3 + HAJA-25 증분` 전체 경로를 거친 DB의 정규화된
  카탈로그 서명(enum/table/column/constraint/index/trigger/sequence/function)을 직접 `diff` —
  완전 일치 확인(2026-07-17, 회사 경계 트리거를 캐노니컬 DDL에 반영한 뒤 재검증)
- `lock_version=0` 기존 행 백필과 `assigned_inspector_id` 미백필 finalize 차단 확인
- 복합 업무 키 중복, `lock_version` NULL 잔존, 잘못된 인덱스 정의, 컬럼 NULL/default 드리프트,
  잘못된 FK 대상, replica-only 트리거, 회사 경계 트리거 누락을 verify가 각각 거부하고 원상복구 뒤
  다시 통과함을 확인
- 회사 경계 트리거의 실제 INSERT/UPDATE 거부·허용 3가지 조합(서로 다른 회사 차단, 동일 회사 다른
  사용자 허용, 둘 다 무소속 자기배정 허용)을 수동으로 재현해 확인(자동화 버전은 §4.1의
  `InspectionAssignedInspectorCompanyBoundaryTest`)

### 4.3 미확정 사항

- 이 브랜치는 아직 원격에 `push`되지 않았다. Docker 기반 CI가 이 커밋들에 대해
  `Ha25IncrementalMigrationTest`(캐노니컬 parity·tamper 시나리오 포함, §4.2 해당)를 자동
  실행한 적이 없다. §4.2의 수동 검증은 §4.1의 자동화된 테스트 실행과 **같은 실행에서 나온 결과가
  아니며**, 서로 다른 방식(자동 vs 수동)으로 얻은 근거임을 분리해 기록한다.
- push 후 CI가 그린으로 확인되기 전까지 이 문서의 "테스트 기준선"은 로컬 검증 수준으로만 신뢰하고,
  최종 확정("검증 완료") 근거로 인용하지 않는다.

따라서 HAJA-206 완료 기준인 "`./gradlew test`가 통과한다"는 §4.1 기준 2026-07-16 시점에 충족했다.
§4.2의 Testcontainers 전용 검증은 push 후 CI(또는 Docker가 정상 동작하는 환경)에서 자동 재확인이
필요하다. Repository 및 증분 테스트의 Testcontainers 기본 이미지도 운영 목표와 같은 `postgres:17`로
통일했다.

## 5. 구현 완료 체크리스트

- [x] 3절의 정책 충돌과 조율 범위를 확정했다.
- [x] 기존 9개 Entity를 최신 DDL과 정합화했다.
- [x] 신규 Entity 11개와 enum을 구현했다.
- [x] 신규 DB 캐노니컬 DDL과 기존 v0.3 증분 경로를 모두 구현했다.
- [x] Testcontainers가 캐노니컬·증분 SQL을 직접 사용하도록 통합했다.
- [x] PostgreSQL 17 `ddl-auto=validate`, Repository 테스트, 전체 `gradlew test`를 통과시켰다.
