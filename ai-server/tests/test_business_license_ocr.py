"""business-license-ocr 실제 구현 검증 (HAJA-169/#552, RapidOCR→EasyOCR 교체 #605).

- 라우터: 입력 검증(image_base64 필수) + 성공/실패 envelope (체인은 모킹)
- 체인: OCR 결과 없음 폴백, 정규식 사업자등록번호 보정, LLM 실패 시 예외 전파 확인 (get_ocr_engine/
  get_llm 모킹 — 실제 EasyOCR/HF 호출 없음)
- 파싱 유닛(실제 OCR 아님): EasyOCR `readtext()` 응답 형식(`[(box, text, conf), ...]`)을
  모킹해 `_extract_text_lines`가 실제 이미지 픽스처 경로로도 정상 파싱하는지 확인. (RapidOCR
  시절과 달리 EasyOCR는 항상 언어 모델을 네트워크에서 다운로드해야 해서 "번들 모델로 오프라인
  실 파이프라인 검증"이 불가능하다 — AI_개발_컨벤션 "HF 모델 관련 테스트는 항상 모킹" 원칙에
  따라 여기서도 모킹으로 대체한다. EasyOCR 자체의 wiring은 `tests/test_ocr_client.py`에서
  별도 검증.)
"""
from pathlib import Path
from unittest.mock import MagicMock, patch

from fastapi.testclient import TestClient

from ai.chains.business_license_ocr_chain import (
    MAX_IMAGE_BASE64_LENGTH,
    BusinessLicenseOcrError,
    BusinessLicenseOcrExtract,
    _decode_image,
    _extract_text_lines,
    _find_business_reg_number,
    _normalize_reg_number,
    _normalize_start_date,
    run_business_license_ocr_chain,
)
from main import app

client = TestClient(app)

FIXTURES_DIR = Path(__file__).parent / "fixtures"


# ── 라우터 레벨 (체인 모킹) ──────────────────────────────────────────────


def test_business_license_ocr_requires_image_base64():
    res = client.post("/ai/business-license-ocr", json={})
    assert res.status_code == 200
    body = res.json()
    assert body["success"] is False
    assert body["error"]["code"] == "VALIDATION_ERROR"


def test_business_license_ocr_rejects_oversized_image_base64():
    """코드 리뷰 P2 — image_base64에 크기 상한(FE 10MB 업로드 상한의 base64 상당치)이 없으면
    대형 페이로드로 디코드/OCR 단계 CPU·메모리를 고갈시킬 수 있다(DoS). Field(max_length=...)로
    FastAPI 요청 파싱 단계에서 거부되는지 확인 — 체인/OCR까지 도달하지 않고 422로 즉시 컷된다
    (다른 필드의 Field(min_length=...) 검증과 동일한 계층, ConfirmedDefectInput 참고)."""
    oversized = "a" * (MAX_IMAGE_BASE64_LENGTH + 1)
    res = client.post("/ai/business-license-ocr", json={"image_base64": oversized})
    assert res.status_code == 422


def test_business_license_ocr_requires_image_base64_when_only_file_ref_given():
    """file_ref는 아직 미구현(seam only) — image_base64 없이 file_ref만 오면 검증 실패."""
    res = client.post("/ai/business-license-ocr", json={"file_ref": "s3://bucket/key.png"})
    assert res.status_code == 200
    body = res.json()
    assert body["success"] is False
    assert body["error"]["code"] == "VALIDATION_ERROR"


@patch("routers.ai_router.run_business_license_ocr_chain")
def test_business_license_ocr_endpoint_success(mock_run_chain):
    from ai.chains.business_license_ocr_chain import BusinessLicenseOcrResult

    mock_run_chain.return_value = BusinessLicenseOcrResult(
        business_registration_number="123-45-67890",
        company_name="하자체크 주식회사",
        representative_name="홍길동",
        business_start_date="2020-01-15",
        line_count=4,
        avg_confidence=0.95,
    )

    res = client.post("/ai/business-license-ocr", json={"image_base64": "dGVzdA=="})
    assert res.status_code == 200
    body = res.json()
    assert body["success"] is True

    data = body["data"]
    assert set(data.keys()) == {
        "businessRegistrationNumber",
        "companyName",
        "representativeName",
        "businessStartDate",
        "raw",
        "stub",
    }
    assert data["businessRegistrationNumber"] == "123-45-67890"
    assert data["companyName"] == "하자체크 주식회사"
    assert data["representativeName"] == "홍길동"
    assert data["businessStartDate"] == "2020-01-15"
    assert data["raw"] == {"lineCount": 4, "avgConfidence": 0.95}
    assert data["stub"] is False


