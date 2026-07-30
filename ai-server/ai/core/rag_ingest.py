"""RAG 문서 임베딩 적재 — #22/HAJA-35.

Spring↔FastAPI 컨테이너 간 파일 공유 볼륨이 없어, Spring이 PDF에서 추출한 텍스트 + 메타데이터를
JSON payload로 넘기면 이 모듈이 target_collection에 따라 적절한 청킹 함수를 선택해 분할하고
Chroma에 add_texts()로 적재한다. 체인(LLM 호출)이 아니라 결정론적 데이터 처리라 ai/chains/가 아닌
ai/core/에 둔다. 메타데이터 필드명·타입·결측값 처리는 docs/design/ai/rag_chroma_schema.md §3~6을
그대로 따른다 — 결측 필드는 키 자체를 만들지 않는다(값을 None/빈 문자열로 채우지 않는다).

⚠️ 범위: rag_chroma_schema.md §5의 defect_kb 전용 필드(defect_category/severity_ref, 관리자 태깅)는
이번 PR의 업로드 화면에 태깅 UI가 없어 이 함수 시그니처에도 포함하지 않는다(#22 handoff에 명시된
ingest_document 시그니처 그대로) — 후속 과제로 남는다.
"""
import hashlib
from datetime import datetime, timezone

from ai.core.chunking import extract_article_metadata, split_general_text, split_regulation_text
from ai.core.vectorstore import COLLECTION_DEFECT_KB, COLLECTION_REGULATIONS, get_vectorstore

# get_embeddings() 기본 모델(ai/core/embeddings.py)과 동일 문자열을 메타데이터에 그대로 기록한다.
EMBEDDING_MODEL = "BAAI/bge-m3"


def _chunk_document_id(doc_id: str, chunk_index: int) -> str:
    # rag_chroma_schema.md §3 "Chroma document id: {doc_id}_{chunk_index} — 재임베딩 시 동일 청크는
    # 동일 id로 upsert".
    return f"{doc_id}_{chunk_index}"


def ingest_document(
    doc_id: str,
    title: str,
    doc_type: str,
    target_collection: str,
    text: str,
    effective_date: str | None = None,
    publisher: str | None = None,
    authored_at: str | None = None,
    verification_status: str | None = None,
) -> int:
    """문서를 청킹해 target_collection에 임베딩하고 청크 수를 반환한다.

    target_collection에 따라 청킹 함수를 선택한다(regulations=조문 경계 우선, defect_kb=고정 길이) —
    AI_개발_컨벤션.md §6. 각 청크의 Chroma document id는 "{doc_id}_{chunk_index}"라 재임베딩 시
    delete_document()로 먼저 정리하면 동일 id로 다시 upsert된다.
    """
    if target_collection == COLLECTION_REGULATIONS:
        chunks = split_regulation_text(text)
    elif target_collection == COLLECTION_DEFECT_KB:
        chunks = split_general_text(text)
    else:
        raise ValueError(f"unknown target_collection: {target_collection}")

    if not chunks:
        return 0

    embedded_at = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    ids: list[str] = []
    metadatas: list[dict] = []
    for index, chunk in enumerate(chunks):
        metadata: dict = {
            "doc_id": doc_id,
            "source": title,
            "doc_type": doc_type,
            "chunk_index": index,
            "chunk_hash": hashlib.sha256(chunk.encode("utf-8")).hexdigest(),
            "embedding_model": EMBEDDING_MODEL,
            "embedded_at": embedded_at,
        }
        if target_collection == COLLECTION_REGULATIONS:
            metadata.update(extract_article_metadata(chunk))
            if effective_date:
                metadata["effective_date"] = effective_date
            if publisher:
                metadata["publisher"] = publisher
        else:  # COLLECTION_DEFECT_KB
            if authored_at:
                metadata["authored_at"] = authored_at
            if verification_status:
                metadata["verification_status"] = verification_status

        ids.append(_chunk_document_id(doc_id, index))
        metadatas.append(metadata)

    vectorstore = get_vectorstore(target_collection)
    vectorstore.add_texts(texts=chunks, metadatas=metadatas, ids=ids)
    return len(chunks)


def delete_document(doc_id: str, collection: str) -> None:
    """동일 doc_id의 기존 청크를 전부 삭제한다. ingest_document()보다 먼저 부르면(구 재임베딩 순서)
    재삽입 실패 시 기존 임베딩을 통째로 잃는다(PR#685 리뷰 P2) — 재임베딩 경로에서는 대신
    delete_stale_chunks()를 ingest_document() 성공 이후에 쓴다. 이 함수는 문서 자체를 완전히
    지우는 별도 시나리오(현재 미구현)를 위해 남겨둔다."""
    vectorstore = get_vectorstore(collection)
    # langchain_chroma==0.1.4의 Chroma.delete(ids=None, **kwargs)는 where를 **kwargs로 받아놓고
    # self._collection.delete(ids=ids)만 호출해 실제로는 버린다 — where 없이 ids=None으로 호출되면
    # chromadb가 "At least one of ids, where, or where_document must be provided" ValueError를
    # 던져 모든 (재)임베딩이 실패한다(code-review P1). 래퍼를 우회해 내부 chromadb 컬렉션을 직접
    # 호출한다.
    vectorstore._collection.delete(where={"doc_id": doc_id})


def delete_stale_chunks(doc_id: str, collection: str, keep_chunk_count: int) -> None:
    """재임베딩 후 남은 옛 청크(chunk_index >= keep_chunk_count)만 삭제한다 — Chroma.add_texts()가
    upsert이므로(collection.upsert, 동일 id 덮어씀) 0..keep_chunk_count-1 구간은 이미 새 내용으로
    갱신돼 있다. 옛 문서가 새 문서보다 청크가 더 많았을 때 남는 초과분만 정리 대상이다.
    ingest_document() **성공 이후에만** 호출한다(PR#685 리뷰 P2 — 삭제를 먼저 하면 재삽입 실패 시
    방금까지 정상 서빙되던 임베딩이 전부 사라진다). 새 문서가 청크 0개(빈 텍스트)일 수도 있어
    keep_chunk_count=0이면 사실상 전체 삭제와 동일하게 동작한다."""
    vectorstore = get_vectorstore(collection)
    vectorstore._collection.delete(
        where={"$and": [{"doc_id": doc_id}, {"chunk_index": {"$gte": keep_chunk_count}}]}
    )
