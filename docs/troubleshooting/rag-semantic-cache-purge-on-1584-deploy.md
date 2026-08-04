# RAG 시맨틱 캐시 회사 스코프(#1584) — 배포 시 `semantic_cache` 1회 purge

> **문서 버전:** v0.1 · **최종 수정:** 2026-08-05
>
> (#1594로 보존 정책이 코드에 들어왔다 — 아래 "#1594 이후: 자동 보존 정책" 절 참고. 이 1회 purge
> 절차 자체는 여전히 유효하며, 운영 수동 purge 수단으로도 계속 쓴다.)

#1584(시맨틱 캐시 회사 스코프 강제) 배포 시 **`semantic_cache` 컬렉션을 1회 비워야 합니다.** 코드 변경은 필요 없고, 배포 절차에만 추가되는 전제조건입니다.

## 왜 필요한가

#1584 이전에 쌓인 캐시 항목에는 `metadata.company_id`가 없습니다. 조회는 `filter={"company_id": …}`로 들어가므로 **이 레거시 항목은 어떤 회사 필터에도 매칭되지 않습니다**(실측 확인). 즉 앱을 경유한 노출 경로는 이미 닫혀 있어 **긴급도는 낮습니다.**

다만 그 항목들이 그대로 남으면:

1. **영구히 읽히지 않는 쓰레기** — 어떤 요청도 매칭시킬 수 없는데 디스크와 HNSW 인덱스를 계속 차지합니다.
2. **질문 원문이 계속 보관됨** — `page_content`가 사용자 질문 원문입니다. 전 회사의 질문이 스코프 필드 없이 한 컬렉션에 남습니다.
3. **교차 오염 가능성이 있던 답변이 보존됨** — #1584 이전 로직에서 생성·재사용된 답변이라, 회사 경계가 지켜졌다는 보장이 없는 상태로 남습니다.

## 방법

`semantic_cache` **컬렉션만** 삭제합니다. 삭제 후 다음 저장 시 자동 재생성되므로 **컨테이너 재기동은 불필요**합니다.

```bash
docker compose -f docker-compose.arm1.yml exec fastapi python -c "
import chromadb
client = chromadb.PersistentClient(path='/app/chroma_data')
before = client.get_collection('semantic_cache').count()
client.delete_collection('semantic_cache')
print(f'semantic_cache purge 완료 — {before}건 삭제')
"
```

검증(0건 또는 컬렉션 부재면 정상):

```bash
docker compose -f docker-compose.arm1.yml exec fastapi python -c "
import chromadb
print([c.name for c in chromadb.PersistentClient(path='/app/chroma_data').list_collections()])
"
```

## ⚠️ 하지 말 것 — `chroma_data` 볼륨 통째로 삭제

`chroma_data` 볼륨에는 `semantic_cache` 외에 **`regulations`(법규 코퍼스)** 와 `defect_kb`가 함께 들어있습니다. 볼륨을 지우면 법규 임베딩까지 날아가 **전체 재적재(re-ingest)** 가 필요해집니다 — 비용도 크고 그동안 RAG 답변 품질이 무너집니다.

`delete_collection('semantic_cache')`는 같은 볼륨의 다른 컬렉션에 영향이 없음을 실측으로 확인했습니다(purge 후 `regulations` 건수 보존, `semantic_cache`만 사라짐).

## 롤백 시

#1584을 되돌리면 필터 없는 전역 조회로 돌아가므로, purge 여부와 무관하게 **회사 간 교차 노출이 재발합니다.** 롤백은 이 이슈를 다시 여는 것과 같다고 보고 판단해야 합니다.

## #1594 이후: 자동 보존 정책 (수동 purge가 필요한 경우가 줄었다)

#1594로 `ai-server/ai/core/semantic_cache.py`가 보존 정책의 단일 지점이 됐습니다. 설계 판단의 근거는 그 모듈 docstring에 있고, 요약하면:

| 정책 | 동작 | 환경변수(기본값) |
|---|---|---|
| **TTL** | 저장 시 `created_at`(epoch seconds)를 기록하고, 조회는 `created_at >= now - TTL`로 필터합니다. 저장 직후 만료분을 삭제합니다. | `SEMANTIC_CACHE_TTL_SECONDS` (604800 = 7일) |
| **용량 상한** | 초과 시 `created_at` 오래된 순으로 축출합니다. | `SEMANTIC_CACHE_MAX_ENTRIES` (5000) |
| **코퍼스 변경 무효화** | `regulations` 문서의 (재)임베딩·삭제가 끝나면 `semantic_cache`를 **전체** 무효화합니다(인용 기준 선택 삭제가 아닌 이유는 모듈 docstring "설계 판단 2"). `defect_kb` 변경은 no-op. | — |
| **히트 임계값** | 런타임에 읽습니다. 잘못된 값은 경고 로그 + 기본값 폴백이라 앱 기동을 막지 않습니다. | `SEMANTIC_CACHE_THRESHOLD` (0.95) |

즉 **관리자 콘솔에서 문서를 삭제·재임베딩하면 캐시 무효화는 자동**입니다. 위 수동 purge가 여전히 필요한 경우는 다음뿐입니다:

1. **레거시 항목 정리(이 문서의 원래 목적)** — `company_id`(#1584 이전)·`created_at`(#1594 이전)이 없는 항목은 어떤 필터에도 매칭되지 않아 조회 불가지만, TTL 삭제 대상으로도 잡히지 않습니다(필드 자체가 없어 `$lt` 비교에 걸리지 않음). 용량 상한 축출에서 가장 먼저 밀려나긴 하나, 배포 시 1회 purge로 즉시 정리하는 편이 확실합니다.
2. **자동 무효화가 실패했을 때** — `purge_semantic_cache()`는 실패해도 문서 삭제·재임베딩 자체를 되돌리지 않고 `logger.exception`만 남깁니다(캐시 정리 실패가 관리 작업을 실패시키면 안 되므로). 로그에 `시맨틱 캐시 무효화 실패`가 보이면 위 절차로 수동 purge 하세요.
3. **Chroma를 코드 밖에서 직접 조작했을 때** — 무효화 훅을 우회하므로 TTL(최대 7일) 백스톱에만 의존하게 됩니다.

## 관련

- 이슈 #1584 · 설계 근거는 `docs/api-contract/contract.md` "POST /ai/rag-chat" 절(시맨틱 캐시 회사 스코프)
- 이슈 #1594 — TTL·용량 상한·코퍼스 변경 무효화 도입(`ai-server/ai/core/semantic_cache.py`)
- 남은 후속: 질문 원문의 **개별** 삭제 요구권 대응(현재는 TTL로 보관 상한만 걸린 상태 — 특정 사용자의 질문만 골라 지우는 수단은 별도 판단 필요)
