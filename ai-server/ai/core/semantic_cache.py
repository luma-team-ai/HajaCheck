"""시맨틱 캐시(Chroma `semantic_cache` 컬렉션) 보존 정책 — TTL·용량 상한·무효화 (#1594).

`rag_chat_chain`이 Redis exact 캐시 미스 시 2차로 쓰는 Chroma 캐시에는 지금까지 만료·삭제·상한이
하나도 없었다(#1594 P2). 결과로 ①법규 개정·재임베딩 후에도 구 답변이 영구 서빙되고, ②삭제된
문서를 인용한 citation이 계속 나가고, ③질문 원문이 무기한 평문 보존되고, ④미스마다 append라
볼륨·검색 지연이 단조 증가했다. 이 모듈이 그 네 가지의 단일 정책 지점이다.

정책은 캐시를 **읽는 쪽(rag_chat_chain)**과 **코퍼스를 바꾸는 쪽(rag_ingest)** 양쪽에서 쓰이므로
체인이 아니라 `ai/core/`에 둔다(core → chains 역참조 금지).

---

## 설계 판단 1 — TTL 기본값 = 7일 (`SEMANTIC_CACHE_TTL_SECONDS`)

하한: exact(Redis) 캐시가 이미 24h(`CACHE_TTL_SECONDS`)다. 시맨틱 캐시의 존재 이유는 "같은 질문을
다르게 표현했을 때도 히트시키는 것" — exact 캐시와 같은 24h로 두면 두 계층이 사실상 같은 창을
공유해 2차 캐시의 이득이 거의 사라진다. 그래서 24h보다는 반드시 길어야 한다.

상한: 구 답변을 서빙하는 최대 기간이 곧 TTL이다. 코퍼스 변경(삭제·재임베딩)은 아래
`purge_semantic_cache()`가 이벤트 기반으로 즉시 무효화하므로, TTL은 그 훅을 우회하는 변경
(운영자의 수동 Chroma 조작, 다른 경로의 재적재 등)에 대한 **백스톱**일 뿐이다. 법규 QA 도메인에서
"최악의 경우 1주일 묵은 답변"은 백스톱으로 수용 가능한 반면, 그 이상은 개정 대응 지연이 실질
피해가 된다. 질문 원문(`page_content`) 평문 보존 기간도 같은 값으로 상한이 걸린다(#1594 harm 3 —
삭제 요구권 대응 수단).

=> 두 경계 사이에서 7일. 운영 배포 주기(주 단위)와도 정합해 "배포 한 번 = 캐시 한 세대"가 된다.

## 설계 판단 2 — 코퍼스 변경 시 **전체 purge** (선택 삭제 아님)

캐시 항목의 `answer_json.sources`에 인용 `doc_id`가 들어있으므로 "바뀐 문서를 인용한 항목만"
선택 삭제하는 것도 가능하지만, 채택하지 않는다.

1. **정확성** — `hybrid_search`는 관련성 임계값 없이 top-k를 반환한다(rag_chat_chain §4.1). 문서
   하나가 추가·삭제·개정되면 그 문서를 **인용하지 않았던** 질문의 검색 결과 순위까지 바뀌므로,
   인용 기준 선택 삭제는 stale 항목을 남긴다(불완전한 무효화 = 무효화 안 한 것과 같은 신뢰도).
2. **비용** — 시맨틱 캐시는 순수 지연 최적화다. 전체 purge의 대가는 "다음 질문들이 한 번씩 LLM을
   더 타는 것"뿐이고, 반대로 문서 삭제·재임베딩은 PLATFORM_ADMIN 콘솔에서만 일어나는 드문
   관리 작업이다. 비대칭이 크다.
3. **실패 모드** — 선택 삭제는 항목마다 `answer_json`을 파싱해야 하고, 파싱 실패 시 stale 항목이
   조용히 남는다(fail-open). 전체 purge는 파싱이 없어 fail-safe다.

무효화 대상은 `regulations` 변경일 때뿐이다(`hybrid_search`가 이 컬렉션만 검색한다) —
`bm25_index.invalidate()`와 동일하게 다른 컬렉션에 대해서는 no-op으로 흡수한다.

## 설계 판단 3 — 용량 상한 = 5,000건 (`SEMANTIC_CACHE_MAX_ENTRIES`), 오래된 순 축출

TTL만으로는 "7일 안에 들어온 질문 변형"이 무한히 쌓일 수 있다(#1594 harm 4). 항목 하나는
bge-m3 벡터(1024차원 float32 ≈ 4KB) + 질문·답변 텍스트라 5,000건이면 벡터만 약 20MB 수준으로
arm1에서 안전하고, HNSW 검색 지연도 이 규모에서는 문제되지 않는다. 축출 순서는 `created_at`
오름차순(오래된 것부터) — TTL과 같은 축을 쓰므로 두 정책이 서로 어긋나지 않는다.
"""
from __future__ import annotations

