"""사업자등록증 OCR 체인 (HAJA-169/#552, 개업일자 추출 #598, RapidOCR→EasyOCR 교체 #605)
— AI_개발_컨벤션.md §8 절차를 따름.

EasyOCR(`ai/core/ocr_client.py`, 한국어 모델)로 이미지에서 텍스트 라인을 추출한 뒤,
LLM structured output으로 사업자등록번호/상호/대표자명/개업연월일 4필드를 파싱한다.
EasyOCR도 완벽하진 않아 유사 글자 오탈자(예: "다"↔"타", "0"↔"O")가 남을 수 있는데, 이는
LLM 프롬프트(`ai/prompts/business_license_ocr.md`)의 문맥 기반 오탈자 교정 지시로 보정한다
(단, 없는 값을 지어내진 않는다 — 인식 안 되면 null).

사업자등록번호는 OCR 원문에서 정규식으로도 탐지해 LLM이 놓치거나 하이픈 표기를 다르게 한
경우를 보정한다 — 숫자는 OCR 자체 신뢰도가 높고 포맷이 고정(3-2-5자리)이라 결정론적 정규식
후처리가 LLM 자유 파싱보다 안전하다(AI_개발_컨벤션.md §4 "자유 텍스트 직접 파싱 금지"는 LLM
*응답*을 정규식/split으로 파싱하는 것을 금지하는 규칙 — 여기서는 LLM 응답이 아니라 OCR 원문에
대한 후처리 보정이라 해당하지 않는다. LLM은 여전히 structured output(BusinessLicenseOcrExtract)
으로만 응답을 준다).

개업연월일(business_start_date, #598)도 같은 이유로 후처리 정규화를 거친다 — 등록증 표기가
"2020 년 01 월 15 일"/"2020.01.15"/"2020-01-15" 등 제각각이라, LLM이 준 원문을 정규식으로
연/월/일을 분리한 뒤 실제 존재하는 날짜인지까지 검증해 ISO YYYY-MM-DD로 정규화한다(`_normalize_start_date`).
국세청 진위확인(#596)이 이 필드를 요구해 FE가 별도 입력 없이 자동채움할 수 있도록 한다.

개인정보 보호(#552 요구사항): OCR 원문(대표자명·주소 등 개인정보 포함)은 로그에 평문으로
남기지 않는다 — 이 파일은 어떤 로그도 남기지 않는다(체인 자체는 로거를 두지 않음). 예외는
삼키지 않고 그대로 호출부(라우터)로 전파하며, 라우터의 `logger.exception`에는 예외 타입/스택만
남고 OCR 원문·LLM 응답 내용은 포함하지 않는다(`routers/ai_router.py` 참고).
"""
import base64
import binascii
import re
from datetime import date
from pathlib import Path
from typing import Optional

from pydantic import BaseModel

from ai.core.llm_client import get_llm
from ai.core.ocr_client import get_ocr_engine
from ai.core.prompt_safety import wrap_untrusted

PROMPTS_DIR = Path(__file__).resolve().parent.parent / "prompts"

# 사업자등록번호 포맷: 3-2-5자리 숫자(하이픈 유무 무관) — 국세청 표준 포맷
BUSINESS_REG_NUMBER_PATTERN = re.compile(r"(\d{3})-?(\d{2})-?(\d{5})")

# 개업연월일 포맷(#598): 연-월-일 구분자가 "년/월/일"·"."·"-" 중 하나(공백 유무 무관)인 표기를
# 폭넓게 허용한다 — 사업자등록증 표기가 "2020 년 01 월 15 일"/"2020.01.15"/"2020-01-15" 등
# 발급 시기·양식에 따라 제각각이라 단일 구분자만 가정하면 놓친다.
BUSINESS_START_DATE_PATTERN = re.compile(
    r"(\d{4})\s*[-./년]\s*(\d{1,2})\s*[-./월]\s*(\d{1,2})\s*일?"
)

