"""고객지원 RAG 챗봇 체인 (FR-6, GitHub #19 / Jira HAJA-28·HAJA-32)

docs/design/ai/rag_chatbot_design.md §3·§4, docs/design/ai/rag_chroma_schema.md §4.2·§6·§7 계약을
그대로 구현한다. AI_개발_컨벤션.md §8 예시 체인 절차 + defect_explain_chain.py 패턴을 따른다.

`sources`는 LLM이 만들지 않는다 — retriever가 반환한 Chroma 청크 metadata에서 코드가 결정적으로
구성한다(설계 §3 "LLM 창작 아님"). LLM structured output(`_RagChatAnswer`)에는 `answer` 하나만 둔다.

LangGraph StateGraph 기반 구현 — 캐시·검색·LLM·출처 빌드 노드 + 조건부 엣지로 분기.

고객 질의(`question`)가 프롬프트에 그대로 들어가지만, 트레이싱을 켜면 원문이 그대로 LangSmith로
전송된다 — #1534, 재봉님 승인으로 마스킹 제거.
"""
from __future__ import annotations

import hashlib
import json
import logging
from pathlib import Path
from typing import Literal, Optional, TypedDict

from langchain_core.documents import Document
from langgraph.graph import END, StateGraph
from pydantic import BaseModel, Field

from ai.core.hybrid_search import hybrid_search
from ai.core.llm_client import CACHE_TTL_SECONDS, get_llm, get_redis_client
from ai.core.prompt_safety import UNTRUSTED_DATA_BEGIN, UNTRUSTED_DATA_END, wrap_untrusted
from ai.core.schemas import AIErrorCode, AIResponse, RagAnswerData, SourceCitation
from ai.core.semantic_cache import (
    CACHE_VERSION_FIELD,
    CREATED_AT_FIELD,
    enforce_retention,
    fresh_entry_filter,
    now_epoch_seconds,
    semantic_cache_threshold,
)
from ai.core.vectorstore import COLLECTION_REGULATIONS, COLLECTION_SEMANTIC_CACHE, get_vectorstore

logger = logging.getLogger(__name__)

PROMPTS_DIR = Path(__file__).resolve().parent.parent / "prompts"

RAG_CHAT_TOP_K = 4  # 설계 §4.1 초안값

# 캐시 버전(#1699) — `prompts/rag_chat.md`에 출력 형식 규칙(결론 우선·불릿·600자 목표)을 추가하며
# 기존 캐시가 옛 형식(형식 규칙 없는 장문) 답변을 계속 반환하지 않도록 **exact·semantic 두 캐시를
# 함께** 버전한다. exact(Redis)는 키 prefix에, semantic(Chroma)은 저장 metadata 필드
# (`RAG_CHAT_SEMANTIC_CACHE_VERSION`, 아래)에 반영한다. 옛 버전 키/항목은 삭제하지 않고 TTL로
# 자연 소멸시킨다(운영 Redis FLUSH·Chroma 컬렉션 삭제 같은 destructive 조치 금지) — exact는 옛
# prefix라 새 prefix로 조회 자체가 안 되고, semantic은 옛 항목에 새 버전 태그가 없어 필터에
# 매칭되지 않는다(company_id/created_at 없는 레거시 항목이 필터에서 자연히 빠지는 것과 동일 원리,
# `semantic_cache.py` 모듈 docstring 참고). 프롬프트를 다시 바꿀 때는 이 값을 함께 bump한다.
RAG_CHAT_CACHE_PREFIX = "ai:cache:rag-chat:v2"
RAG_CHAT_SEMANTIC_CACHE_VERSION = "v2"

# 시맨틱 캐시 유사도 임계값은 `ai.core.semantic_cache.semantic_cache_threshold()`가 **런타임에**
# 읽는다(#1594 P3) — 예전에는 이 모듈 최상단에서 `float(os.getenv(...))`로 읽어 ①레포 컨벤션
# (`vectorstore.py` `_client()` 주석: os.getenv는 반드시 함수 내부에서) 위반이었고 ②잘못된 값
# 하나가 임포트 시점 ValueError로 앱 전체를 죽였다. TTL·용량 상한과 함께 그 모듈이 단일 정책
# 지점이다.
# TODO(#1462 후속 이슈): 실측 기반 threshold 튜닝 필요. 현재는 법규 QA 도메인 오탐 리스크 때문에 보수적으로 높게 설정한 초기값.

