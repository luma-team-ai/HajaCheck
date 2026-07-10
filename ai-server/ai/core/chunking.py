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