# FE 사업자등록증 업로드 상한 10MB(frontend/src/features/auth/constants.ts
# BUSINESS_LICENSE_MAX_SIZE_BYTES)의 base64 인코딩 상당치 — base64는 원본 대비 약 +33% 커지므로
# 10MB ≈ 13.4M자. 여유를 두고 14,000,000자로 설정(코드 리뷰 P2 — 상한 없이 base64 디코드/PIL
# open을 태우면 대형 페이로드로 CPU/메모리를 고갈시킬 수 있음, DoS 방지).
MAX_IMAGE_BASE64_LENGTH = 14_000_000

# structured 응답 Redis 캐시 TTL(#623) — 대표자명 등 개인정보가 섞인 OCR 응답이라, 다른 체인의
# 기본 24h(llm_client.CACHE_TTL_SECONDS)보다 짧게 둬 공유 Redis(OCI dev/arm1 prod) 잔존 기간을 줄인다.
OCR_CACHE_TTL_SECONDS = 60 * 60  # 1시간


class BusinessLicenseOcrExtract(BaseModel):
    """LLM 응답은 structured output 으로만 수신 — 자유 텍스트 파싱 금지 (AI_개발_컨벤션.md §4)"""

    business_registration_number: Optional[str] = None
    company_name: Optional[str] = None
    representative_name: Optional[str] = None
    # 개업연월일(#598) — 등록증 표기 그대로("2020.01.15" 등) 반환받아 아래에서 정규화한다.
    business_start_date: Optional[str] = None


class BusinessLicenseOcrResult(BaseModel):
    """체인의 최종 반환값 — regex 보정이 반영된 사업자등록번호 + OCR 품질 메타(라우터의 `raw`에 사용)."""

    business_registration_number: Optional[str] = None
    company_name: Optional[str] = None
    representative_name: Optional[str] = None
    business_start_date: Optional[str] = None
    line_count: int = 0
    avg_confidence: Optional[float] = None


class BusinessLicenseOcrError(Exception):
    """OCR/체인 처리 실패 — 원인 메시지에 OCR 원문이 담기지 않도록 고정 문구만 사용."""


def _decode_image(image_base64: str) -> bytes:
    # 방어심층(defense-in-depth) — 정상 HTTP 경로는 BusinessLicenseOcrRequest.image_base64의
    # Field(max_length=...)에서 이미 걸러지지만, 이 함수가 라우터 검증을 거치지 않고 직접 호출될
    # 가능성(다른 체인·백그라운드 잡 등 향후 호출부)에 대비해 디코드 이전에 문자열 길이로 먼저
    # 컷한다 — base64.b64decode/PIL.Image.open에 대형 페이로드를 태우지 않는다(코드 리뷰 P2).
    if len(image_base64) > MAX_IMAGE_BASE64_LENGTH:
        raise BusinessLicenseOcrError(
            "이미지 크기가 허용 상한(10MB)을 초과했습니다"
        )
    try:
        return base64.b64decode(image_base64, validate=True)
    except (binascii.Error, ValueError) as e:
        raise BusinessLicenseOcrError("image_base64가 올바른 base64 인코딩이 아닙니다") from e


def _extract_text_lines(image_bytes: bytes) -> list[tuple[str, float]]:
    """EasyOCR로 (텍스트, 신뢰도) 라인 목록을 추출한다. 텍스트를 못 찾으면 빈 리스트.

    EasyOCR `readtext()`는 파일 경로/numpy 배열/bytes를 모두 받아들이므로(내부
    `reformat_input()`이 bytes를 cv2.imdecode로 직접 디코딩) 별도 PIL/np 변환 없이
    원본 bytes를 그대로 넘긴다. `detail=1, paragraph=False`로 RapidOCR과 동일한
    `(box, text, confidence)` 튜플 목록을 받아 하위 로직(정규식 후처리 등)을 그대로 유지한다.
    """
    engine = get_ocr_engine()
    result = engine.readtext(image_bytes, detail=1, paragraph=False)
    if not result:
        return []
    return [(text, float(score)) for _box, text, score in result]