# 캐시 기록 허용 답변 길이 경계(#1594 P2) — 아래 `_is_cacheable` 참고.
# 하한은 "정상 답변을 떨어뜨리지 않으면서 퇴화 출력만 걸러내는" 선으로 잡는다: 한국어 법규 QA
# 답변은 짧아도 "관리주체입니다."(8자) 수준이라 하한을 크게 잡으면 멀쩡한 답변이 캐시에서 빠진다.
# 5자 미만은 어떤 근거 기반 답변으로도 성립하지 않는다.
MIN_CACHEABLE_ANSWER_LENGTH = 5
# 상한은 프롬프트/컨텍스트 덤프 유출 같은 비정상 장문 출력 차단용 — 정상 답변은 RAG_CHAT_TOP_K(4)
# 청크 기반 요약이라 이 근처에도 가지 않는다.
# #1699 확인: `prompts/rag_chat.md`에 추가한 출력 형식 규칙의 목표 길이(공백 포함 약 600자)와
# 이 상한이 충돌하지 않는다 — 8000자는 600자 목표의 10배 이상이라 "목표"를 다소 넘는 정상
# 답변도 여유 있게 캐시된다. 상한을 낮출 필요 없음(정상 답변이 캐시에서 빠질 위험 없음).
MAX_CACHEABLE_ANSWER_LENGTH = 8000


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
    # 대화 이력(질문/답변 텍스트만, #1493/HAJA-657) — 비어있지 않으면 skip_cache=True로 캐시를
    # 조회/저장 모두 우회한다(design §4 "이력이 있으면 캐시를 우회").
    history: list[dict]
    skip_cache: bool
    # `_is_cacheable` 판정 결과(#1594) — cache_write가 채우고 semantic_cache_write가 재사용한다
    # (요청당 1회 판정 + 거부 로그 1줄). 키가 없으면 _resolve_cacheable가 그 자리에서 판정한다.
    cacheable: Optional[bool]
    # 요청자의 회사 식별자(#1584) — Spring이 @AuthenticationPrincipal에서만 취득해 넘긴다(요청 바디
    # 신뢰 금지). 시맨틱 캐시는 이 값으로 회사 스코프를 강제한다. 회사 미소속 개인회원은 None이며,
    # 이때는 시맨틱 캐시를 조회도 저장도 하지 않는다(fail-closed — 필터 없는 전역 조회 폴백 금지).
    company_id: Optional[int]


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


def _build_history_block(history: Optional[list[dict]]) -> str:
    """이전 대화 블록 렌더링(design §5.3) — "이전 대화:\\nQ: ...\\nA: ...\\n\\n" 반복.

    history가 비어있으면 빈 문자열(1턴째 프롬프트는 기존과 100% 동일해야 회귀가 없다).

    Q(이전 질문)는 현재 질문(question_text)과 동일하게 사용자가 입력한 텍스트이므로
    wrap_untrusted로 감싼다(PR #1510 P2 픽스) — 프롬프트 인젝션 방어 대상은 "누가 언제 입력했는지"가
    아니라 "사용자 입력인지"이므로 현재 질문만 감싸고 이전 질문은 놔두는 건 방어 구멍이다.
    A(이전 답변)는 이 체인 자신이 생성한 LLM 출력(사용자가 직접 쓴 텍스트가 아님)이라 감싸지 않는다
    — wrap_untrusted 대상은 "사용자·외부 입력"(prompt_safety.py 모듈 docstring)이지 자체 출력이 아니다.
    """
    if not history:
        return ""
    lines = ["이전 대화:"]
    for turn in history:
        lines.append(f"Q: {wrap_untrusted(turn['question'])}")
        lines.append(f"A: {turn['answer']}")
    return "\n".join(lines) + "\n\n"


def _build_prompt(question: str, context: str, history: Optional[list[dict]] = None) -> str:
    system = (PROMPTS_DIR / "_system_base.md").read_text(encoding="utf-8")
    template = (PROMPTS_DIR / "rag_chat.md").read_text(encoding="utf-8")
    filled = template.format(
        context=context,
        question_text=wrap_untrusted(question),
    )
    history_block = _build_history_block(history)
    # 시스템 프롬프트 뒤 · 현재 질문(포함된 filled) 앞에 삽입(design §5.3).
    return f"{system}\n\n{history_block}{filled}"


