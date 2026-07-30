# 기존 PostgreSQL 수동 증분 반영 절차 (Flyway 이전 — 보관)

> **문서 버전:** v0.3 · **최종 수정:** 2026-07-22 · 이전 버전 `archive/`

> ⚠️ **Flyway 도입(#359) 이후 보관 문서.** 신규 마이그레이션은 더 이상 이 디렉터리에 수동 SQL로
> 추가하지 않는다 — `backend/src/main/resources/db/migration/`의 Flyway 버전 파일(`V{n}__*.sql`)이
> 유일한 진실 소스다. 이 디렉터리와 아래 절차는 Flyway 도입 이전에 arm1 프로덕션 등 기존 DB를
> 현재 스키마로 수동 정합했던 이력의 기록으로만 남긴다(신규 DB에는 적용하지 않는다).

이 디렉터리는 이미 운영 중인 v0.3 계열 PostgreSQL을 현재
[`HajaCheck_script.sql`](../HajaCheck_script.sql) 스키마로 올리는 수동 증분 SQL을 보관한다.
Flyway 도입 전까지 신규 DB는 통합 DDL을 사용하고, 기존 DB는 아래 순서만 사용한다.
`DATABASE_URL`은 `public` 스키마와 기존 객체를 소유했거나 해당 DDL 권한을 위임받은 전용 마이그레이션
계정으로 연결해야 하며, 읽기·쓰기만 가능한 일반 애플리케이션 계정으로 실행하지 않는다.

## 적용 순서

1. DB 백업과 애플리케이션 쓰기 중지 또는 점검 시간을 확보한다.
2. `20260716_01_ha25_expand.sql`을 실행한다.
3. 아래 조회로 백필 대상을 확인하고 운영 근거에 따라 값을 채운다.
4. `20260716_02_ha25_finalize.sql`을 실행한다. 미백필·중복·오너 멤버십 불일치가 있으면 최종 제약 적용 전에 중단한다.
5. `20260716_03_ha25_verify.sql`을 실행한다.
6. `20260716_04_menu_schema_expand.sql`을 실행해 메뉴 스키마를 추가한다.
7. `20260716_05_menu_schema_verify.sql`을 실행한다.
8. `20260720_01_create_api_system_logs.sql`을 실행해 API 시스템 로그 스키마를 추가한다.
9. `20260721_01_plans_seed_free_assign.sql`을 실행해 `plans` 시드와 기존 대상 사용자의 FREE 플랜을 백필한다.
10. 새 애플리케이션을 배포하고 Hibernate `ddl-auto=validate` 통과와 애플리케이션 헬스를 확인한다.

```bash
psql -X --set ON_ERROR_STOP=1 --dbname "$DATABASE_URL" \
  --file docs/design/db/migrations/20260716_01_ha25_expand.sql

psql -X --set ON_ERROR_STOP=1 --dbname "$DATABASE_URL" \
  --file docs/design/db/migrations/20260716_02_ha25_finalize.sql

psql -X --set ON_ERROR_STOP=1 --dbname "$DATABASE_URL" \
  --file docs/design/db/migrations/20260716_03_ha25_verify.sql

psql -X --set ON_ERROR_STOP=1 --dbname "$DATABASE_URL" \
  --file docs/design/db/migrations/20260716_04_menu_schema_expand.sql

psql -X --set ON_ERROR_STOP=1 --dbname "$DATABASE_URL" \
  --file docs/design/db/migrations/20260716_05_menu_schema_verify.sql

psql -X --set ON_ERROR_STOP=1 --dbname "$DATABASE_URL" \
  --file docs/design/db/migrations/20260720_01_create_api_system_logs.sql

psql -X --set ON_ERROR_STOP=1 --dbname "$DATABASE_URL" \
  --file docs/design/db/migrations/20260721_01_plans_seed_free_assign.sql

```

변경 스크립트(`01`, `02`, `04`)는 반드시 psql 기본 autocommit 상태에서 실행한다. 각 작업은 세션 advisory
lock으로 동시 실행을 막고, HAJA-25가 기존 테이블에 추가하는 인덱스는 `CREATE INDEX CONCURRENTLY`로 생성한다. 명시적
트랜잭션으로 감싸면 PostgreSQL이 concurrent 인덱스 생성을 거부한다. finalize의 NOT NULL은
`NOT VALID` CHECK를 먼저 검증해 마지막
`SET NOT NULL`의 테이블 스캔과 잠금 시간을 줄인다.

autocommit 방식이므로 중간 실패 시 앞서 성공한 문장은 유지된다. 모든 문장은 재실행을 전제로 작성했으며,
오류 원인을 해소한 뒤 같은 파일을 다시 실행한다. `ON_ERROR_STOP=1`로 종료된 psql 연결의 세션 advisory lock은
연결 종료와 함께 자동 해제된다.

### 메뉴 스키마

`20260716_04_menu_schema_expand.sql`은 `menu_node_type`, `menus`, `menu_role_access`와 관련 인덱스·트리거를
추가한다. `trg_menu_role_access_reject_group`은 `GROUP` 메뉴에 대한 직접 role 매핑 삽입/변경을 쓰기 시점에
거부한다. 기존 행 백필·기존 테이블 인덱스 생성·테이블 재작성은 없고, `users` FK 메타데이터를 만들 때의
짧은 잠금만 발생한다. 메뉴 데이터는 운영값이 확정되지 않았으므로 자동 seed하지 않는다. 스크립트는 실행 전
`users`, `role_type`, `set_updated_at()` 기준선을 확인하고 기존 `menu_node_type`이 있다면 enum 값이 정확히
일치할 때만 재실행을 허용한다. `05` 검증 결과는 `menu_schema_ready=true` 한 행이어야 한다.
HAJA-25의 `01`~`03`을 이미 적용한 DB는 `04`부터 실행해도 되며, 메뉴 스키마만 독립 배포하는 경우에도 같은
두 파일(`04` → `05`)을 사용한다.

### v0.3 기준선 제약과 신규 테이블

`user_consents(user_id, policy_type, policy_version)`와 `inspections(facility_id, round_no)` 유니크 제약은
[`HajaCheck_script_v0.3.sql`](../archive/HajaCheck_script_v0.3.sql)부터 존재한 기준선 제약이다. HAJA-25
증분 스크립트나 이번 JPA 매핑이 새로 추가하는 제약이 아니다. `media.mime_signature_verified`도 v0.3부터
`default false not null`이므로 이번에 추가하거나 백필하는 컬럼이 아니다. v0.3을 표방하는 운영 DB에 이
제약이나 컬럼이 없다면 HAJA-25 마이그레이션을 계속하지 말고 기준선 스키마 드리프트를 먼저 조사한다.
expand와 verify는 이 세 기준선 조건을 PostgreSQL 카탈로그에서 직접 확인하고, 이름이 다른 동등 제약은
허용하되 제약 자체가 없으면 즉시 중단한다.

verify는 카탈로그 확인에 더해 두 복합 키를 `GROUP BY ... HAVING count(*) > 1`로 직접 스캔한다. 중복이
발견되면 해당 업무 키의 정본 행을 결정하고 참조·감사 이력을 정리한 뒤 다시 실행한다. 정상적인 v0.3
UNIQUE 제약 아래에서는 중복이 생길 수 없으므로, 발견 시에는 제약이 제거된 복구본이나 부분 적용 DB로 본다.

Gradle `verifyHa25SchemaInputs` 작업은 캐노니컬 DDL, Git에 추적된 v0.3 archive와 카탈로그 대조에 포함되는
모든 증분 스크립트가 실제 파일인지 `processTestResources` 전에 강제한다. 따라서 archive 파일이 현재 PR의
변경 파일 목록에 없더라도 base 브랜치에서 누락되면 컴파일·테스트 전에 빌드가 실패한다.

`company_memberships(company_id, user_id)` 유니크 제약은 HAJA-25가 새로 만드는 빈 테이블에 먼저 적용된다.
그 뒤 기존 회사·사용자 데이터를 `ON CONFLICT`가 있는 백필문으로 넣으므로, 기존 운영 행의 중복 때문에
제약 생성이 실패하는 경로는 없다. 재초대는 새 행 INSERT가 아니라 기존 비활성 행을 `PENDING`으로 되돌리는
도메인 메서드를 사용한다.

## 필수 백필

### 기존 회사 멤버십

expand는 `APPROVED + VERIFIED` 회사의 `owner_user_id`만 신뢰해 오너 멤버십을 `APPROVED`로 백필하고,
`users.company_id`를 같은 회사로 맞춘다. 승인되지 않은 회사 오너는 `PENDING`으로 유지한다.
비오너의 기존 `users.company_id`는 일단 `PENDING` 멤버십 후보로 보존하되 자동 승인하거나 즉시
`NULL`로 지우지 않는다. 동일 사용자가 승인된 여러 회사를 소유하거나 다른 회사 포인터를 가진 경우와,
운영자가 비오너 후보를 분류하지 않은 경우에는 expand가 중단된다.

```sql
select c.id as company_id, c.owner_user_id, c.status, c.verification_status,
       u.company_id as user_company_id, cm.status as membership_status, cm.approved_at
from companies c
join users u on u.id = c.owner_user_id
left join company_memberships cm
  on cm.company_id = c.id and cm.user_id = c.owner_user_id
order by c.id;

select cm.company_id, c.name as company_name, cm.user_id, u.email,
       cm.status, u.company_id as user_company_id
from company_memberships cm
join companies c on c.id = cm.company_id
join users u on u.id = cm.user_id
where cm.status = 'PENDING'::company_membership_status_type
order by cm.company_id, cm.user_id;
```

비오너 후보는 기존 초대·재직 명부·관리자 승인 기록 등 감사 근거와 대조해 한 건씩 분류한다.

- 정상 구성원으로 확인되고 회사도 `APPROVED + VERIFIED`이면 아래처럼 멤버십을 명시적으로 승인한다.
  `approved_at`에는 실제 확인 가능한 승인 시각을 사용하고, 알 수 없으면 마이그레이션 검토 완료 시각을
  사용했다는 근거를 운영 기록에 남긴다. 한 사용자를 여러 회사에 승인하려 하면 부분 유니크 인덱스가 거부한다.
- 근거가 없거나 회사가 승인·검증되지 않은 후보는 멤버십을 `PENDING`으로 유지하고 해당 사용자의
  `users.company_id`만 `NULL`로 제거한다. 이후 정식 초대·승인 절차를 거쳐야 회사 접근권을 얻는다.

```sql
-- 정상 구성원으로 확인된 단일 후보 승인 예시
update company_memberships cm
set status = 'APPROVED'::company_membership_status_type,
    approved_at = :'confirmed_approved_at'::timestamptz,
    invited_by = coalesce(cm.invited_by, c.owner_user_id),
    expires_at = null,
    revoked_at = null,
    updated_at = now()
from companies c
where cm.company_id = :company_id
  and cm.user_id = :user_id
  and c.id = cm.company_id
  and c.status = 'APPROVED'::company_status_type
  and c.verification_status = 'VERIFIED'::business_verification_status_type
  and cm.status = 'PENDING'::company_membership_status_type;

-- 감사 근거가 없는 단일 후보 격리 예시
update users
set company_id = null,
    updated_at = now()
where id = :user_id
  and company_id = :company_id;
```

분류를 마친 뒤 expand를 다시 실행한다. 유효한 `APPROVED` 멤버십 없이 `company_id`가 남은 사용자가
한 명이라도 있으면 expand가 계속 중단되므로, 정상 구성원의 접근권이 자동으로 사라지거나 미승인 사용자가
레거시 포인터만으로 회사 권한을 유지하는 상태를 모두 방지한다. finalize는 남아 있는 `PENDING` 건수를
경고하고, 승인·검증된 회사에 유효한 오너 멤버십 또는 일치하는 `users.company_id`가 없으면 중단한다.
finalize와 verify는 애플리케이션 쓰기가 완전히 멈추지 않아 분류 이후 새 stale `company_id`가 생긴 경우도
각각 차단·확인한다.

### 점검 담당자

`created_by`는 생성자일 뿐 담당 검사자라는 보장이 없으므로 자동 복사하지 않는다.

`assigned_inspector_id`의 FK는 `users(id)`만 참조할 뿐 회사 경계는 강제하지 않는다.
`AuthService.validateAssignableInspector`가 매 생성 시점에 요청자(`created_by`)와 배정자가 같은 회사
(`users.company_id`)인지 이미 검증하지만 이는 애플리케이션 계층 방어일 뿐이라, finalize는
`assigned_inspector_id`가 `NOT NULL`로 확정된 뒤(=백필 완료 후) `trg_inspections_check_assigned_inspector_company`
트리거를 설치해 DB 레벨에서도 같은 불변식을 강제한다. 백필 이전 데이터를 막지 않도록 finalize 단계에서만
설치하며, verify는 이 트리거의 존재를 확인한다.

```sql
select id, facility_id, created_by, inspection_date
from inspections
where assigned_inspector_id is null
order by id;

-- 확정된 배정 자료를 근거로 행별 또는 임시 매핑 테이블을 이용해 갱신한다.
update inspections
set assigned_inspector_id = :confirmed_inspector_id
where id = :inspection_id;
```

### RAG 대상 컬렉션

기존 문서가 `REGULATIONS`인지 `DEFECT_KB`인지 제목이나 source type만으로 추정하지 않는다.

```sql
select id, title, source_type, file_url
from rag_documents
where target_collection is null
order by id;

update rag_documents
set target_collection = :'confirmed_target_collection'::rag_target_collection_type
where id = :document_id;
```

### 기존 인용 위치와 본문

기존 `snippet`이 NULL이거나 `locator`가 없는 행은 원본 문서 또는 Chroma 청크를 대조해 채운다.

```sql
select id, message_id, document_id, chunk_ref, snippet
from chat_message_citations
where locator is null or snippet is null
order by id;

update chat_message_citations
set locator = :'confirmed_locator',
    snippet = :'confirmed_snippet'
where id = :citation_id;
```

### 활성 구독 중복

finalize 단계는 사용자 또는 회사별 `ACTIVE` 구독이 둘 이상이면 중단한다. 결제·구독 이력을 확인해
유효한 한 행만 남기고 나머지는 적절한 상태로 전환한 뒤 재실행한다.

### 상태 전이 낙관적 락

`companies`, `company_memberships`, `defects`, `reports`, `counsel_tickets`, `rag_documents`,
`notifications`에는 JPA `@Version`에 대응하는 `lock_version bigint default 0 not null`을 적용한다.
`notifications`는 상태 머신은 아니지만(단순 읽음 플래그 토글), 이 PR의 다른 모든 가변 테이블과의
일관성을 위해 포함했다(HAJA-25 P2). expand는 기존 행을
상수 기본값 0으로 보강하고, finalize는 `NOT VALID CHECK` 검증 후 NOT NULL을 확정한다. 업무 버전을 뜻하는
`reports.version`과 동시성 제어용 `reports.lock_version`은 서로 다른 컬럼이다.

finalize는 NULL 잔존 행이 있으면 승격 전에 중단하고, verify는 모든 대상 테이블의 실제 행을 다시 스캔해
NULL이 없음을 확인한 뒤 PostgreSQL 카탈로그에서 `bigint DEFAULT 0 NOT NULL` 속성까지 검증한다.

`Ha25IncrementalMigrationTest`는 별도 PostgreSQL 16에서 v0.3 기준 DDL과 기존 데이터 fixture를 적재한 뒤
미분류 비오너 때문에 expand가 중단되는지 확인 → 감사 근거가 있다고 가정한 명시적 멤버십 승인 백필 →
expand 2회 → `lock_version=0` 백필 확인 → 미백필 finalize 차단 확인 → 나머지 필수 백필 →
finalize 2회 → verify를 실행한다. 같은 PostgreSQL 인스턴스의 독립 DB에 캐노니컬 DDL을 추가로
적용하고, 두 DB의 enum·테이블·컬럼 타입/NULL/default·제약·인덱스·트리거·시퀀스·함수를
정규화한 PostgreSQL 카탈로그 스냅샷으로 직접 대조한다. 컬럼의 물리적 순서만 증분 경로의 정상적
차이로 제외하고 실행 의미가 다르면 테스트를 실패시킨다. 이후 Hibernate `ddl-auto=validate`로 전체
Entity 매핑을 대조하고, 같은 이름의 잘못된 인덱스·nullable 컬럼·기본값 누락·잘못된 FK 대상·
replica-only 트리거를 차례로 주입해 verify가 각각 거부하는지도 확인한다.

`MenuSchemaMigrationTest`는 같은 v0.3 기준선에서 메뉴 expand를 2회 실행한 뒤 CHECK·FK 삭제 정책·역할 매핑
CASCADE·`updated_at` 트리거를 검증한다. GROUP 메뉴의 직접 역할 매핑과 같은 이름의 잘못된 FK·인덱스를
주입해 `20260716_05_menu_schema_verify.sql`이 의미 드리프트를 거부하는지도 확인한다.

`ApiSystemLogSchemaMigrationTest`는 빈 PostgreSQL 16에 `20260720_01_create_api_system_logs.sql`을 2회 적용해
재실행 가능성을 확인한다. 전용 테스트는 `POSTGRES_USER=hajacheck`이며 `postgres` DB 역할이 없는
PostgreSQL 16에서 실행해 owner 이식성을 검증하고, identity·기본값·CHECK·인덱스 3개·`user_id` FK 부재와
`request_id` 비유니크 정책을 확인한다. 같은 이름의 CHECK, request ID UNIQUE 인덱스,
`varchar_pattern_ops` 인덱스, 예상 밖 expression 인덱스를 주입한 뒤 재실행하면 즉시 중단되는지도 검증한다.
`Ha25IncrementalMigrationTest`도 이 독립 파일을 전체 증분 경로에 포함한 뒤 캐노니컬 DDL 신규 설치 DB와
PostgreSQL 카탈로그를 대조한다.

verify는 객체 이름의 존재만 보지 않는다. 필수 컬럼의 타입·기본값·NULL 정책, 인덱스의 대상 테이블·키 순서·
UNIQUE·부분 조건·valid/ready 상태, `updated_at` 트리거의 대상·함수·시점·행 단위·활성 상태를 PostgreSQL
카탈로그에서 대조한다. 따라서 Flyway 없이도 캐노니컬 신규 설치 경로와 수동 증분 경로의 의미적 드리프트를
기본 Gradle 테스트에서 탐지한다.

Docker를 사용할 수 없는 검증 환경에서 외부 PostgreSQL을 연결할 때는 테스트가 마이그레이션을 대신 실행하지
않는다. 폐기 가능한 DB에 이 문서의 전체 절차를 먼저 적용한 경우에만 아래처럼 명시적으로 validate-only 경로를
활성화한다. `HA25_MIGRATION_EXTERNAL_SCHEMA_READY=true` 없이 URL만 설정하면 테스트는 즉시 실패한다.

```bash
HA25_MIGRATION_POSTGRES_URL="<JDBC_URL>" \
HA25_MIGRATION_POSTGRES_USERNAME="<DB_USERNAME>" \
HA25_MIGRATION_POSTGRES_PASSWORD="<DB_PASSWORD>" \
HA25_MIGRATION_EXTERNAL_SCHEMA_READY="true" \
./gradlew test --tests com.hajacheck.support.Ha25IncrementalMigrationTest
```

### 상담 세션 중복 배정

`counsel_tickets.session_id`는 전문상담 세션 하나와 상담 티켓 하나의 1:1 배정을 뜻한다. finalize는
동일한 non-null `session_id`가 여러 티켓에 연결된 기존 데이터가 있으면 중단한다. 중복 원인을 확인해
유효한 티켓 하나만 남기거나 세션을 분리한 뒤 재실행한다. 정리가 끝나면
`uq_counsel_tickets_session` 부분 유니크 인덱스가 이후 중복 배정을 차단한다.

### 실패한 concurrent 인덱스 복구

중단된 `CREATE INDEX CONCURRENTLY`는 같은 이름의 INVALID 인덱스를 남길 수 있다. verify가 누락 또는
INVALID 인덱스를 보고하면 아래 조회로 확인하고 해당 인덱스만 concurrent 방식으로 삭제한 뒤 expand 또는
finalize를 다시 실행한다.

```sql
select index_class.relname, index_meta.indisready, index_meta.indisvalid
from pg_index index_meta
join pg_class index_class on index_class.oid = index_meta.indexrelid
join pg_namespace namespace on namespace.oid = index_class.relnamespace
where namespace.nspname = 'public'
  and (not index_meta.indisready or not index_meta.indisvalid);

-- 예: INVALID로 확인된 이름만 지정한다.
drop index concurrently if exists public.idx_rag_documents_target_collection;
```

## 그 밖의 독립 마이그레이션

`20260719_01_ap020_notification_history_index.sql`은 위 HAJA-25 expand/finalize/verify 체인과 무관한
단일 목적 파일이다(AP-020 알림 센터 조회용 인덱스 추가) — 파일 상단 안내에 따라 단독으로 적용한다.

`20260720_01_create_api_system_logs.sql`도 HAJA-25와 메뉴 expand/verify 체인에 의존하지 않는 독립
마이그레이션이다(#497/HAJA-299). 기존 테이블을 변경하거나 백필하지 않고 빈 `api_system_logs` 테이블과
일반 인덱스 3개만 추가한다. 세션 advisory lock으로 동시 실행을 막고 끝에서 명시적으로 해제한다.
동일 이름 테이블·제약·인덱스가 이미 있으면 캐노니컬 DDL과 컬럼 타입·NULL·기본값·identity·CHECK·PK가
정확히 일치할 때만 재실행을 허용한다. PK를 제외한 모든 인덱스의 이름·UNIQUE·valid/ready·접근 방식·키와
정렬·include·operator class·collation·predicate·expression까지 전체 대조하며, `user_id` FK나 request ID
UNIQUE 또는 `varchar_pattern_ops`를 포함한 예상 밖 의미가 있으면 즉시 중단한다. 신규 빈 테이블이라
`CREATE INDEX CONCURRENTLY`는 사용하지 않는다.

이 파일은 `postgres` 같은 환경별 역할명을 하드코딩하거나 테이블 owner를 변경하지 않는다. 새 테이블은
마이그레이션 실행 계정이 소유한다. 따라서 `POSTGRES_USER=hajacheck`처럼 `postgres` DB 역할이 없는 표준
PostgreSQL 16 환경에서도 실행할 수 있다. 반대로 이 이식성은 애플리케이션 계정의 append-only 권한을
자동으로 보장한다는 뜻이 아니다.

운영 적용 순서상 위 전체 체인을 처음부터 실행할 때는 메뉴 검증 후 API 시스템 로그 마이그레이션을 적용한다.
HAJA-25와 메뉴 스키마를 이미 적용한 DB에는 이 파일만 단독으로 실행해도 된다. 이 테이블에 운영 로그가
쌓인 뒤에는 애플리케이션만 이전 버전으로 롤백하고 테이블은 유지하며, 자동 `DROP TABLE` 롤백은 하지 않는다.

### API 시스템 로그 수집 활성화 게이트

- 현재처럼 애플리케이션이 동일 DB superuser/owner를 쓰면 INSERT-only를 강제할 수 없다. 수집 전 환경별
  이름의 owner(DDL), writer(INSERT), reader(SELECT), retention(`user_id=NULL` UPDATE와 만료 DELETE) 역할을
  분리하고 실제 `information_schema.role_table_grants`·`has_table_privilege` 결과를 운영 기록에 남긴다.
  이 저장소 SQL은 환경별 역할명을 알 수 없으므로 실행 불가능한 `GRANT`/`REVOKE`를 하드코딩하지 않는다.
- 정상 수집 계정에는 INSERT만, 조회 계정에는 SELECT만 부여한다. retention 계정만 탈퇴·익명화 목적의
  `user_id=NULL` UPDATE와 `created_at` 기준 DELETE를 수행한다. owner 권한은 런타임 계정에서 제거한다.
- 기본 보존기간은 30일이다. 일일 batch가 `created_at < now() - interval '30 days'` 행을 작은 단위로
  삭제하며, 백업·덤프·복제본에도 30일 이내 삭제 수명주기를 적용한다. 탈퇴·익명화 시에는 보존기간 내
  관련 행의 `user_id`를 NULL로 바꾼다.
- trusted proxy CIDR/hop 검증 전에는 forwarded IP를 사용하지 않는다. 직접 peer 또는 검증된 proxy 체인에서
  판정한 주소만 IPv4 `/24`·IPv6 `/48` 네트워크로 축약해 저장하며, 이 값은 감사·차단의 단독 근거가 아니다.
- 위 역할 분리, retention, 익명화, 프록시 신뢰 설정과 후속 수집 필터의 정제 테스트가 완료되기 전에는
  API 시스템 로그 수집 기능을 활성화하지 않는다.

`20260721_01_plans_seed_free_assign.sql`도 마찬가지로 독립 파일이다(#517 / HAJA-308 가입 시 FREE 플랜
자동 배정) — `plans` 시드 3건(FREE/STANDARD/ENTERPRISE, `ON CONFLICT DO NOTHING`)과, 기존 회사·무소속
개인 활성 사용자 중 ACTIVE/UPGRADE_REQUESTED `user_plans`이 없는 대상에 FREE `ACTIVE` 행을 채우는 백필을
함께 수행한다. 신규 설치는 `HajaCheck_script.sql`에 이미 반영된 동일 시드를 사용하므로 이 파일이 필요
없고, 기존 운영 DB만 파일 상단 안내에 따라 단독으로 적용한다.

`20260722_01_platform_admin_role.sql`도 독립 파일이다(#534 / #535 플랫폼 관리자 콘솔 선행 작업) —
`role_type` PG enum에 `PLATFORM_ADMIN` 라벨을 추가한다(`ALTER TYPE ... ADD VALUE IF NOT EXISTS`,
autocommit 필요·재실행 안전). 신규 설치는 `HajaCheck_script.sql`에 이미 반영돼 있어 이 파일이 필요
없고, 기존 운영/개발 DB만 단독으로 적용한다. 이 값이 없는 상태에서 `role='PLATFORM_ADMIN'` 사용자가
로그인하면 `InternalAuthenticationServiceException`(No enum constant Role.PLATFORM_ADMIN)이 발생한다.

## 롤백 원칙

expand 단계는 기존 컬럼을 삭제하거나 값을 변환하지 않는다. finalize 이후에는 새 애플리케이션이 신규
컬럼에 의존하므로 SQL 역변경 대신 애플리케이션 롤백과 DB 백업 복원을 함께 판단한다. 운영 데이터가
기록된 `company_memberships` 또는 신규 컬럼을 임의로 DROP하지 않는다.