import logging
import os
import time
from typing import Callable, Optional, TypeVar

from ai.core.vectorstore import (
    COLLECTION_REGULATIONS,
    COLLECTION_SEMANTIC_CACHE,
    get_vectorstore,
)

logger = logging.getLogger(__name__)

# 캐시 항목 생성 시각(epoch seconds, int) — Chroma metadata는 str/int/float/bool만 허용하므로
# datetime이 아니라 int로 저장한다. 숫자여야 where 연산자($lt/$gte)로 필터·삭제할 수 있다.
CREATED_AT_FIELD = "created_at"

# 기본값들 — 모두 위 모듈 docstring의 설계 판단 근거를 따른다.
DEFAULT_SEMANTIC_CACHE_THRESHOLD = 0.95
DEFAULT_TTL_SECONDS = 60 * 60 * 24 * 7  # 7일
DEFAULT_MAX_ENTRIES = 5000

_T = TypeVar("_T", int, float)


def _env_number(
    name: str,
    default: _T,
    caster: Callable[[str], _T],
    minimum: Optional[float] = None,
    maximum: Optional[float] = None,
) -> _T:
    """환경변수를 **호출 시점에** 읽어 숫자로 변환한다. 실패하면 경고만 남기고 기본값을 쓴다.

    `os.getenv`를 모듈 임포트 시점이 아니라 함수 내부에서 호출하는 것은 이 레포의 명시적
    컨벤션이다(`ai/core/vectorstore.py` `_client()` 주석 — 모듈 최상단에서 읽으면 값이 첫 임포트
    시점에 고정돼 테스트의 `patch.dict(os.environ, ...)`가 반영되지 않는다).

    변환 실패에 기본값으로 폴백하는 것도 의도적이다(#1594 P3) — 예전 구현은 임포트 시점에
    `float(os.getenv(...))`를 그대로 불러, 오타 하나가 `ValueError`로 **앱 전체 기동을 죽였다**.
    캐시 임계값 하나 때문에 서비스가 안 뜨는 것보다, 기본값으로 뜨고 경고를 남기는 쪽이 옳다.
    """
    raw = os.getenv(name)
    if raw is None or not raw.strip():
        return default

    try:
        value = caster(raw.strip())
    except (TypeError, ValueError):
        logger.warning(
            "%s 값을 숫자로 해석할 수 없어 기본값(%s)을 사용한다 — 운영 env 설정을 확인할 것", name, default
        )
        return default

    if (minimum is not None and value < minimum) or (maximum is not None and value > maximum):
        logger.warning(
            "%s 값이 허용 범위(%s~%s)를 벗어나 기본값(%s)을 사용한다 — 운영 env 설정을 확인할 것",
            name, minimum, maximum, default,
        )
        return default

    return value


def semantic_cache_threshold() -> float:
    """시맨틱 캐시 히트 판정 유사도 임계값.

    `langchain_chroma.similarity_search_with_score`는 distance(작을수록 유사)를 반환하고 컬렉션이
    `hnsw:space=cosine`이므로 `distance = 1 - cosine_similarity`다. 따라서 호출부는
    `score <= (1 - threshold)`일 때 hit으로 판정한다.

    허용 범위는 (0, 1] — 0 이하나 1 초과는 "hit이 절대 안 나거나 아무거나 hit"이라 설정 실수로만
    나올 수 있는 값이라 기본값으로 폴백한다.
    """
    return _env_number(
        "SEMANTIC_CACHE_THRESHOLD",
        DEFAULT_SEMANTIC_CACHE_THRESHOLD,
        float,
        minimum=1e-9,
        maximum=1.0,
    )


def semantic_cache_ttl_seconds() -> int:
    return _env_number("SEMANTIC_CACHE_TTL_SECONDS", DEFAULT_TTL_SECONDS, int, minimum=1)


def semantic_cache_max_entries() -> int:
    return _env_number("SEMANTIC_CACHE_MAX_ENTRIES", DEFAULT_MAX_ENTRIES, int, minimum=1)


def now_epoch_seconds() -> int:
    return int(time.time())


def ttl_cutoff() -> int:
    """이 시각(epoch seconds)보다 오래 전에 만들어진 항목은 만료로 본다."""
    return now_epoch_seconds() - semantic_cache_ttl_seconds()