def _cache_key(question: str) -> str:
    return f"{RAG_CHAT_CACHE_PREFIX}:{hashlib.sha256(question.encode('utf-8')).hexdigest()[:16]}"


def _is_cacheable(state: RagChatState) -> bool:
    """이 답변을 캐시(exact·semantic 양쪽)에 기록해도 되는지 판정한다(#1594 P2).

    기록 여부를 **LLM이 스스로 신고한 `grounded` 필드 단독**으로 결정하던 것에 코드가 결정론적으로
    확인 가능한 조건을 얹는다. 각 조건의 **실제 기여도**는 아래와 같다 — 과대평가를 남겨두면 이후
    누군가 "인젝션 캐시 오염은 이미 막혀 있다"고 오판하므로 정확히 적는다(코드리뷰 P2 지적):

    1. **`sources`가 비어 있지 않다** — 그래프상 `build_sources`를 거친 뒤에만 이 판정에 도달하므로
       **현재 경로에서는 항상 참**이다. 즉 인젝션 차단 기여는 사실상 0이고, 목적은 그래프 구조가
       바뀌어도 "근거 없는 답변이 캐시에 들어가는" 경로가 되살아나지 않게 하는 **구조적 이중 방어**다
       (company_id 노드 가드와 같은 원칙).
    2. **답변 길이가 상식 범위** — 빈/공백·한두 글자 같은 **퇴화 출력**과 컨텍스트 덤프성 장문을
       걷어낸다. 오염된 답변은 대개 정상 길이로 나오므로 인젝션 차단 기여는 거의 없다.
    3. **답변에 UNTRUSTED_DATA 마커가 없다** — `wrap_untrusted()`가 이미 `sanitize_untrusted()`로
       사용자 입력의 `-{3,}`를 전각으로 깨뜨리므로(`prompt_safety.py`), **사용자 입력에서 유래한**
       마커는 애초에 답변에 실릴 수 없다. 따라서 이 조건이 잡는 것은 인젝션이 아니라 모델이 스스로
       프롬프트 스캐폴딩을 복창한 **경계 붕괴 신호**다. 그런 턴의 출력은 재사용하지 않는다.

    ## ⚠️ 잔여 위험 — 이 함수는 이슈 2번을 "부분 완화"할 뿐 해소하지 못한다

    실제 위협인 "오염된 청크가 검색되었거나, 인젝션으로 `grounded=true` + **정상 길이의 왜곡 답변**이
    생성된 경우"는 위 세 조건을 **전부 통과해 그대로 캐시된다.** 이 함수에는 답변과 근거의 정합성을
    검증하는 수단이 없기 때문이다. #1584로 blast radius가 회사 1개로 줄어든 상태가 여전히 상한이다.

    실질적 강화 방향(후속): 저장 시 `sources`의 `chunk_ref`/`chunk_hash`를 metadata에 함께 남기고,
    **캐시 히트를 서빙하기 직전에** 그 청크가 아직 존재하며 내용이 동일한지 대조한다. 그러면
    ①오염·삭제된 근거에 기반한 캐시 답변이 걸러지고 ②설계 판단 2의 write-after-purge 레이스도
    같은 메커니즘으로 덮인다(`semantic_cache.py` 모듈 docstring 참고). 저장 포맷 변경이 필요해
    이 이슈의 범위를 넘는다.

    캐시 기록만 막고 응답 자체는 그대로 반환한다 — 사용자에게 보이는 동작은 바뀌지 않는다.
    로그에는 답변 원문을 남기지 않는다(개인정보·인젝션 페이로드 잔존 방지 — 길이만 기록).
    """
    sources = state.get("sources")
    if not sources:
        logger.warning("캐시 기록 거부 — sources가 비어 있다(근거 없는 답변)")
        return False

    answer = (state["llm_answer"].answer or "").strip()
    if not (MIN_CACHEABLE_ANSWER_LENGTH <= len(answer) <= MAX_CACHEABLE_ANSWER_LENGTH):
        logger.warning(
            "캐시 기록 거부 — 답변 길이 %d자가 허용 범위(%d~%d)를 벗어났다",
            len(answer), MIN_CACHEABLE_ANSWER_LENGTH, MAX_CACHEABLE_ANSWER_LENGTH,
        )
        return False

    if UNTRUSTED_DATA_BEGIN in answer or UNTRUSTED_DATA_END in answer:
        logger.warning("캐시 기록 거부 — 답변에 UNTRUSTED_DATA 마커가 섞였다(프롬프트 경계 붕괴 신호)")
        return False

    return True


