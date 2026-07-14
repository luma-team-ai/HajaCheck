"""business-license-ocr stub 엔드포인트 검증 (HAJA-169).

실제 OCR 미구현 — 계약된 고정 stub 응답(4키+stub=true, 3필드 null)만 반환하는지 확인.
"""
from fastapi.testclient import TestClient

from main import app

client = TestClient(app)


def test_business_license_ocr_returns_stub_envelope_with_image_base64():
    res = client.post(
        "/ai/business-license-ocr",
        json={"image_base64": "dGVzdA=="},
    )
    assert res.status_code == 200
    body = res.json()
    assert body["success"] is True

    data = body["data"]
    assert set(data.keys()) == {
        "businessRegistrationNumber",
        "companyName",
        "representativeName",
        "raw",
        "stub",
    }
    assert data["businessRegistrationNumber"] is None
    assert data["companyName"] is None
    assert data["representativeName"] is None
    assert data["raw"] == {}
    assert data["stub"] is True


def test_business_license_ocr_returns_stub_envelope_with_file_ref():
    res = client.post(
        "/ai/business-license-ocr",
        json={"file_ref": "s3://bucket/key.png"},
    )
    assert res.status_code == 200
    body = res.json()
    assert body["success"] is True
    assert body["data"]["stub"] is True


def test_business_license_ocr_returns_stub_envelope_with_empty_body():
    res = client.post("/ai/business-license-ocr", json={})
    assert res.status_code == 200
    body = res.json()
    assert body["success"] is True
    assert body["data"]["stub"] is True


if __name__ == "__main__":
    test_business_license_ocr_returns_stub_envelope_with_image_base64()
    test_business_license_ocr_returns_stub_envelope_with_file_ref()
    test_business_license_ocr_returns_stub_envelope_with_empty_body()
    print("OK: business_license_ocr stub self-check passed")