def fresh_entry_filter(company_id: int) -> dict:
    """조회용 where 필터 — 회사 스코프(#1584) + TTL 미경과(#1594)를 함께 강제한다.

    `created_at`이 아예 없는 레거시 항목(#1594 이전 적재분)은 `$gte` 비교에 매칭되지 않아 자연히
    조회 대상에서 빠진다 — `company_id` 필드가 없던 #1584 이전 항목이 빠지는 것과 같은 fail-closed
    거동이다(잔여 항목 정리는 `docs/troubleshooting/rag-semantic-cache-purge-on-1584-deploy.md`).
    """
    return {
        "$and": [
            {"company_id": company_id},
            {CREATED_AT_FIELD: {"$gte": ttl_cutoff()}},
        ]
    }


def _semantic_collection():
    """내부 chromadb 컬렉션 핸들. `langchain_chroma==0.1.4`의 `Chroma.delete()` 래퍼가 `where`를
    버리기 때문에(`rag_ingest.delete_document()` 주석 참고) 삭제·집계는 내부 컬렉션을 직접 쓴다."""
    return get_vectorstore(COLLECTION_SEMANTIC_CACHE)._collection


def enforce_retention() -> None:
    """TTL 경과분 삭제 + 용량 상한 초과분(오래된 순) 축출. 캐시 저장 직후에 호출한다.

    보존 정책 유지 실패가 사용자 요청을 실패시키면 안 되므로 예외를 삼키고 로깅만 한다 —
    이미 답변은 생성돼 있고, 정리는 다음 저장 때 다시 시도된다(멱등).
    """
    try:
        collection = _semantic_collection()

        # 1) TTL 경과분 — where 삭제 한 번. 매칭 0건이어도 조용히 성공한다(실측 확인).
        collection.delete(where={CREATED_AT_FIELD: {"$lt": ttl_cutoff()}})

        # 2) 용량 상한 — count()가 상한 이하면 여기서 끝(대부분의 요청이 이 경로).
        max_entries = semantic_cache_max_entries()
        if collection.count() <= max_entries:
            return

        existing = collection.get(include=["metadatas"])
        ids = existing.get("ids") or []
        metadatas = existing.get("metadatas") or []
        # created_at이 없는 레거시 항목은 0으로 취급 → 가장 먼저 축출된다(어차피 조회 불가 항목).
        ordered = sorted(
            zip(ids, metadatas),
            key=lambda pair: (pair[1] or {}).get(CREATED_AT_FIELD, 0),
        )
        overflow_ids = [entry_id for entry_id, _metadata in ordered[: len(ordered) - max_entries]]
        if overflow_ids:
            collection.delete(ids=overflow_ids)
            logger.info("시맨틱 캐시 용량 상한 초과 — 오래된 %d건 축출", len(overflow_ids))
    except Exception:  # noqa: BLE001 — 캐시 유지보수 실패가 응답을 막으면 안 된다
        logger.exception("시맨틱 캐시 보존 정책 적용 실패 — 다음 저장 시 재시도된다")


def purge_semantic_cache(collection: str = COLLECTION_REGULATIONS) -> int:
    """법규 코퍼스가 바뀌면 시맨틱 캐시를 통째로 무효화하고 삭제 건수를 반환한다(설계 판단 2).

    `regulations`가 아닌 컬렉션 변경은 `hybrid_search` 대상이 아니므로 no-op이다
    (`bm25_index.invalidate()`와 동일한 규약).

    컬렉션 자체를 `delete_collection`으로 지우지 않고 id 전량 삭제로 비운다 — 컬렉션 객체를
    들고 있는 다른 호출부의 핸들이 무효해지지 않고, `enforce_retention()`과 같은 방식이라
    거동을 하나로 유지할 수 있다. 상한(기본 5,000건)이 걸린 컬렉션이라 전량 조회는 저렴하다.
    """
    if collection != COLLECTION_REGULATIONS:
        return 0

    try:
        chroma_collection = _semantic_collection()
        ids = chroma_collection.get(include=[]).get("ids") or []
        if not ids:
            return 0
        # chromadb는 빈 리스트 delete를 ValueError로 거절한다(실측) — 위에서 반드시 걸러낸다.
        chroma_collection.delete(ids=ids)
        logger.info("법규 코퍼스 변경으로 시맨틱 캐시 무효화 — %d건 삭제", len(ids))
        return len(ids)
    except Exception:  # noqa: BLE001 — 무효화 실패가 문서 삭제/재임베딩 자체를 되돌리면 안 된다
        logger.exception(
            "시맨틱 캐시 무효화 실패 — 구 답변이 최대 TTL(%d초)까지 서빙될 수 있다. "
            "docs/troubleshooting/rag-semantic-cache-purge-on-1584-deploy.md 절차로 수동 purge 할 것",
            semantic_cache_ttl_seconds(),
        )
        return 0