def _resolve_cacheable(state: RagChatState) -> bool:
    """`_is_cacheable` 판정을 요청당 1회로 묶는다(#1594 P3).

    exact·semantic 두 저장 노드가 각자 `_is_cacheable`를 부르면 한 요청에 판정이 2회 돌고, 거부 시
    경고 로그도 2줄씩 쌓인다. 거부 조건은 외부 입력(질문)으로 유발 가능하므로 로그 증폭 벡터가 된다
    — 앞 노드(`cache_write`)가 판정 결과를 state에 실어 뒤 노드가 재사용한다.

    키가 없으면(그래프를 우회한 직접 호출 등) 그 자리에서 판정한다 — 캐시된 값이 없다고 해서
    "기록 허용"으로 폴백하면 fail-open이 되므로 반드시 실제 판정으로 떨어뜨린다.
    """
    cached = state.get("cacheable")
    if cached is not None:
        return cached
    return _is_cacheable(state)


# ============================================================================
# StateGraph 노드들 — 각 노드는 state를 받아서 수정된 state를 반환
# ============================================================================


def _node_cache_check(state: RagChatState) -> RagChatState:
    """캐시 조회 노드.

    Redis에서 캐시를 조회하고, 히트 시 cached_result와 final_response를 세팅한다.
    노드 함수 내에서 get_redis_client()를 런타임 호출하므로 @patch 호환성 유지.
    """
    cache_key = _cache_key(state["question"])

    # 이력이 있는 요청(2턴째부터)은 캐시를 완전히 우회한다(design §4) — Redis 조회 자체를 생략한다.
    if state.get("skip_cache"):
        return {
            **state,
            "cache_key": cache_key,
            "cached_result": None,
            "final_response": None,
        }

    redis_client = get_redis_client()
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


def _route_after_cache(state: RagChatState) -> Literal["end", "semantic_cache", "retrieve"]:
    """캐시 후 라우터 — skip_cache면 semantic 캐시까지 건너뛰고 곧장 retrieve로, 그 외엔 캐시
    히트면 END, 미스면 semantic_cache_check 노드로.

    company_id가 없으면(회사 미소속 개인회원) 시맨틱 캐시 노드 자체를 건너뛴다(#1584) —
    필터 없는 전역 조회로 폴백하면 회사 간 답변 교차 노출이 그대로 재현되므로, 캐시 이득을
    포기하고 fail-closed로 간다. exact(Redis) 캐시와 LLM 경로는 그대로 동작한다.
    """
    if state.get("skip_cache"):
        return "retrieve"
    if state["cached_result"] is not None:
        return "end"
    if state.get("company_id") is None:
        return "retrieve"
    return "semantic_cache"


