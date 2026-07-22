"""고객지원 RAG 챗봇 체인 (FR-6, GitHub #19 / Jira HAJA-28·HAJA-32)

docs/design/ai/rag_chatbot_design.md §3·§4, docs/design/ai/rag_chroma_schema.md §4.2·§6·§7 계약을
그대로 구현한다. AI_개발_컨벤션.md §8 예시 체인 절차 + defect_explain_chain.py 패턴을 따른다.

`sources`는 LLM이 만들지 않는다 — retriever가 반환한 Chroma 청크 metadata에서 코드가 결정적으로
구성한다(설계 §3 "LLM 창작 아님"). LLM structured output(`_RagChatAnswer`)에는 `answer` 하나만 둔다.
"""
import hashlib
import json
from pathlib import Path

from pydantic import BaseModel

from ai.core.llm_client import CACHE_TTL_SECONDS, get_llm, get_redis_client
from ai.core.prompt_safety import wrap_untrusted
from ai.core.schemas import AIErrorCode, AIResponse, RagAnswerData, SourceCitation
from ai.core.vectorstore import COLLECTION_REGULATIONS, get_vectorstore

PROMPTS_DIR = Path(__file__).resolve().parent.parent / "prompts"

RAG_CHAT_TOP_K = 4  # 설계 §4.1 초안값
RAG_CHAT_CACHE_PREFIX = "ai:cache:rag-chat"


class _RagChatAnswer(BaseModel):
    """LLM structured output 스키마 — answer만. 공개 응답 스키마 RagAnswerData(§2)와는 별개다.
    sources 필드를 아예 두지 않음으로써 LLM이 이상한 값을 내도 sources가 절대 바뀌지 않는다."""

    answer: str


def _render_locator(metadata: dict) -> str:
    """rag_chroma_schema.md §7 렌더링 정책 그대로.

    article 있으면 "제12조"(clause 있으면 "제12조 ①"), 없고 page 있으면 "{page}페이지".
    설계 문서에 명시되지 않은 잔여 엣지케이스(article·page 둘 다 없음)는
    SourceCitation.locator가 min_length=1이라 빈 값을 낼 수 없으므로 "문서 전체"를
    방어적 기본값으로 둔다 — 리뷰에서 재확인 대상.
    """
    article = metadata.get("article")
    if article:
        clause = metadata.get("clause")
        return f"{article} {clause}" if clause else str(article)

    page = metadata.get("page")
    if page:
        return f"{page}페이지"

    return "문서 전체"


def _build_sources(docs) -> list[SourceCitation]:
    """rag_chroma_schema.md §4.2 매핑 표 그대로. docs는 LangChain Document 리스트
    (similarity_search 반환값) — page_content/metadata를 갖는다."""
    sources = []
    for doc in docs:
        metadata = doc.metadata
        doc_id = str(metadata["doc_id"])
        chunk_index = metadata["chunk_index"]
        sources.append(
            SourceCitation(
                doc_id=doc_id,
                title=metadata["source"],
                collection=COLLECTION_REGULATIONS,
                locator=_render_locator(metadata),
                snippet=doc.page_content,
                chunk_ref=f"{doc_id}_{chunk_index}",
            )
        )
    return sources


def _build_context(docs) -> str:
    return "\n\n".join(doc.page_content for doc in docs)


def _build_prompt(question: str, context: str) -> str:
    system = (PROMPTS_DIR / "_system_base.md").read_text(encoding="utf-8")
    template = (PROMPTS_DIR / "rag_chat.md").read_text(encoding="utf-8")
    filled = template.format(
        context=context,
        question_text=wrap_untrusted(question),
    )
    return f"{system}\n\n{filled}"


def _cache_key(question: str) -> str:
    return f"{RAG_CHAT_CACHE_PREFIX}:{hashlib.sha256(question.encode('utf-8')).hexdigest()[:16]}"


def run_rag_chat_chain(question: str) -> AIResponse:
    redis_client = get_redis_client()
    cache_key = _cache_key(question)

    cached = redis_client.get(cache_key)
    if cached is not None:
        return AIResponse.ok(json.loads(cached))

    docs = get_vectorstore(COLLECTION_REGULATIONS).similarity_search(question, k=RAG_CHAT_TOP_K)
    if not docs:
        # 검색만으론 비용이 없어 재시도 비용이 없으므로 캐시 저장 안 함(설계 §4.3).
        return AIResponse.fail(AIErrorCode.RAG_NO_RESULT, "관련 근거를 찾지 못했습니다")

    context = _build_context(docs)
    prompt = _build_prompt(question, context)
    llm_answer = get_llm().with_structured_output(_RagChatAnswer).invoke(prompt)

    answer_data = RagAnswerData(answer=llm_answer.answer, sources=_build_sources(docs))
    redis_client.setex(cache_key, CACHE_TTL_SECONDS, answer_data.model_dump_json())
    return AIResponse.ok(answer_data.model_dump())
