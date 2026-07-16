# HAJA-25 기존 PostgreSQL 증분 반영 절차

> **문서 버전:** v0.1 · **최종 수정:** 2026-07-17 · 이전 버전 `archive/`

이 디렉터리는 이미 운영 중인 v0.3 계열 PostgreSQL을 현재
[`HajaCheck_script.sql`](../HajaCheck_script.sql) 스키마로 올리는 수동 증분 SQL을 보관한다.
Flyway 도입 전까지 신규 DB는 통합 DDL을 사용하고, 기존 DB는 아래 순서만 사용한다.

## 적용 순서

1. DB 백업과 애플리케이션 쓰기 중지 또는 점검 시간을 확보한다.
2. `20260716_01_ha25_expand.sql`을 실행한다.
3. 아래 조회로 백필 대상을 확인하고 운영 근거에 따라 값을 채운다.
4. `20260716_02_ha25_finalize.sql`을 실행한다. 미백필·중복·오너 멤버십 불일치가 있으면 최종 제약 적용 전에 중단한다.
5. `20260716_03_ha25_verify.sql`을 실행한다.
6. 새 애플리케이션을 배포하고 Hibernate `ddl-auto=validate` 통과와 애플리케이션 헬스를 확인한다.

```bash
psql -X --set ON_ERROR_STOP=1 --dbname "$DATABASE_URL" \
  --file docs/design/db/migrations/20260716_01_ha25_expand.sql

psql -X --set ON_ERROR_STOP=1 --dbname "$DATABASE_URL" \
  --file docs/design/db/migrations/20260716_02_ha25_finalize.sql

psql -X --set ON_ERROR_STOP=1 --dbname "$DATABASE_URL" \
  --file docs/design/db/migrations/20260716_03_ha25_verify.sql

```

expand와 finalize는 반드시 psql 기본 autocommit 상태에서 실행한다. 두 스크립트는 세션 advisory lock으로
동시 실행을 막고, 기존 테이블에 추가하는 인덱스는 `CREATE INDEX CONCURRENTLY`로 생성한다. 명시적
트랜잭션으로 감싸면 PostgreSQL이 concurrent 인덱스 생성을 거부한다. finalize의 NOT NULL은
`NOT VALID` CHECK를 먼저 검증해 마지막
`SET NOT NULL`의 테이블 스캔과 잠금 시간을 줄인다.

autocommit 방식이므로 중간 실패 시 앞서 성공한 문장은 유지된다. 모든 문장은 재실행을 전제로 작성했으며,
오류 원인을 해소한 뒤 같은 파일을 다시 실행한다. `ON_ERROR_STOP=1`로 종료된 psql 연결의 세션 advisory lock은
연결 종료와 함께 자동 해제된다.

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

Gradle `verifyHa25SchemaInputs` 작업은 캐노니컬 DDL, Git에 추적된 v0.3 archive, 증분 스크립트
3개가 모두 실제 파일인지 `processTestResources` 전에 강제한다. 따라서 archive 파일이 현재 PR의
변경 파일 목록에 없더라도 base 브랜치에서 누락되면 컴파일·테스트 전에 빌드가 실패한다.

`company_memberships(company_id, user_id)` 유니크 제약은 HAJA-25가 새로 만드는 빈 테이블에 먼저 적용된다.
그 뒤 기존 회사·사용자 데이터를 `ON CONFLICT`가 있는 백필문으로 넣으므로, 기존 운영 행의 중복 때문에
제약 생성이 실패하는 경로는 없다. 재초대는 새 행 INSERT가 아니라 기존 비활성 행을 `PENDING`으로 되돌리는
도메인 메서드를 사용한다.

## 필수 백필

### 기존 회사 멤버십

expand는 `APPROVED + VERIFIED` 회사의 `owner_user_id`만 신뢰해 오너 멤버십을 `APPROVED`로 백필하고,
`users.company_id`를 같은 회사로 맞춘다. 승인되지 않은 회사 오너와 근거가 없는 비오너 `users.company_id`는
`PENDING`으로 격리한다. 동일 사용자가 승인된 여러 회사를 소유하거나 다른 회사 포인터를 가진 경우에는
자동 덮어쓰지 않고 중단한다.

```sql
select c.id as company_id, c.owner_user_id, c.status, c.verification_status,
       u.company_id as user_company_id, cm.status as membership_status, cm.approved_at
from companies c
join users u on u.id = c.owner_user_id
left join company_memberships cm
  on cm.company_id = c.id and cm.user_id = c.owner_user_id
order by c.id;

select company_id, user_id, status
from company_memberships
where status = 'PENDING'::company_membership_status_type
order by company_id, user_id;
```

finalize는 정상적으로 격리된 `PENDING` 건수를 경고하고, 승인·검증된 회사에 유효한 오너 멤버십 또는
일치하는 `users.company_id`가 없으면 중단한다.

expand는 격리 대상 사용자(유효한 `APPROVED` 멤버십이 없는 `company_id` 보유자)의 `users.company_id`를
그 자리에서 `NULL`로 정리한다. `company_memberships`가 소속·권한의 새 권위 원천이 된 이후에도
`users.company_id`가 남아 있으면, 이를 인가 근거로 참조하는 기존 경로(`AuthService.validateAssignableInspector`의
"같은 회사" 판정 등)가 승인되지 않은 사용자를 회사 소속으로 오인할 수 있기 때문이다. finalize와 verify는
이 정리가 유지되는지(애플리케이션 쓰기가 완전히 멈추지 않아 그 사이 새로 생긴 stale `company_id`가 없는지)
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

`Ha25IncrementalMigrationTest`는 별도 PostgreSQL 17에서 v0.3 기준 DDL과 기존 데이터 fixture를 적재한 뒤
expand 2회 → `lock_version=0` 백필 확인 → 미백필 finalize 차단 확인 → 명시적 백필 →
finalize 2회 → verify를 실행한다. 같은 PostgreSQL 인스턴스의 독립 DB에 캐노니컬 DDL을 추가로
적용하고, 두 DB의 enum·테이블·컬럼 타입/NULL/default·제약·인덱스·트리거·시퀀스·함수를
정규화한 PostgreSQL 카탈로그 스냅샷으로 직접 대조한다. 컬럼의 물리적 순서만 증분 경로의 정상적
차이로 제외하고 실행 의미가 다르면 테스트를 실패시킨다. 이후 Hibernate `ddl-auto=validate`로 전체
Entity 매핑을 대조하고, 같은 이름의 잘못된 인덱스·nullable 컬럼·기본값 누락·잘못된 FK 대상·
replica-only 트리거를 차례로 주입해 verify가 각각 거부하는지도 확인한다.

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

## 롤백 원칙

expand 단계는 기존 컬럼을 삭제하거나 값을 변환하지 않는다. finalize 이후에는 새 애플리케이션이 신규
컬럼에 의존하므로 SQL 역변경 대신 애플리케이션 롤백과 DB 백업 복원을 함께 판단한다. 운영 데이터가
기록된 `company_memberships` 또는 신규 컬럼을 임의로 DROP하지 않는다.