@patch("routers.ai_router.run_business_license_ocr_chain")
def test_business_license_ocr_endpoint_chain_failure_returns_error_envelope(mock_run_chain):
    mock_run_chain.side_effect = RuntimeError("OCR 모델 로드 실패")

    res = client.post("/ai/business-license-ocr", json={"image_base64": "dGVzdA=="})
    assert res.status_code == 200
    body = res.json()
    assert body["success"] is False
    assert body["error"]["code"] == "LLM_INVALID_OUTPUT"
    # 원본 예외 메시지가 클라이언트에 그대로 노출되지 않아야 한다(내부 정보 비노출 원칙)
    assert "OCR 모델 로드 실패" not in body["error"]["message"]


# ── 체인 단위 (get_ocr_engine/get_llm 모킹) ─────────────────────────────


def test_decode_image_rejects_invalid_base64():
    try:
        _decode_image("not-valid-base64!!!")
        assert False, "예외가 발생해야 한다"
    except BusinessLicenseOcrError:
        pass


def test_decode_image_rejects_oversized_input_before_decoding():
    """방어심층(defense-in-depth) — 라우터의 Field(max_length=...) 검증을 우회해 이 함수가
    직접 호출되더라도(예: 향후 비-HTTP 호출부) 디코드 전에 길이로 먼저 거부해야 한다."""
    oversized = "a" * (MAX_IMAGE_BASE64_LENGTH + 1)
    try:
        _decode_image(oversized)
        assert False, "예외가 발생해야 한다"
    except BusinessLicenseOcrError as e:
        assert "10MB" in str(e)


def test_normalize_reg_number_handles_dash_and_no_dash():
    assert _normalize_reg_number("123-45-67890") == "123-45-67890"
    assert _normalize_reg_number("1234567890") == "123-45-67890"
    assert _normalize_reg_number(None) is None
    assert _normalize_reg_number("대표자 홍길동") == "대표자 홍길동"  # 매칭 실패 시 원본 그대로


def test_find_business_reg_number_scans_lines():
    lines = ["사업자등록증", "등록번호:123-45-67890", "상호:하자체크"]
    assert _find_business_reg_number(lines) == "123-45-67890"
    assert _find_business_reg_number(["관련 숫자 없음"]) is None


def test_normalize_start_date_handles_various_notations():
    """사업자등록증 개업연월일 표기 다양성(#598) — 년/월/일, 점, 하이픈 구분자 모두 ISO로 정규화."""
    assert _normalize_start_date("2020 년 01 월 15 일") == "2020-01-15"
    assert _normalize_start_date("2020.01.15") == "2020-01-15"
    assert _normalize_start_date("2020-01-15") == "2020-01-15"
    assert _normalize_start_date("2020.1.5") == "2020-01-05"


def test_normalize_start_date_returns_none_on_missing_or_invalid():
    assert _normalize_start_date(None) is None
    assert _normalize_start_date("") is None
    assert _normalize_start_date("확인불가") is None
    # 정규식은 매칭되지만 존재하지 않는 날짜(13월) — datetime.date에서 ValueError로 거부
    assert _normalize_start_date("2020년 13월 01일") is None


@patch("ai.chains.business_license_ocr_chain.get_ocr_engine")
def test_extract_text_lines_returns_empty_when_ocr_finds_nothing(mock_get_engine):
    mock_engine = MagicMock()
    mock_engine.readtext.return_value = []
    mock_get_engine.return_value = mock_engine

    assert _extract_text_lines(b"fake-image-bytes") == []


