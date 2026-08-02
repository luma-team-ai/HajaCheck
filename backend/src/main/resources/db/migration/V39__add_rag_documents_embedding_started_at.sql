-- Flyway V39 — rag_documents EMBEDDING 고착 복구용 컬럼 3종 신설(#1393, PR머신 리뷰 P1 + 2차 P2).
--
-- ① embedding_started_at — 임베딩 완료 확정이 인메모리 @Async 폴러(RagEmbeddingCompletionPoller)에만
-- 의존해, 폴링 창(약 25초) 안에 JVM이 재시작되면(배포·크래시·롤링 재시작) 폴러가 통째로 유실되고
-- 문서가 EMBEDDING 상태로 영구 고착된다. 고착을 판정하려면 "언제부터 EMBEDDING이었나"가 필요한데
-- 기존 컬럼은 완료 시각(embedded_at)뿐이라 시작 시각 컬럼을 추가한다.
--
-- RagEmbeddingStaleReconciler(@Scheduled)가 이 컬럼으로 stale EMBEDDING을 찾아 정리하고,
-- RagDocument.restartEmbedding()도 이 값으로 "stale EMBEDDING만 재시작 허용"을 판정한다. 기존 행은
-- NULL로 남되(=시작 시각 미상) 엔티티가 NULL도 stale로 취급해 복구 대상에 포함시키므로 백필하지 않는다.
--
-- ② expected_chunk_count / ③ embed_batch_id — embedding_started_at은 "얼마나 오래 EMBEDDING이었나"만
-- 알려줄 뿐 "성공했다면 Chroma에 뭐가 들어있어야 정답인가"는 알려주지 못한다. 폴러가 25초 상한에
-- 도달하면 EMBEDDING을 유지한 채 종료하고 최종 판정을 리컨사일러에 넘기는데, 리컨사일러가 실제
-- Chroma 상태를 재확인해 채점하려면 "이번 요청이 기대하는 청크 수·배치 식별자"가 필요하다. 이 값은
-- 원래 폴러의 인메모리 인자로만 전달돼 25초가 지나면 사라졌고, 그래서 리컨사일러는 실제로 성공한
-- 대용량 임베딩까지 재확인 없이 무조건 FAILED로 마킹했다(PR머신 리뷰 2차 P2 — "폴러 상한 초과 후
-- 완료 재확인 경로 부재"). 임베딩 요청 직후(AI 서버 응답을 받은 시점) RagDocumentWriter.
-- recordEmbedRequest()가 이 두 컬럼에 기대값을 기록해두면, 리컨사일러가 completeEmbedding() 이전에
-- AiProxyService.checkEmbeddingStatus()로 실제 Chroma 상태를 재조회해 RagEmbeddingCompletionCheck
-- (폴러와 동일 판정 로직)로 대조할 수 있다.
alter table rag_documents
    add column if not exists embedding_started_at timestamp with time zone,
    add column if not exists expected_chunk_count integer,
    add column if not exists embed_batch_id varchar(64);

comment on column rag_documents.embedding_started_at is '임베딩 시작 시각(EMBEDDING 고착 판정 기준 — NULL은 시작 시각 미상)';
comment on column rag_documents.expected_chunk_count is '이번 임베딩 요청이 기대하는 청크 수(리컨사일러 재확인용)';
comment on column rag_documents.embed_batch_id is '이번 임베딩 요청의 배치 식별자(리컨사일러 재확인용)';
