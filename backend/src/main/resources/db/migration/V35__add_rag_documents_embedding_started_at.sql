-- Flyway V35 — rag_documents.embedding_started_at 신설(#1393, PR머신 리뷰 P1).
--
-- 임베딩 완료 확정이 인메모리 @Async 폴러(RagEmbeddingCompletionPoller)에만 의존해,
-- 폴링 창(약 25초) 안에 JVM이 재시작되면(배포·크래시·롤링 재시작) 폴러가 통째로 유실되고
-- 문서가 EMBEDDING 상태로 영구 고착된다. 고착을 판정하려면 "언제부터 EMBEDDING이었나"가
-- 필요한데 기존 컬럼은 완료 시각(embedded_at)뿐이라 시작 시각 컬럼을 추가한다.
--
-- RagEmbeddingStaleReconciler(@Scheduled)가 이 컬럼으로 stale EMBEDDING을 찾아 FAILED로
-- 정리하고, RagDocument.restartEmbedding()도 이 값으로 "stale EMBEDDING만 재시작 허용"을
-- 판정한다. 기존 행은 NULL로 남되(=시작 시각 미상) 엔티티가 NULL도 stale로 취급해 복구 대상에
-- 포함시키므로 백필하지 않는다.
alter table rag_documents
    add column if not exists embedding_started_at timestamp with time zone;

comment on column rag_documents.embedding_started_at is '임베딩 시작 시각(EMBEDDING 고착 판정 기준 — NULL은 시작 시각 미상)';
