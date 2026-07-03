"""Chroma PersistentClient 팩토리 — AI_개발_컨벤션.md §6

- Chroma 접근은 이 팩토리만 사용
- 컬렉션: regulations(법규·지침), defect_kb(하자 지식)
- 재임베딩은 명시적 배치 잡으로만 실행 (쓰기 락 충돌 방지)
"""

COLLECTION_REGULATIONS = "regulations"
COLLECTION_DEFECT_KB = "defect_kb"


def get_vectorstore(collection: str):
    # TODO(AI-LLM 코치): chromadb.PersistentClient 설정
    raise NotImplementedError("온보딩 세션(7/15) 전 AI-LLM 코치가 구현")
