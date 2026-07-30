"""chunking 규칙 검증 (AI_개발_컨벤션.md §6) — pdftotext류 추출본은 개행이 없다는 전제로 검증."""
from ai.core.chunking import extract_article_metadata, split_general_text, split_regulation_text

# 실제 원문(pdftotext 추출)과 동일하게 개행 없이 이어붙인 샘플
FLAT_LAW_SAMPLE = (
    "제1조(목적) 이 지침은 시설물의 안전점검 및 정밀안전진단의 실시 등에 필요한 사항을 규정한다. "
    "제2조(정의) 이 지침에서 사용하는 용어의 뜻은 다음과 같다. "
    "①안전점검이란 경험과 기술을 갖춘 자가 육안이나 점검기구로 조사하는 것을 말한다. "
    "②정밀안전진단이란 정밀한 외관조사·시험·측정 등을 실시하는 것을 말한다. "
    "제3조(적용범위) 이 지침은 관계 법령에서 정한 시설물에 적용한다."
)


def test_regulation_split_breaks_on_article_boundaries_even_without_newlines():
    chunks = split_regulation_text(FLAT_LAW_SAMPLE, chunk_size=80, chunk_overlap=10)
    assert len(chunks) >= 3
    assert any(c.startswith("제1조") for c in chunks)
    assert any(c.startswith("제2조") for c in chunks)
    assert any(c.startswith("제3조") for c in chunks)


def test_regulation_split_respects_chunk_size_bound():
    chunks = split_regulation_text(FLAT_LAW_SAMPLE, chunk_size=90, chunk_overlap=0)
    assert len(chunks) > 1
    assert all(len(c) <= 90 for c in chunks)


def test_general_split_uses_fixed_size_no_article_awareness():
    text = "가나다라마바사아자차카타파하" * 10
    chunks = split_general_text(text, chunk_size=50, chunk_overlap=5)
    assert all(len(c) <= 50 for c in chunks)


def test_extract_article_metadata_조문과항이있으면둘다추출():
    metadata = extract_article_metadata("제2조(정의) ①안전점검이란 경험과 기술을 갖춘 자가 조사하는 것을 말한다.")
    assert metadata["article"] == "제2조"
    assert metadata["clause"] == "①"


def test_extract_article_metadata_조문만있고항은50자밖에있으면clause제외():
    padding = "가" * 60
    metadata = extract_article_metadata(f"제3조(적용범위) {padding} ①이 항은 너무 멀어서 제외된다.")
    assert metadata["article"] == "제3조"
    assert "clause" not in metadata


def test_extract_article_metadata_조문없으면빈dict():
    metadata = extract_article_metadata("이 문단에는 조문 번호가 없다.")
    assert metadata == {}


def test_extract_article_metadata_항만있고조문없으면clause도추출안함():
    # article이 없으면 clause 탐색 자체를 하지 않는다(정의: article 매치 직후 50자 이내).
    metadata = extract_article_metadata("①이 문단은 조문 없이 항만 등장한다.")
    assert metadata == {}


if __name__ == "__main__":
    test_regulation_split_breaks_on_article_boundaries_even_without_newlines()
    test_regulation_split_keeps_clause_with_its_article_when_it_fits()
    test_general_split_uses_fixed_size_no_article_awareness()
    test_extract_article_metadata_조문과항이있으면둘다추출()
    test_extract_article_metadata_조문만있고항은50자밖에있으면clause제외()
    test_extract_article_metadata_조문없으면빈dict()
    test_extract_article_metadata_항만있고조문없으면clause도추출안함()
    print("OK: chunking self-check passed")
