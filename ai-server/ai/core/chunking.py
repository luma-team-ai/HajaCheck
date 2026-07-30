"""문서 청킹 유틸 — AI_개발_컨벤션.md §6

법규 문서(regulations 컬렉션)는 조문(제N조)·항(①②③…) 경계 우선 분리,
그 외 일반 문서는 500자/오버랩 50자 기준. RAG 담당자는 이 함수만 사용한다.
"""
import re

from langchain_text_splitters import RecursiveCharacterTextSplitter

DEFAULT_CHUNK_SIZE = 500
DEFAULT_CHUNK_OVERLAP = 50

_ARTICLE = re.compile(r"(?<!\n)(?=제\d+조)")
_CLAUSE = re.compile(r"(?<!\n)(?=[①-⑳])")


def _normalize_law_breaks(text: str) -> str:
    """pdftotext 등으로 개행이 소실된 원문 대비 — 조문·항 앞에 개행 삽입.
    (조문 경계 separators가 실제로 걸리려면 원문에 개행이 있어야 함)
    """
    text = _ARTICLE.sub("\n", text)
    text = _CLAUSE.sub("\n", text)
    return text


def split_regulation_text(
    text: str, chunk_size: int = DEFAULT_CHUNK_SIZE, chunk_overlap: int = DEFAULT_CHUNK_OVERLAP
) -> list[str]:
    splitter = RecursiveCharacterTextSplitter(
        separators=["\n제", "\n①", "\n\n", "\n"],
        chunk_size=chunk_size,
        chunk_overlap=chunk_overlap,
    )
    return splitter.split_text(_normalize_law_breaks(text))


def split_general_text(
    text: str, chunk_size: int = DEFAULT_CHUNK_SIZE, chunk_overlap: int = DEFAULT_CHUNK_OVERLAP
) -> list[str]:
    splitter = RecursiveCharacterTextSplitter(chunk_size=chunk_size, chunk_overlap=chunk_overlap)
    return splitter.split_text(text)


# 조문(article)/항(clause) 메타데이터 추출 — #22/HAJA-35. 로컬 데모 스크립트(PR #477 청킹 실험)의 임시
# 정규식을 정식 컨벤션 함수로 승격한다(docs/design/ai/rag_chroma_schema.md §4 article/clause 필드 정의).
_ARTICLE_METADATA = re.compile(r"제\d+조")
_CLAUSE_METADATA = re.compile(r"[①-⑳]")
_CLAUSE_SEARCH_WINDOW = 50


def extract_article_metadata(chunk: str) -> dict:
    """청크 텍스트에서 조문(article)/항(clause) 메타데이터를 추출한다.

    - article: 청크 내 첫 "제N조" 매치. 없으면 결과 dict에 키 자체를 넣지 않는다
      (rag_chroma_schema.md §3 "결측값은 키 자체를 생성하지 않는다").
    - clause: article이 있을 때만, 그 매치 직후 50자 이내에 등장하는 첫 "①"~"⑳". article이 없거나
      그 범위 안에 항 기호가 없으면 clause 키를 넣지 않는다.

    반환값은 그대로 Chroma add_texts()의 metadata 항목에 병합할 수 있는 형태(str 값만 포함)다.
    """
    metadata: dict = {}
    article_match = _ARTICLE_METADATA.search(chunk)
    if not article_match:
        return metadata
    metadata["article"] = article_match.group()

    window = chunk[article_match.end():article_match.end() + _CLAUSE_SEARCH_WINDOW]
    clause_match = _CLAUSE_METADATA.search(window)
    if clause_match:
        metadata["clause"] = clause_match.group()
    return metadata
