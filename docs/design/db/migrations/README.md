# HAJA-25 기존 PostgreSQL 증분 반영 절차

> **문서 버전:** v0.1 · **최종 수정:** 2026-07-16 · 이전 버전 `archive/`

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

### v0.3에 이미 존재하는 유니크 제약

`user_consents(user_id, policy_type, policy_version)`와 `inspections(facility_id, round_no)` 유니크 제약은
[`HajaCheck_script_v0.3.sql`](../archive/HajaCheck_script_v0.3.sql)부터 존재한 기준선 제약이다. HAJA-25
증분 스크립트나 이번 JPA 매핑이 새로 추가하는 제약이 아니므로 별도 중복 정리 단계는 두지 않는다. v0.3을
표방하는 운영 DB에 둘 중 하나가 없다면 HAJA-25 마이그레이션을 계속하지 말고 기준선 스키마 드리프트를 먼저
조사한다.

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

### 점검 담당자

`created_by`는 생성자일 뿐 담당 검사자라는 보장이 없으므로 자동 복사하지 않는다.

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
