-- Flyway V49 — chat_message_citations.document_id FK를 ON DELETE SET NULL로 전환(#1597).
--
-- 배경: V1 baseline이 document_id FK에 ON DELETE 절을 빠뜨렸다(바로 위 message_id는
-- on delete cascade인데 document_id만 누락 — RagDocumentService.delete()에서 Chroma 청크는
-- 지워졌는데 PG 로우 삭제가 FK 위반(23503)으로 영구 실패해 "목록엔 있는데 검색은 안 되는" 반쪽
-- 상태가 고착됐다). rag_documents를 참조하는 FK는 이 하나뿐이다(전 마이그레이션 확인 완료).
--
-- 결정(사용자 확정 — (A) 채택, (B) CASCADE·(C) 애플리케이션 삭제 방지는 불채택): 인용 이력은 남기고
-- 문서 참조만 끊는다. 과거 대화의 출처 칩은 문서 제목이 null이면 "삭제된 문서"로 표시하되
-- locator·snippet은 그대로 노출한다(citation 행 자체에 이미 보관돼 있어 문서가 사라져도 표시 가능).
--
-- unique(message_id, document_id, chunk_ref)는 SET NULL 이후에도 안전하다 — PostgreSQL은 유니크
-- 제약에서 NULL을 서로 다른 값으로 취급하므로, 같은 메시지가 삭제된 문서를 여러 건 인용해도(모두
-- document_id=NULL) 유니크 위반이 나지 않는다.
--
-- 멱등성(#544 P1 회귀 방지): 캐노니컬 DDL(HajaCheck_script.sql)이 이미 이 스키마를 포함한 기존 DB
-- (baseline-on-migrate)에서도 실패하지 않도록 가드로 no-op 안전하게 작성한다.

-- 1) NOT NULL 완화 — 이미 nullable이면 no-op.
alter table chat_message_citations
    alter column document_id drop not null;

-- 2) 기존 FK 제거(V1이 인라인 `references`로 만들어 이름이 PostgreSQL 기본 생성 규칙
--    {table}_{column}_fkey다). 캐노니컬 DDL이 이미 아래 3)의 이름으로 재정의된 기존 DB에서는
--    이 이름의 제약이 없으므로 IF EXISTS로 no-op.
alter table chat_message_citations
    drop constraint if exists chat_message_citations_document_id_fkey;

-- 3) ON DELETE SET NULL로 명시적 이름의 FK를 재생성(저장소 FK 네이밍 컨벤션 fk_{table}_{역할}).
do $$
begin
    if not exists (
        select 1 from pg_constraint
         where conname = 'fk_chat_message_citations_document'
           and conrelid = 'public.chat_message_citations'::regclass
    ) then
        alter table public.chat_message_citations
            add constraint fk_chat_message_citations_document
                foreign key (document_id) references public.rag_documents (id)
                on delete set null;
    end if;
end
$$;

comment on column chat_message_citations.document_id is
    '인용된 RAG 문서 식별자(rag_documents) — 문서 삭제 시 NULL(ON DELETE SET NULL, #1597). 인용 이력(locator/snippet)은 문서가 사라져도 유지된다';