def _node_semantic_cache_check(state: RagChatState) -> RagChatState:
    """시맨틱 캐시(2차) 조회 노드 — exact-match(Redis) 미스 시에만 진입.

    Chroma semantic_cache 컬렉션에서 **같은 회사(company_id)가 남긴** 과거 질문 중 가장 유사한
    것을 검색해(#1584), distance가 임계값 이하면 hit으로 판정하고 저장된 answer_json을
    cached_result/final_response에 세팅한다.
    노드 함수 내에서 get_vectorstore()를 런타임 호출(모듈 top-level 금지 — @patch 호환성 유지,
    Redis 클라이언트와 동일 컨벤션).

    company_id가 None이면 _route_after_cache가 이 노드로 보내지 않지만, 그래프 구조 변경 시에도
    전역 조회가 되살아나지 않도록 노드 자체에서도 방어적으로 miss 반환한다(Chroma는 filter 값에
    None을 허용하지 않아 `filter={"company_id": None}`은 ValueError로 터진다 — 실측 확인).

    TTL(#1594): 필터에 `created_at >= ttl_cutoff()`를 함께 걸어 만료된 항목은 애초에 후보로
    올라오지 않게 한다. 만료분의 실제 삭제는 저장 경로의 `enforce_retention()`이 맡지만, 조회에서
    먼저 막아야 "삭제가 아직 안 돌았을 때 구 답변이 나가는" 창이 생기지 않는다.

    파싱 실패 시 miss로 안전하게 폴백한다(캐시 오염으로 전체 요청이 죽지 않도록).
    """
    company_id = state.get("company_id")
    if company_id is None:
        return {**state, "cached_result": None, "final_response": None}

    vectorstore = get_vectorstore(COLLECTION_SEMANTIC_CACHE)
    # filter로 회사 스코프(#1584) + TTL 미경과(#1594)를 함께 강제한다. company_id/created_at
    # metadata가 없는 과거 캐시 항목은 어떤 필터에도 매칭되지 않으므로 자연히 조회 대상에서
    # 빠진다(실측 확인).
    results = vectorstore.similarity_search_with_score(
        state["question"],
        k=1,
        filter=fresh_entry_filter(company_id, RAG_CHAT_SEMANTIC_CACHE_VERSION),
    )

    cached_result = None
    if results:
        doc, score = results[0]
        if score <= (1 - semantic_cache_threshold()):
            try:
                cached_result = json.loads(doc.metadata["answer_json"])
            except (KeyError, json.JSONDecodeError, TypeError):
                cached_result = None

    final_response = AIResponse.ok(cached_result) if cached_result is not None else None

    return {
        **state,
        "cached_result": cached_result,
        "final_response": final_response,
    }


def _route_after_semantic_cache(state: RagChatState) -> Literal["end", "retrieve"]:
    """시맨틱 캐시 후 라우터 — 히트면 END, 미스면 retrieve 노드로."""
    return "end" if state["cached_result"] is not None else "retrieve"


