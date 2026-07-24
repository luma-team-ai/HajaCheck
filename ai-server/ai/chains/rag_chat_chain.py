"""고객지원 RAG 챗봇 체인 (FR-6, GitHub #19 / Jira HAJA-28·HAJA-32)

docs/design/ai/rag_chatbot_design.md §3·§4, docs/design/ai/rag_chroma_schema.md §4.2·§6·§7 계약을
그대로 구현한다. AI_개발_컨벤션.md §8 예시 체인 절차 + defect_explain_chain.py 패턴을 따른다.

`sources`는 LLM이 만들지 않는다 — retriever가 반환한 Chroma 청크 metadata에서 코드가 결정적으로
구성한다(설계 §3 "LLM 창작 아님"). LLM structured output(`_RagChatAnswer`)에는 `answer` 하나만 둔다.

LangGraph StateGraph 기반 구현 — 캐시·검색·LLM·출처 빌드 노드 + 조건부 엣지로 분기.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Literal, Optional, TypedDict

from langgraph.graph import END, StateGraph
from pydantic import BaseModel, Field

from ai.core.llm_client import CACHE_TTL_SECONDS, get_llm, get_redis_client
from ai.core.prompt_safety import wrap_untrusted
from ai.core.schemas import AIErrorCode, AIResponse, RagAnswerData, SourceCitation
from ai.core.vectorstore import COLLECTION_REGULATIONS, get_vectorstore

PROMPTS_DIR = Path(__file__).resolve().parent.parent / "prompts"

RAG_CHAT_TOP_K = 4  # 설계 §4.1 초안값
RAG_CHAT_CACHE_PREFIX = "ai:cache:rag-chat"


class RagChatState(TypedDict):
    """StateGraph 상태 — 질의부터 최종 응답까지의 중간값들을 담는다.

    캐시 유무, 검색 결과 유무에 따라 경로가 분기하므로, 모든 경로가 final_response를 남겨둔다.
    """
    question: str
    cache_key: str
    cached_result: Optional[dict]
    docs: list
    llm_answer: Optional["_RagChatAnswer"]
    sources: Optional[list[SourceCitation]]
    final_response: Optional[AIResponse]


class _RagChatAnswer(BaseModel):
    """LLM structured output 스키마 — answer만. 공개 응답 스키마 RagAnswerData(§2)와는 별개다.
    sources 필드를 아예 두지 않음으로써 LLM이 이상한 값을 내도 sources가 절대 바뀌지 않는다.

    grounded: retrieve가 관련성 임계값 없이 top-k를 그대로 반환하므로(§4.1), docs가 비어있지
    않다는 사실만으로는 "질의와 실제로 관련된 근거"를 보장하지 못한다 — LLM이 그 발췌로 실제
    답변했는지 스스로 판정한 값. False면 sources를 붙이지 않고 no_result로 라우팅한다."""

    answer: str
    grounded: bool = Field(
        description=(
            "검색된 법규 발췌만으로 질문에 실제로 답변했으면 true. "
            "발췌에 관련 근거가 없어 '관련 근거를 찾지 못했습니다'류로 답했으면 false."
        )
    )


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


# ============================================================================
# StateGraph 노드들 — 각 노드는 state를 받아서 수정된 state를 반환
# ============================================================================


def _node_cache_check(state: RagChatState) -> RagChatState:
    """캐시 조회 노드.

    Redis에서 캐시를 조회하고, 히트 시 cached_result와 final_response를 세팅한다.
    노드 함수 내에서 get_redis_client()를 런타임 호출하므로 @patch 호환성 유지.
    """
    redis_client = get_redis_client()
    cache_key = _cache_key(state["question"])
    cached = redis_client.get(cache_key)

    cached_result = json.loads(cached) if cached else None
    # PR머신 P3: truthy 판정(if cached_result)이 아니라 is not None으로 통일 — _route_after_cache와
    # 기준이 어긋나면(캐시 포맷이 falsy-but-not-None 값을 저장하게 바뀌는 경우) 라우터는 END로 보내는데
    # final_response가 None이 되어 run_rag_chat_chain이 None을 반환하는 잠재적 불일치를 방지한다.
    final_response = AIResponse.ok(cached_result) if cached_result is not None else None

    return {
        **state,
        "cache_key": cache_key,
        "cached_result": cached_result,
        "final_response": final_response,
    }


def _route_after_cache(state: RagChatState) -> Literal["end", "retrieve"]:
    """캐시 후 라우터 — 캐시 히트면 END, 미스면 retrieve 노드로."""
    return "end" if state["cached_result"] is not None else "retrieve"


def _node_retrieve(state: RagChatState) -> RagChatState:
    """검색 노드.

    벡터스토어에서 similarity_search를 수행. 노드 함수 내에서 get_vectorstore()를 런타임 호출.
    """
    docs = get_vectorstore(COLLECTION_REGULATIONS).similarity_search(
        state["question"], k=RAG_CHAT_TOP_K
    )
    return {**state, "docs": docs}


def _route_after_retrieve(state: RagChatState) -> Literal["answer", "no_result"]:
    """검색 후 라우터 — 문서 있으면 answer, 없으면 no_result 노드로."""
    return "answer" if state["docs"] else "no_result"


def _node_answer(state: RagChatState) -> RagChatState:
    """LLM 호출 노드.

    context 구성, prompt 빌드, LLM structured output 호출.
    노드 함수 내에서 get_llm()을 런타임 호출하므로 @patch 호환성 유지.
    """
    context = _build_context(state["docs"])
    prompt = _build_prompt(state["question"], context)
    llm_answer = get_llm().with_structured_output(_RagChatAnswer).invoke(prompt)
    return {**state, "llm_answer": llm_answer}


def _route_after_answer(state: RagChatState) -> Literal["build_sources", "no_result"]:
    """LLM 답변 후 라우터 — 검색 발췌로 실제 답변했으면(grounded=true) build_sources,
    아니면(grounded=false) no_result. docs 비존재만 걸러내던 _route_after_retrieve와 달리
    "검색은 됐지만 무관한 발췌"까지 여기서 걸러낸다."""
    return "build_sources" if state["llm_answer"].grounded else "no_result"


def _node_build_sources(state: RagChatState) -> RagChatState:
    """출처 빌드 노드.

    검색 문서의 metadata에서 결정론적으로 sources를 구성.
    """
    sources = _build_sources(state["docs"])
    return {**state, "sources": sources}


def _node_cache_write(state: RagChatState) -> RagChatState:
    """캐시 저장 노드.

    RagAnswerData를 조립해서 Redis에 저장하고 final_response를 세팅.
    """
    answer_data = RagAnswerData(
        answer=state["llm_answer"].answer,
        sources=state["sources"]
    )
    redis_client = get_redis_client()
    redis_client.setex(
        state["cache_key"],
        CACHE_TTL_SECONDS,
        answer_data.model_dump_json()
    )
    return {
        **state,
        "final_response": AIResponse.ok(answer_data.model_dump())
    }


def _node_no_result(state: RagChatState) -> RagChatState:
    """근거 없음 응답 노드 — 검색 0건(_route_after_retrieve) 또는 검색은 됐지만 무관함
    (_route_after_answer, grounded=false) 두 경로에서 모두 진입한다.

    RAG_NO_RESULT 에러 응답을 세팅. 캐시는 저장하지 않음 (설계 §4.3).
    """
    return {
        **state,
        "final_response": AIResponse.fail(
            AIErrorCode.RAG_NO_RESULT,
            "관련 근거를 찾지 못했습니다"
        )
    }


# ============================================================================
# StateGraph 정의 및 compile — 모듈 로드 시 1회만 수행
# ============================================================================

_graph = StateGraph(RagChatState)

# 노드 등록
_graph.add_node("cache_check", _node_cache_check)
_graph.add_node("retrieve", _node_retrieve)
_graph.add_node("answer", _node_answer)
_graph.add_node("build_sources", _node_build_sources)
_graph.add_node("cache_write", _node_cache_write)
_graph.add_node("no_result", _node_no_result)

# 진입점 및 엣지
_graph.set_entry_point("cache_check")

# cache_check 후 조건부 분기
_graph.add_conditional_edges(
    "cache_check",
    _route_after_cache,
    {"end": END, "retrieve": "retrieve"}
)

# retrieve 후 조건부 분기
_graph.add_conditional_edges(
    "retrieve",
    _route_after_retrieve,
    {"answer": "answer", "no_result": "no_result"}
)

# answer 후 조건부 분기 — grounded=false면 no_result로(무관한 발췌에 sources 붙이는 것 방지)
_graph.add_conditional_edges(
    "answer",
    _route_after_answer,
    {"build_sources": "build_sources", "no_result": "no_result"}
)

# 무조건 엣지 (재시도 없음, 선형 흐름)
_graph.add_edge("build_sources", "cache_write")
_graph.add_edge("cache_write", END)
_graph.add_edge("no_result", END)

# compile
compiled_graph = _graph.compile()


def run_rag_chat_chain(question: str) -> AIResponse:
    """RAG 챗봇 체인 공개 진입점.

    LangGraph StateGraph로 구현된 파이프라인을 invoke하고 최종 응답을 반환.
    시그니처와 반환값은 기존과 100% 동일.
    """
    initial_state: RagChatState = {
        "question": question,
        "cache_key": "",
        "cached_result": None,
        "docs": [],
        "llm_answer": None,
        "sources": None,
        "final_response": None,
    }

    result_state = compiled_graph.invoke(
        initial_state,
        config={"recursion_limit": 10}
    )

    return result_state["final_response"]