def _normalize_reg_number(candidate: Optional[str]) -> Optional[str]:
    if not candidate:
        return None
    m = BUSINESS_REG_NUMBER_PATTERN.search(candidate)
    if m:
        return f"{m.group(1)}-{m.group(2)}-{m.group(3)}"
    return candidate


def _find_business_reg_number(lines: list[str]) -> Optional[str]:
    """OCR 텍스트 라인 중 사업자등록번호 포맷과 일치하는 첫 라인을 표준 포맷(000-00-00000)으로
    정규화해 반환한다. 사업자등록증 특성상 등록번호는 한 줄에 표기되므로 라인 단위로만 탐색한다."""
    for line in lines:
        m = BUSINESS_REG_NUMBER_PATTERN.search(line)
        if m:
            return f"{m.group(1)}-{m.group(2)}-{m.group(3)}"
    return None


def _normalize_start_date(candidate: Optional[str]) -> Optional[str]:
    """LLM이 추출한 개업연월일 원문을 ISO YYYY-MM-DD로 정규화한다(#598).

    등록증 표기가 "2020 년 01 월 15 일"/"2020.01.15"/"2020-01-15" 등 제각각이라 정규식으로
    연/월/일을 분리한 뒤, `datetime.date`로 실제 존재하는 날짜인지까지 검증한다. 포맷이
    맞지 않거나(라벨은 찾았지만 값이 깨짐) 존재하지 않는 날짜(예: 13월)면 None으로 폴백해
    FE가 수동 입력으로 전환할 수 있게 한다(허위 값을 만들어내지 않음).
    """
    if not candidate:
        return None
    m = BUSINESS_START_DATE_PATTERN.search(candidate)
    if not m:
        return None
    year, month, day = (int(group) for group in m.groups())
    try:
        return date(year, month, day).isoformat()
    except ValueError:
        return None


def _build_prompt(ocr_text_block: str) -> str:
    system = (PROMPTS_DIR / "_system_base.md").read_text(encoding="utf-8")
    template = (PROMPTS_DIR / "business_license_ocr.md").read_text(encoding="utf-8")
    filled = template.format(ocr_text_block=wrap_untrusted(ocr_text_block))
    return f"{system}\n\n{filled}"


def run_business_license_ocr_chain(image_base64: str) -> BusinessLicenseOcrResult:
    """사업자등록증 이미지(base64) -> OCR -> LLM structured output -> 3필드 결과.

    OCR이 텍스트를 하나도 못 찾으면 LLM을 호출하지 않고 빈 결과를 반환한다(크레딧 절약 —
    LLM에 빈 컨텍스트를 줘봐야 null만 나온다).
    """
    image_bytes = _decode_image(image_base64)
    lines_with_scores = _extract_text_lines(image_bytes)

    if not lines_with_scores:
        return BusinessLicenseOcrResult(line_count=0, avg_confidence=None)

    texts = [text for text, _score in lines_with_scores]
    avg_confidence = sum(score for _text, score in lines_with_scores) / len(lines_with_scores)

    prompt = _build_prompt("\n".join(texts))
    llm_result = (
        get_llm()
        .with_structured_output(BusinessLicenseOcrExtract, ttl=OCR_CACHE_TTL_SECONDS)
        .invoke(prompt)
    )

    business_registration_number = _find_business_reg_number(
        texts
    ) or _normalize_reg_number(llm_result.business_registration_number)

    return BusinessLicenseOcrResult(
        business_registration_number=business_registration_number,
        company_name=llm_result.company_name,
        representative_name=llm_result.representative_name,
        business_start_date=_normalize_start_date(llm_result.business_start_date),
        line_count=len(texts),
        avg_confidence=round(avg_confidence, 4),
    )