@patch("ai.chains.business_license_ocr_chain.get_llm")
@patch("ai.chains.business_license_ocr_chain.get_ocr_engine")
def test_run_chain_returns_empty_result_without_llm_call_when_ocr_empty(
    mock_get_engine, mock_get_llm
):
    mock_engine = MagicMock()
    mock_engine.readtext.return_value = []
    mock_get_engine.return_value = mock_engine

    import base64

    result = run_business_license_ocr_chain(base64.b64encode(b"fake").decode())

    assert result.line_count == 0
    assert result.avg_confidence is None
    assert result.business_registration_number is None
    mock_get_llm.assert_not_called()  # OCR 결과 없으면 LLM 호출 자체를 건너뜀(크레딧 절약)


@patch("ai.chains.business_license_ocr_chain.get_llm")
@patch("ai.chains.business_license_ocr_chain.get_ocr_engine")
def test_run_chain_prefers_regex_reg_number_over_llm_guess(mock_get_engine, mock_get_llm):
    """OCR 원문에 정규식으로 잡히는 등록번호가 있으면, LLM이 다른 값을 내놓아도 OCR 기반 값을 우선한다
    (숫자는 결정론적 후처리가 LLM 자유 파싱보다 신뢰도가 높다는 설계 의도 검증)."""
    mock_engine = MagicMock()
    mock_engine.readtext.return_value = [
        [[[0, 0], [1, 0], [1, 1], [0, 1]], "등록번호:123-45-67890", 0.97],
        [[[0, 1], [1, 1], [1, 2], [0, 2]], "상호:하자체크 주식회사", 0.99],
        [[[0, 2], [1, 2], [1, 3], [0, 3]], "대표자: 홍길동", 0.86],
    ]
    mock_get_engine.return_value = mock_engine

    mock_llm = MagicMock()
    mock_llm.with_structured_output.return_value.invoke.return_value = (
        BusinessLicenseOcrExtract(
            business_registration_number="999-99-99999",  # OCR 정규식 결과와 일부러 다르게
            company_name="하자체크 주식회사",
            representative_name="홍길동",
        )
    )
    mock_get_llm.return_value = mock_llm

    import base64

    result = run_business_license_ocr_chain(base64.b64encode(b"fake").decode())

    assert result.business_registration_number == "123-45-67890"  # OCR regex 우선
    assert result.company_name == "하자체크 주식회사"
    assert result.representative_name == "홍길동"
    assert result.line_count == 3
    assert result.avg_confidence == round((0.97 + 0.99 + 0.86) / 3, 4)


@patch("ai.chains.business_license_ocr_chain.get_llm")
@patch("ai.chains.business_license_ocr_chain.get_ocr_engine")
def test_run_chain_falls_back_to_llm_number_when_regex_finds_none(
    mock_get_engine, mock_get_llm
):
    """등록번호 라인이 OCR 오인식으로 깨져 정규식이 못 잡으면 LLM 파싱 결과(정규화 적용)를 쓴다."""
    mock_engine = MagicMock()
    mock_engine.readtext.return_value = [
        [[[0, 0], [1, 0], [1, 1], [0, 1]], "등록번호 확인불가", 0.4]
    ]
    mock_get_engine.return_value = mock_engine

    mock_llm = MagicMock()
    mock_llm.with_structured_output.return_value.invoke.return_value = (
        BusinessLicenseOcrExtract(
            business_registration_number="1234567890",  # 하이픈 없이
            company_name=None,
            representative_name=None,
        )
    )
    mock_get_llm.return_value = mock_llm

    import base64

    result = run_business_license_ocr_chain(base64.b64encode(b"fake").decode())

    assert result.business_registration_number == "123-45-67890"  # 정규화됨
    assert result.company_name is None
    assert result.representative_name is None


@patch("ai.chains.business_license_ocr_chain.get_llm")
@patch("ai.chains.business_license_ocr_chain.get_ocr_engine")
def test_run_chain_normalizes_business_start_date_from_llm(mock_get_engine, mock_get_llm):
    """LLM이 원문 그대로("2020.01.15") 넘긴 개업연월일을 체인이 ISO로 정규화해 반환한다(#598)."""
    mock_engine = MagicMock()
    mock_engine.readtext.return_value = [
        [[[0, 0], [1, 0], [1, 1], [0, 1]], "개업연월일:2020.01.15", 0.9]
    ]
    mock_get_engine.return_value = mock_engine

    mock_llm = MagicMock()
    mock_llm.with_structured_output.return_value.invoke.return_value = BusinessLicenseOcrExtract(
        business_registration_number=None,
        company_name=None,
        representative_name=None,
        business_start_date="2020.01.15",
    )
    mock_get_llm.return_value = mock_llm

    import base64

    result = run_business_license_ocr_chain(base64.b64encode(b"fake").decode())

    assert result.business_start_date == "2020-01-15"


