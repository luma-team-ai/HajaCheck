"""시맨틱 캐시(Chroma `semantic_cache` 컬렉션) 보존 정책 — TTL·용량 상한·무효화 (#1594).

`rag_chat_chain`이 Redis exact 캐시 미스 시 2차로 쓰는 Chroma 캐시에는 지금까지 만료·삭제·상한이
하나도 없었다(#1594 P2). 결과로 ①법규 개정·재임베딩 후에도 구 답변이 영구 서빙되고, ②삭제된
문서를 인용한 citation이 계속 나가고, ③질문 원문이 무기한 평문 보존되고, ④미스마다 append라
볼륨·검색 지연이 단조 증가했다. 이 모듈이 그 네 가지의 단일 정책 지점이다.

정책은 캐시를 **읽는 쪽(rag_chat_chain)**과 **코퍼스를 바꾸는 쪽(rag_ingest)** 양쪽에서 쓰이므로
체인이 아니라 `ai/core/`에 둔다(core → chains 역참조 금지).

---

## 설계 판단 1 — TTL 기본값 = 24시간 (`SEMANTIC_CACHE_TTL_SECONDS`)

TTL은 곧 **구 답변을 서빙할 수 있는 최대 기간**이자 **질문 원문(`page_content`) 평문 보존 상한**
(#1594 harm 3)이다. 짧을수록 안전하고, 길수록 캐시 히트율이 오른다.

비용 쪽부터 보면 시맨틱 캐시는 **순수 지연 최적화**라, TTL을 줄여서 잃는 것은 "만료된 질문이
다음에 다시 오면 LLM을 한 번 더 탄다"뿐이다(설계 판단 2의 비용 평가와 같은 척도 — 두 곳이 같은
비용을 다르게 취급하면 안 된다). 그에 비해 안전 쪽에서 얻는 것이 크다:

- 아래 무효화 훅에는 **구조적 창**이 있다(설계 판단 2의 ⚠️ 참고). 훅이 완전하지 않으므로 TTL은
  "훅을 우회한 변경에 대한 백스톱"만이 아니라 **훅 자체의 누락분을 덮는 상한**이기도 하다.
- 프롬프트·모델·top_k 변경에는 아직 무효화 훅이 없다(후속). 그 변경의 효과가 캐시에 반영되기까지
  걸리는 최대 지연도 TTL이다.

한때 "exact(Redis) 캐시가 24h이므로 시맨틱 캐시는 그보다 길어야 2차 캐시의 이득이 있다"는 근거로
7일을 잡았으나 **이는 틀렸다** — exact 캐시 키는 질문 원문의 sha256(`_cache_key`)이라 표현이 다른
질문은 TTL과 무관하게 애초에 히트하지 않는다. 두 계층의 TTL이 같아도 패러프레이즈 히트라는
시맨틱 캐시의 존재 이유는 그대로다.

=> 24시간. exact 캐시(`CACHE_TTL_SECONDS`)와 같은 창이라 "캐시 세대"를 하나로 추론할 수 있고,
위 구조적 창들의 노출 기간이 1일로 묶인다.

## 설계 판단 2 — 코퍼스 변경 시 **전체 무효화** (선택 삭제 아님)

캐시 항목의 `answer_json.sources`에 인용 `doc_id`가 들어있으므로 "바뀐 문서를 인용한 항목만"
선택 삭제하는 것도 가능하지만, 채택하지 않는다.

1. **정확성** — `hybrid_search`는 관련성 임계값 없이 top-k를 반환한다(rag_chat_chain §4.1). 문서
   하나가 추가·삭제·개정되면 그 문서를 **인용하지 않았던** 질문의 검색 결과 순위까지 바뀌므로,
   인용 기준 선택 삭제는 stale 항목을 남긴다(불완전한 무효화 = 무효화 안 한 것과 같은 신뢰도).
2. **비용** — 전체 무효화의 대가는 "다음 질문들이 한 번씩 LLM을 더 타는 것"뿐이고(설계 판단 1과
   같은 척도), 반대로 문서 삭제·재임베딩은 PLATFORM_ADMIN 콘솔에서만 일어나는 드문 관리
   작업이다. 비대칭이 크다.
3. **실패 모드** — 선택 삭제는 항목마다 `answer_json`을 파싱해야 하고, 파싱 실패 시 stale 항목이
   조용히 남는다(fail-open). 전체 무효화는 파싱이 없어 fail-safe다.

무효화 대상은 `regulations` 변경일 때뿐이다(`hybrid_search`가 이 컬렉션만 검색한다) —
`bm25_index.invalidate()`와 동일하게 다른 컬렉션에 대해서는 no-op으로 흡수한다.

> ⚠️ **알려진 구조적 창(write-after-purge 레이스)** — 무효화는 "즉시·완전"하지 않다.
> `_node_retrieve`가 문서를 읽고 → LLM 응답까지 수 초 → `_node_semantic_cache_write`가 저장하는
> 사이에 관리자가 문서를 삭제하면, 무효화가 먼저 돌고 **in-flight 요청이 그 뒤에** 삭제된 문서를
> 인용한 항목을 새로 써 넣는다. 챗 경로는 `document_ingest_lock`(#1412)을 잡지 않아 막히지 않는다.
> 이 잔여 위험은 TTL(설계 판단 1)이 24시간으로 덮는다.
>
> 근본 해결 방향(후속): ①무효화 세대 스탬프 — 무효화 시 카운터를 올리고, retrieve 시점 값과
> write 시점 값이 다르면 저장을 건너뛴다. 단, 모듈 전역 카운터는 **단일 프로세스에서만** 유효하고
> (현재 uvicorn 단일 워커) 멀티워커로 가면 다른 워커의 무효화를 감지하지 못해 **조용히 반쪽**이
> 되므로, 제대로 하려면 Redis 공유 카운터가 필요하다. ②저장 직전 인용 청크의 존재·동일성 재확인
> (`chunk_ref`/`chunk_hash` 대조) — 아래 `_is_cacheable` 후속 방향과 같은 메커니즘이라 함께 묶어
> 설계하는 편이 낫다. 어느 쪽이든 이 이슈의 범위를 넘어 별도 판단이 필요하다.

## 설계 판단 3 — 용량 상한 = 5,000건 (`SEMANTIC_CACHE_MAX_ENTRIES`), 오래된 순 축출

TTL만으로는 "TTL 창 안에 들어온 질문 변형"이 무한히 쌓일 수 있다(#1594 harm 4). 항목 하나는
bge-m3 벡터(1024차원 float32 ≈ 4KB) + 질문·답변 텍스트라 5,000건이면 벡터만 약 20MB 수준으로
arm1에서 안전하고, HNSW 검색 지연도 이 규모에서는 문제되지 않는다. 축출 순서는 `created_at`
오름차순(오래된 것부터) — TTL과 같은 축을 쓰므로 두 정책이 서로 어긋나지 않는다.

축출은 **여유 마진(히스테리시스)** 을 두고 돈다 — 상한에 정확히 붙여 축출하면 상한 도달 이후
**모든** 저장이 전량 metadata 조회 + 정렬 + 삭제를 사용자 요청 경로에서 동기로 수행하게 된다.
`상한 × 1.1`을 넘을 때만 `상한 × 0.9`까지 한 번에 줄여, 그 비용을 약 `상한 × 0.2`회에 한 번으로
분산한다(실제 보관량 상한은 `상한 × 1.1`).
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

# 캐시 항목의 버전 태그(#1699) — 프롬프트·모델 변경으로 캐시된 답변 형식이 달라지면 호출부
# (현재는 `rag_chat_chain.RAG_CHAT_SEMANTIC_CACHE_VERSION`)가 값을 bump한다. 이 모듈은 값 자체를
# 알 필요가 없다 — `company_id`와 동일하게 "필터 조건 중 하나"로만 다룬다. 옛 버전 항목은 이
# 필드가 없거나 값이 달라 필터에 매칭되지 않고(자연 소멸), TTL 정책(`enforce_retention`)이 실제
# 삭제를 맡는다(destructive 즉시 삭제 없음).
CACHE_VERSION_FIELD = "cache_version"

# 기본값들 — 모두 위 모듈 docstring의 설계 판단 근거를 따른다.
DEFAULT_SEMANTIC_CACHE_THRESHOLD = 0.95
DEFAULT_TTL_SECONDS = 60 * 60 * 24  # 24시간 (설계 판단 1)
DEFAULT_MAX_ENTRIES = 5000

# 설정값 상한 — 하한만 두면 `86400`에 0을 하나 더 붙인 오타(`864000`)가 **경고 없이** 통과해
# 백스톱을 10배로 무력화한다(threshold를 maximum=1.0으로 막은 것과 같은 원칙, #1594 P3).
# 상한을 넘는 값은 "의도적 설정"보다 오타일 확률이 훨씬 높으므로 기본값으로 폴백하고 경고한다.
MAX_TTL_SECONDS = 60 * 60 * 24 * 30  # 30일 — 이보다 긴 보존은 개인정보 관점에서 별도 판단이 필요
MAX_MAX_ENTRIES = 50_000  # 벡터만 약 200MB — arm1에서 이 이상은 운영 판단이 선행돼야 한다

# 용량 축출 히스테리시스(설계 판단 3) — 상한에 붙여 축출하면 상한 도달 후 모든 저장이 전량
# 조회+정렬+삭제를 떠안는다. 트리거 배수를 넘을 때만 목표 배수까지 한 번에 줄인다.
EVICTION_TRIGGER_RATIO = 1.1
EVICTION_TARGET_RATIO = 0.9

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
    return _env_number(
        "SEMANTIC_CACHE_TTL_SECONDS",
        DEFAULT_TTL_SECONDS,
        int,
        minimum=1,
        maximum=MAX_TTL_SECONDS,
    )


def semantic_cache_max_entries() -> int:
    return _env_number(
        "SEMANTIC_CACHE_MAX_ENTRIES",
        DEFAULT_MAX_ENTRIES,
        int,
        minimum=1,
        maximum=MAX_MAX_ENTRIES,
    )


def now_epoch_seconds() -> int:
    return int(time.time())


def ttl_cutoff() -> int:
    """이 시각(epoch seconds)보다 오래 전에 만들어진 항목은 만료로 본다."""
    return now_epoch_seconds() - semantic_cache_ttl_seconds()


def fresh_entry_filter(company_id: int, cache_version: str) -> dict:
    """조회용 where 필터 — 회사 스코프(#1584) + TTL 미경과(#1594) + 캐시 버전(#1699)을 함께 강제한다.

    `created_at`이 아예 없는 레거시 항목(#1594 이전 적재분)은 `$gte` 비교에 매칭되지 않아 자연히
    조회 대상에서 빠진다 — `company_id` 필드가 없던 #1584 이전 항목이 빠지는 것과 같은 fail-closed
    거동이다. 그 항목들의 실제 삭제는 `_delete_legacy_entries()`가 맡는다(같은 이유로 `where`
    삭제에도 안 걸리므로 별도 경로가 필요하다).

    `cache_version`도 같은 원리다 — 옛 버전(또는 필드 자체가 없는 #1699 이전) 항목은 이 조건에
    매칭되지 않아 조회에서 자연히 빠지고, 실제 삭제는 TTL 경과 시 `enforce_retention()`이 맡는다
    (버전이 바뀌었다고 즉시 삭제하지 않음 — destructive 조치 금지, `rag_chat_chain.py`
    `RAG_CHAT_CACHE_PREFIX` 주석 참고). 호출부(현재 `rag_chat_chain`)가 실제 버전 값을 정하고,
    이 모듈은 그 값을 필터 조건으로만 다룬다(company_id와 동일 원칙 — 값의 의미를 모른다).
    """
    return {
        "$and": [
            {"company_id": company_id},
            {CACHE_VERSION_FIELD: cache_version},
            {CREATED_AT_FIELD: {"$gte": ttl_cutoff()}},
        ]
    }


def _semantic_collection():
    """내부 chromadb 컬렉션 핸들. `langchain_chroma==0.1.4`의 `Chroma.delete()` 래퍼가 `where`를
    버리기 때문에(`rag_ingest.delete_document()` 주석 참고) 삭제·집계는 내부 컬렉션을 직접 쓴다."""
    return get_vectorstore(COLLECTION_SEMANTIC_CACHE)._collection


def _delete_legacy_entries(collection) -> int:
    """`created_at`이 아예 없는 레거시 항목을 삭제하고 건수를 반환한다.

    ⚠️ 이게 왜 별도 경로인가 — chromadb의 `where`는 "필드가 없음"을 표현할 수 없다. 그래서
    `where={"created_at": {"$lt": cutoff}}`는 **필드 자체가 없는 항목에 매칭되지 않아**, #1594 이전
    적재분은 조회에서는 빠지지만(fail-closed) **삭제는 영원히 안 되는** 상태로 남는다. 용량 축출에서
    가장 먼저 밀려나긴 하나 그건 상한을 넘겼을 때만 도는 경로라, 컬렉션이 상한 미만이면 레거시
    평문 질문이 무기한 잔존한다(#1594 harm 3와 정면 충돌).

    구현: "필드가 있는 항목"의 여집합으로 구한다. `created_at >= 0`은 필드가 있는 항목만 매칭하므로
    전체 id에서 그 id를 빼면 레거시다. 두 번 다 `include=[]`(id만, metadata 미포함)라 전량 조회여도
    저렴하고, 상한(기본 5,000건)이 걸린 컬렉션이라 규모도 유계다. 한 번 정리되면 이후로는 빈
    집합이라 사실상 no-op이다.

    부수 효과(의도적): `company_id`가 없던 #1584 이전 항목도 `created_at`이 없으므로 함께 정리된다
    — `docs/troubleshooting/rag-semantic-cache-purge-on-1584-deploy.md`가 배포 전제조건으로 남긴
    "수동 1회 purge"가 이 경로로 자동화된다.
    """
    all_ids = set(collection.get(include=[]).get("ids") or [])
    if not all_ids:
        return 0

    stamped_ids = set(
        collection.get(where={CREATED_AT_FIELD: {"$gte": 0}}, include=[]).get("ids") or []
    )
    legacy_ids = list(all_ids - stamped_ids)
    if not legacy_ids:
        return 0

    collection.delete(ids=legacy_ids)
    logger.info("시맨틱 캐시 레거시 항목(created_at 없음) %d건 삭제", len(legacy_ids))
    return len(legacy_ids)


def enforce_retention() -> None:
    """TTL 경과분 + 레거시 항목 삭제, 용량 상한 초과 시 오래된 순 축출.

    호출 지점은 두 곳이다:
    - 캐시 저장 직후(`_node_semantic_cache_write`) — 쓰기가 늘린 만큼 그 자리에서 정리한다.
    - `main.py`의 주기 루프 — **쓰기가 없어도** 돈다. 저장 경로에만 걸어두면 트래픽이 저조하거나
      exact 캐시만 히트하거나 개인회원만 접속하는 기간에 정리가 통째로 멈춰, 조회 필터가 가려줄 뿐
      질문 원문 평문은 디스크에 무기한 남는다. "TTL이 평문 보존 상한"이라는 설계 판단 1의 단언은
      주기 실행이 있어야 성립한다(#1594 P2).

    보존 정책 유지 실패가 사용자 요청을 실패시키면 안 되므로 예외를 삼키고 로깅만 한다 —
    이미 답변은 생성돼 있고, 정리는 다음 호출 때 다시 시도된다(멱등).
    """
    try:
        collection = _semantic_collection()

        # 1) TTL 경과분 — where 삭제 한 번. 매칭 0건이어도 조용히 성공한다(실측 확인).
        collection.delete(where={CREATED_AT_FIELD: {"$lt": ttl_cutoff()}})

        # 2) created_at 자체가 없는 레거시 항목 — where로 표현이 안 돼 별도 경로. 용량 분기와
        #    무관하게 항상 수행한다(위 _delete_legacy_entries docstring 참고).
        _delete_legacy_entries(collection)

        # 3) 용량 상한 — 히스테리시스(설계 판단 3). 트리거 배수를 넘지 않으면 여기서 끝이라
        #    대부분의 저장은 count() 한 번만 추가로 낸다.
        max_entries = semantic_cache_max_entries()
        if collection.count() <= max_entries * EVICTION_TRIGGER_RATIO:
            return

        existing = collection.get(include=["metadatas"])
        ids = existing.get("ids") or []
        metadatas = existing.get("metadatas") or []
        ordered = sorted(
            zip(ids, metadatas),
            key=lambda pair: (pair[1] or {}).get(CREATED_AT_FIELD, 0),
        )
        # 목표 배수까지 한 번에 줄인다 — 상한에 딱 맞추면 다음 저장이 곧바로 다시 트리거된다.
        target = int(max_entries * EVICTION_TARGET_RATIO)
        overflow_ids = [entry_id for entry_id, _metadata in ordered[: max(len(ordered) - target, 0)]]
        if overflow_ids:
            collection.delete(ids=overflow_ids)
            logger.info("시맨틱 캐시 용량 상한 초과 — 오래된 %d건 축출", len(overflow_ids))
    except Exception:  # noqa: BLE001 — 캐시 유지보수 실패가 응답을 막으면 안 된다
        logger.exception("시맨틱 캐시 보존 정책 적용 실패 — 다음 호출 시 재시도된다")


def invalidate_on_corpus_change(changed_collection: str = COLLECTION_REGULATIONS) -> int:
    """**코퍼스가 바뀌었을 때** 시맨틱 캐시를 통째로 무효화하고 삭제 건수를 반환한다(설계 판단 2).

    ⚠️ 인자는 "무효화할 대상"이 아니라 **"방금 변경된 코퍼스 컬렉션"** 이다. 지워지는 것은 언제나
    `semantic_cache`다. 예전 이름(`purge_semantic_cache(collection)`)은 호출부가
    `purge_semantic_cache(COLLECTION_REGULATIONS)`로 읽혀 "regulations를 purge한다"는 정반대 의미로
    오해되기 딱 좋았다(#1594 P3) — 법규 코퍼스를 날리는 코드로 잘못 읽히면 위험이 크다.

    `regulations`가 아닌 컬렉션 변경은 `hybrid_search` 대상이 아니므로 no-op이다
    (`bm25_index.invalidate()`와 동일한 규약).

    컬렉션 자체를 `delete_collection`으로 지우지 않고 id 전량 삭제로 비운다 — 컬렉션 객체를
    들고 있는 다른 호출부의 핸들이 무효해지지 않고, `enforce_retention()`과 같은 방식이라
    거동을 하나로 유지할 수 있다. 상한(기본 5,000건)이 걸린 컬렉션이라 전량 조회는 저렴하다.
    """
    if changed_collection != COLLECTION_REGULATIONS:
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
