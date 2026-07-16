# HAJA-25 기존 PostgreSQL 증분 반영 절차

> **문서 버전:** v0.1 · **최종 수정:** 2026-07-16 · 이전 버전 `archive/`

이 디렉터리는 이미 운영 중인 v0.3 계열 PostgreSQL을 현재
[`HajaCheck_script.sql`](../HajaCheck_script.sql) 스키마로 올리는 수동 증분 SQL을 보관한다.
Flyway 도입 전까지 신규 DB는 통합 DDL을 사용하고, 기존 DB는 아래 순서만 사용한다.

## 적용 순서

1. DB 백업과 애플리케이션 쓰기 중지 또는 점검 시간을 확보한다.
2. `20260716_01_ha25_expand.sql`을 실행한다.
3. 아래 조회로 백필 대상을 확인하고 운영 근거에 따라 값을 채운다.
4. `20260716_02_ha25_finalize.sql`을 실행한다. 미백필 또는 중복 데이터가 있으면 전체 트랜잭션이 실패한다.
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

각 변경 스크립트는 자체 트랜잭션과 advisory lock을 사용한다. 실패 원인을 해소한 뒤 같은 파일을
다시 실행할 수 있도록 스키마 확장 구문은 재실행 가능하게 작성되어 있다.

## 필수 백필

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

## 롤백 원칙

expand 단계는 기존 컬럼을 삭제하거나 값을 변환하지 않는다. finalize 이후에는 새 애플리케이션이 신규
컬럼에 의존하므로 SQL 역변경 대신 애플리케이션 롤백과 DB 백업 복원을 함께 판단한다. 운영 데이터가
기록된 `company_memberships` 또는 신규 컬럼을 임의로 DROP하지 않는다.
