# RAG 시맨틱 캐시 회사 스코프(#1584) — 배포 시 `semantic_cache` 1회 purge

> **문서 버전:** v0.1 · **최종 수정:** 2026-08-05

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

## 관련

- 이슈 #1584 · 설계 근거는 `docs/api-contract/contract.md` "POST /ai/rag-chat" 절(시맨틱 캐시 회사 스코프)
- 후속(P2 umbrella): 시맨틱 캐시 TTL·무효화·용량 상한, 문서 재임베딩 시 purge 자동화, 질문 원문 보존 정책