def _node_retrieve(state: RagChatState) -> RagChatState:
    """검색 노드.

    벡터검색 + BM25검색을 RRF로 결합한 하이브리드 검색(#1410)을 수행. 노드 함수 내에서
    hybrid_search()를 런타임 호출(내부에서 get_vectorstore()/bm25_index를 런타임 호출하므로
    @patch 호환성 유지).
    """
    docs = hybrid_search(state["question"], k=RAG_CHAT_TOP_K)
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
    prompt = _build_prompt(state["question"], context, state.get("history"))
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

    `_is_cacheable`이 거부하면 저장만 건너뛰고 응답은 그대로 반환한다(#1594 P2). 판정 결과를
    state에 실어 뒤따르는 semantic 저장 노드가 재사용한다(요청당 1회 — `_resolve_cacheable`).
    """
    answer_data = RagAnswerData(
        answer=state["llm_answer"].answer,
        sources=state["sources"]
    )
    cacheable = _is_cacheable(state)
    # 이력이 있던 요청은 캐시 저장도 생략(design §4) — 캐시 조회를 우회한 요청과 대칭.
    if not state.get("skip_cache") and cacheable:
        redis_client = get_redis_client()
        redis_client.setex(
            state["cache_key"],
            CACHE_TTL_SECONDS,
            answer_data.model_dump_json()
        )
    return {
        **state,
        "cacheable": cacheable,
        "final_response": AIResponse.ok(answer_data.model_dump())
    }


def _node_semantic_cache_write(state: RagChatState) -> RagChatState:
    """시맨틱 캐시(2차) 저장 노드 — cache_write(exact) 직후, grounded=true 경로에서만 진입한다
    (no_result 경로는 그래프 구조상 이 노드를 거치지 않으므로 별도 분기 불필요).

    질문 원문을 page_content로, RagAnswerData 직렬화 결과를 metadata["answer_json"]으로,
    요청자의 회사 식별자를 metadata["company_id"]로, 캐시 버전 태그를 metadata["cache_version"]으로,
    생성 시각(epoch seconds)을 metadata["created_at"]으로 저장한다(#1584, #1594, #1699) — 조회 시
    앞의 셋으로 회사 스코프·캐시 버전·TTL을 필터링한다.

    저장 직후 `enforce_retention()`으로 TTL 경과분 삭제 + 용량 상한 축출을 수행한다(#1594) —
    캐시가 커지는 유일한 지점이 여기이므로 정리도 같은 지점에 붙이는 것이 가장 단순하다.
    이 호출은 내부에서 예외를 삼키므로 정리 실패가 응답을 막지 않는다.

    `_is_cacheable`이 거부한 답변은 저장하지 않는다(#1594 P2) — exact 캐시와 동일 기준.

    company_id가 없으면(개인회원) 저장하지 않는다 — 조회를 건너뛰는 것과 대칭이며, Chroma가
    metadata 값에 None을 허용하지 않기도 한다(실측: "Expected metadata value to be a str, int,
    float or bool, got None"). 스코프 필드 없이 저장하면 이후 어떤 회사도 읽지 못하는 쓰레기
    항목만 쌓인다.

    노드 함수 내에서 get_vectorstore()를 런타임 호출(테스트 patch 호환).
    """
    if state.get("skip_cache"):
        return state

    company_id = state.get("company_id")
    if company_id is None:
        return state

    if not _resolve_cacheable(state):
        return state

    answer_data = RagAnswerData(
        answer=state["llm_answer"].answer,
        sources=state["sources"]
    )
    vectorstore = get_vectorstore(COLLECTION_SEMANTIC_CACHE)
    vectorstore.add_documents([
        Document(
            page_content=state["question"],
            metadata={
                "answer_json": answer_data.model_dump_json(),
                "company_id": company_id,
                CACHE_VERSION_FIELD: RAG_CHAT_SEMANTIC_CACHE_VERSION,
                CREATED_AT_FIELD: now_epoch_seconds(),
            },
        )
    ])
    enforce_retention()
    return state


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
_graph.add_node("semantic_cache_check", _node_semantic_cache_check)
_graph.add_node("retrieve", _node_retrieve)
_graph.add_node("answer", _node_answer)
_graph.add_node("build_sources", _node_build_sources)
_graph.add_node("cache_write", _node_cache_write)
_graph.add_node("semantic_cache_write", _node_semantic_cache_write)
_graph.add_node("no_result", _node_no_result)

# 진입점 및 엣지
_graph.set_entry_point("cache_check")

# cache_check(exact) 후 조건부 분기 — 미스면 semantic_cache_check로(1차/2차 계층 구조).
# skip_cache(이력 있음)면 semantic 캐시까지 건너뛰고 곧장 retrieve 노드로(design §4).
_graph.add_conditional_edges(
    "cache_check",
    _route_after_cache,
    {"end": END, "semantic_cache": "semantic_cache_check", "retrieve": "retrieve"}
)

# semantic_cache_check 후 조건부 분기 — 미스면 retrieve로
_graph.add_conditional_edges(
    "semantic_cache_check",
    _route_after_semantic_cache,
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
_graph.add_edge("cache_write", "semantic_cache_write")
_graph.add_edge("semantic_cache_write", END)
_graph.add_edge("no_result", END)

# compile
compiled_graph = _graph.compile()


def run_rag_chat_chain(
    question: str,
    history: Optional[list[dict]] = None,
    company_id: Optional[int] = None,
) -> AIResponse:
    """RAG 챗봇 체인 공개 진입점.

    LangGraph StateGraph로 구현된 파이프라인을 invoke하고 최종 응답을 반환.
    history가 비어있지 않으면(대화 맥락 반영 요청, #1493/HAJA-657) skip_cache=True로 캐시
    조회/저장을 모두 우회한다(design §4) — history가 비어있으면 기존과 100% 동일하게 동작(회귀 없음).

    company_id는 Spring이 `@AuthenticationPrincipal`에서만 취득해 넘기는 요청자 회사 식별자(#1584).
    시맨틱 캐시의 조회·저장을 이 회사 스코프로 제한하며, None(회사 미소속 개인회원)이면 시맨틱
    캐시를 조회도 저장도 하지 않는다(fail-closed).
    """
    history = history or []
    initial_state: RagChatState = {
        "question": question,
        "cache_key": "",
        "cached_result": None,
        "docs": [],
        "llm_answer": None,
        "sources": None,
        "final_response": None,
        "cacheable": None,
        "history": history,
        "skip_cache": bool(history),
        "company_id": company_id,
    }

    result_state = compiled_graph.invoke(
        initial_state,
        config={"recursion_limit": 10}
    )

    return result_state["final_response"]
