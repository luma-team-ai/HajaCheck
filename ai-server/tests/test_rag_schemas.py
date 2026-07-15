import pytest
from pydantic import ValidationError

from ai.core.schemas import AIResponse, RagAnswerData, SourceCitation


def _citation(collection: str = "regulations", doc_id: str = "42") -> SourceCitation:
    return SourceCitation(
        doc_id=doc_id,
        title="시설물의 안전 및 유지관리에 관한 특별법",
        collection=collection,
        locator="제12조",
        snippet="관리주체는 시설물의 안전점검을 정기적으로 실시하여야 한다.",
        chunk_ref="42_3",
    )


def test_source_citation_allows_known_collections():
    assert _citation("regulations").collection == "regulations"
    assert _citation("defect_kb").collection == "defect_kb"


def test_source_citation_rejects_db_enum_collection_label():
    with pytest.raises(ValidationError):
        _citation("REGULATIONS")


@pytest.mark.parametrize("doc_id", ["abc", "0", "42_3"])
def test_source_citation_rejects_non_positive_integer_doc_id(doc_id: str):
    with pytest.raises(ValidationError):
        _citation(doc_id=doc_id)


@pytest.mark.parametrize("field", ["locator", "snippet"])
def test_source_citation_requires_rendered_citation_text(field: str):
    payload = _citation().model_dump()
    payload.pop(field)

    with pytest.raises(ValidationError):
        SourceCitation.model_validate(payload)


def test_rag_answer_data_serializes_locator_and_snippet_in_envelope():
    data = RagAnswerData(answer="균열 보수 기준 답변", sources=[_citation()])
    body = AIResponse.ok(data=data.model_dump()).model_dump()

    source = body["data"]["sources"][0]
    assert int(source["doc_id"]) == 42
    assert source["locator"] == "제12조"
    assert source["snippet"] == "관리주체는 시설물의 안전점검을 정기적으로 실시하여야 한다."
    assert source["chunk_ref"] == "42_3"