@patch("ai.chains.business_license_ocr_chain.get_llm")
@patch("ai.chains.business_license_ocr_chain.get_ocr_engine")
def test_run_chain_business_start_date_null_when_llm_gives_nothing(mock_get_engine, mock_get_llm):
    """LLM이 개업연월일을 못 찾으면(None) 체인 결과도 None — 허위 값을 만들어내지 않는다."""
    mock_engine = MagicMock()
    mock_engine.readtext.return_value = [
        [[[0, 0], [1, 0], [1, 1], [0, 1]], "사업자등록증", 0.9]
    ]
    mock_get_engine.return_value = mock_engine

    mock_llm = MagicMock()
    mock_llm.with_structured_output.return_value.invoke.return_value = BusinessLicenseOcrExtract(
        business_registration_number=None,
        company_name=None,
        representative_name=None,
        business_start_date=None,
    )
    mock_get_llm.return_value = mock_llm

    import base64

    result = run_business_license_ocr_chain(base64.b64encode(b"fake").decode())

    assert result.business_start_date is None


@patch("ai.chains.business_license_ocr_chain.get_ocr_engine")
def test_run_chain_propagates_ocr_engine_failure(mock_get_engine):
    """OCR 엔진 자체가 실패하면 체인은 예외를 삼키지 않고 그대로 전파한다(라우터가 폴백 처리)."""
    mock_get_engine.side_effect = RuntimeError("모델 다운로드 실패")

    import base64

    try:
        run_business_license_ocr_chain(base64.b64encode(b"fake").decode())
        assert False, "예외가 전파돼야 한다"
    except RuntimeError:
        pass


# ── 파싱 유닛: EasyOCR 응답 형식 모킹 + 실제 이미지 픽스처 경로(디코딩 파싱 확인, 실제 OCR 아님) ──


@patch("ai.chains.business_license_ocr_chain.get_ocr_engine")
def test_extract_text_lines_parses_easyocr_format_with_real_fixture_path(mock_get_engine):
    """`_extract_text_lines`가 실제 이미지 픽스처 파일 경로로 EasyOCR `readtext()` 응답 형식
    (`[(box, text, conf), ...]`, `detail=1, paragraph=False`)을 올바르게 파싱하는지 확인한다.

    코드 리뷰 P3 — 이 테스트는 **실제 OCR 파이프라인을 검증하지 않는다**(`get_ocr_engine`을
    완전히 모킹하므로 실제 EasyOCR 모델은 전혀 호출되지 않는다). 픽스처 이미지는 `_extract_text_lines`가
    bytes를 읽어 반환값을 파싱하는 경로만 실경로로 태우기 위해 재사용할 뿐이다(즉 "스모크
    테스트"가 아니라 파싱 로직 유닛 테스트). 실제 EasyOCR 한국어 인식 정확도는 이 작업(#605)
    배경 조사의 로컬 실측(모델 다운로드 포함, 이 테스트 스위트 밖에서 별도 확인)으로 검증됨 —
    EasyOCR는 RapidOCR과 달리 pip 패키지에 오프라인 번들 모델이 없어 CI에서 매번 실제 파이프라인을
    돌리려면 네트워크 다운로드가 필요하므로(AI_개발_컨벤션 "HF 모델 관련 테스트는 항상 모킹"
    원칙과 상충) 여기서는 모킹으로 대체한다.
    """
    mock_engine = MagicMock()
    mock_engine.readtext.return_value = [
        [[[0, 0], [1, 0], [1, 1], [0, 1]], "등록번호:123-45-67890", 0.97],
    ]
    mock_get_engine.return_value = mock_engine

    image_bytes = (FIXTURES_DIR / "business_license_sample.png").read_bytes()
    lines = _extract_text_lines(image_bytes)

    assert len(lines) > 0
    joined = " ".join(text for text, _score in lines)
    assert _find_business_reg_number([joined]) == "123-45-67890"
