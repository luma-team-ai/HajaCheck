"""defect_explain 체인/엔드포인트 최소 동작 검증 (실제 HF/Redis 호출 없이 get_llm만 모킹).

- 프롬프트 조립(_build_prompt)이 실제 prompts/*.md 파일을 읽어 정상 조립되는지
- /ai/defect-explain 이 structured output 결과를 AIResponse envelope으로 감싸는지
- LLM 예외 시 서버가 죽지 않고 AIResponse.fail 로 응답하는지
"""
from unittest.mock import MagicMock, patch

from fastapi.testclient import TestClient

from ai.chains.defect_explain_chain import DefectExplain, _build_prompt
from main import app

client = TestClient(app)


def test_build_prompt_includes_inputs():
    prompt = _build_prompt("균열", "C", "1층 기둥", "공동주택")
    assert "균열" in prompt
    assert "1층 기둥" in prompt


@patch("ai.chains.defect_explain_chain.get_llm")
def test_defect_explain_endpoint_success(mock_get_llm):
    mock_llm = MagicMock()
    mock_llm.with_structured_output.return_value.invoke.return_value = DefectExplain(
        cause="철근 부식", risk="구조 내력 저하", action="단면 보수 후 재도장"
    )
    mock_get_llm.return_value = mock_llm

    res = client.post(
        "/ai/defect-explain",
        json={
            "defect_type": "균열",
            "severity_grade": "C",
            "location": "1층 기둥",
            "facility_type": "공동주택",
        },
    )
    assert res.status_code == 200
    body = res.json()
    assert body["success"] is True
    assert body["data"]["cause"] == "철근 부식"


@patch("ai.chains.defect_explain_chain.get_llm")
def test_defect_explain_endpoint_llm_failure_returns_error_envelope(mock_get_llm):
    mock_get_llm.side_effect = KeyError("HF_API_TOKEN")

    res = client.post(
        "/ai/defect-explain",
        json={
            "defect_type": "균열",
            "severity_grade": "C",
            "location": "1층 기둥",
            "facility_type": "공동주택",
        },
    )
    assert res.status_code == 200
    body = res.json()
    assert body["success"] is False
    assert body["error"]["code"] == "LLM_INVALID_OUTPUT"


if __name__ == "__main__":
    test_build_prompt_includes_inputs()
    test_defect_explain_endpoint_success()
    test_defect_explain_endpoint_llm_failure_returns_error_envelope()
    print("OK: defect_explain chain/endpoint self-check passed")
