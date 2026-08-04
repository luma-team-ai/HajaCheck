"""LangSmith 정규식 PII 스크럽 anonymizer (#1585).

과거 #1240/#1248은 `LANGSMITH_HIDE_INPUTS`/`LANGSMITH_HIDE_OUTPUTS` env로 트레이스
입출력을 전면 마스킹(`{}` 반환)했고, 그 뒤 #1534/PR #1545에서 그 전면 마스킹은 의도적으로
폐기됐다(가시성 상실 — 팀이 실제 프롬프트·응답을 보고 디버깅해야 한다는 요구). 이 모듈은
그 대체재다: **원문 가시성은 유지하되**, 정규식으로 식별 가능한 PII 패턴(주민등록번호·
사업자등록번호·전화번호·이메일·카드번호)만 값 안에서 부분 치환한다. 구조(dict 키·list
순서)는 항상 보존되고, 매칭되지 않는 일반 텍스트는 원문 그대로 남는다.

## 설치 지점 — 전 필드 공통 (inputs/outputs/error)

`langsmith.client.Client`는 `_hide_run_inputs`/`_hide_run_outputs`/`_hide_run_error` 세
경로 모두에서 (HIDE_* 가 명시적으로 True가 아닌 한) 동일한 `self._anonymizer(dict)`를
호출한다(langsmith 0.10.10 실측, `client.py` 참고). 특히 `_hide_run_error`는 error
문자열을 `{"error": ...}`로 감싸 anonymizer에 넘기고 반환 dict의 `"error"` 키를 다시
꺼낸다 — structured output 파싱 실패 시 예외 메시지에 프롬프트 원문이 실려 나간 전례가
있다(#1240 P1). 이 함수는 dict를 재귀적으로 순회하는 범용 구현이라 별도 분기 없이 세
경로 모두에서 동일하게 동작한다.

## 설치는 게이트 없이 무조건 — 싱글턴 선점 함정(#1248) 그대로 유효

`langsmith.run_trees.get_cached_client(anonymizer=...)`는 싱글턴이 이미 존재하면 전달한
kwargs를 조용히 무시하고 기존 인스턴스를 그대로 반환한다(langsmith 자체 동작). 정상 부팅
순서(main.py가 가장 먼저 호출)에서는 발생하지 않지만, 보조 진입점이 langsmith를 먼저
건드리면 anonymizer 없는 싱글턴이 만들어질 수 있다 — `install_pii_scrub_anonymizer()`가
반환된 client의 `_anonymizer`를 직접 확인하고 명시적으로 재할당하는 이유다.
"""
from __future__ import annotations

import logging
import re
from typing import Any

logger = logging.getLogger(__name__)

# 각 (라벨, 패턴) — 순서가 중요하다. 앞선 패턴이 치환한 구간은 뒤 패턴이 다시 볼 필요가
# 없어지고(치환 토큰 `[REDACTED:...]`에는 숫자가 없어 재매칭 위험도 없다), 특히 이메일은
# 로컬파트에 포함된 숫자열이 카드번호/전화번호 패턴에 선점되지 않도록 반드시 가장 먼저
# 처리한다. `\b` 경계는 순수 숫자 런 내부에서는 성립하지 않으므로(숫자·하이픈 모두 이 지점
# 에선 "단어"의 시작/끝으로만 걸린다), 더 긴 숫자열 중간의 우연한 부분열을 오탐하지 않는다.
_EMAIL_RE = re.compile(r"\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b")
_RRN_RE = re.compile(r"\b\d{6}-?[1-4]\d{6}\b")  # 주민등록번호: YYMMDD-[성별1~4]######
_CARD_RE = re.compile(r"\b(?:\d{4}[- ]?){3}\d{4}\b")  # 카드번호(16자리 계열)
_BIZ_REG_RE = re.compile(r"\b\d{3}-?\d{2}-?\d{5}\b")  # 사업자등록번호: ###-##-#####
_PHONE_RE = re.compile(r"\b0\d{1,2}-?\d{3,4}-?\d{4}\b")  # 휴대폰/일반 전화번호(0으로 시작)

_PATTERNS: tuple[tuple[str, re.Pattern[str]], ...] = (
    ("이메일", _EMAIL_RE),
    ("주민등록번호", _RRN_RE),
    ("카드번호", _CARD_RE),
    ("사업자등록번호", _BIZ_REG_RE),
    ("전화번호", _PHONE_RE),
)


def _scrub_string(text: str) -> str:
    for label, pattern in _PATTERNS:
        text = pattern.sub(f"[REDACTED:{label}]", text)
    return text


def _scrub_value(value: Any) -> Any:
    """dict/list/str을 재귀적으로 순회하며 str 리프에만 정규식 치환을 적용한다.

    dict 키·list 순서·비-str 값(int/bool/None 등)은 그대로 보존한다 — 구조를 바꾸는
    순간 LangSmith 트레이스 뷰의 나머지 필드(토큰 수·타이밍 등 문자열이 아닌 메타데이터)
    까지 깨질 수 있다.
    """
    if isinstance(value, str):
        return _scrub_string(value)
    if isinstance(value, dict):
        return {k: _scrub_value(v) for k, v in value.items()}
    if isinstance(value, list):
        return [_scrub_value(v) for v in value]
    if isinstance(value, tuple):
        return tuple(_scrub_value(v) for v in value)
    return value


def pii_scrub_anonymizer(data: dict) -> dict:
    """LangSmith `Client(anonymizer=...)` 훅.

    `inputs`/`outputs`/`{"error": ...}` dict를 받아 **같은 구조의 dict**를 반환한다 —
    키를 지우거나 `{}`로 뭉개지 않는다(전면 마스킹 재도입 금지, #1534/PR #1545 참고).
    값 문자열 안의 PII 패턴만 `[REDACTED:종류]` 토큰으로 부분 치환되고, 나머지 원문
    (프롬프트·응답·시설명·하자 설명 등)은 그대로 남는다.
    """
    if not isinstance(data, dict):
        # langsmith Client 내부 경로는 항상 dict를 넘기지만(inputs/outputs는 json
        # 직렬화된 dict, error는 {"error": ...}로 래핑됨), 계약을 벗어난 호출에도
        # 예외를 던지지 않고 원본을 그대로 돌려준다(fail-safe — 스크럽이 실패해도
        # 트레이싱 자체가 죽으면 안 된다는 원칙은 유지하되, 필드를 지우지는 않는다).
        return data
    return {key: _scrub_value(val) for key, val in data.items()}


def install_pii_scrub_anonymizer() -> None:
    """LangSmith 클라이언트 싱글턴을 선점해 `pii_scrub_anonymizer`를 설치한다.

    조건 없이 항상 호출한다 — HIDE_* env 유무·트레이싱 on/off와 무관하게 설치해야
    "나중에 어떤 경로로든 트레이스가 나가면 최소한 PII는 걸러진다"는 안전망이 된다
    (게이트를 두면 조건이 갈라지는 조합이 반드시 생긴다는 것이 과거 #1240 구현의
    교훈이었다 — `git show f41533ef:ai-server/ai/core/langsmith_guard.py` 참고).

    **어떤 실패도 호출자에게 전파하지 않는다(fail-safe).** 이 함수는 main.py 모듈
    최상위에서 호출되므로, 예외가 새어 나가면 `import main` 자체가 실패해 ai-server가
    아예 뜨지 못한다. 내부 구현이 langsmith private API(`get_cached_client` kwargs 처리·
    `client._anonymizer` 속성)에 의존하는 만큼 라이브러리 업그레이드로 깨질 수 있고,
    그때 서비스 전체가 죽는 편익은 없다 — `pii_scrub_anonymizer()`가 이미 채택한
    "스크럽 실패가 트레이싱/서비스를 죽이면 안 된다"는 원칙을 설치 단계에도 적용한다.
    단, 조용히 넘어가면 트레이싱이 켜진 채 스크럽만 빠진 상태를 눈치채지 못하므로
    `logger.exception`으로 스택과 함께 크게 남긴다.
    """
    try:
        from langsmith.run_trees import get_cached_client

        client = get_cached_client(anonymizer=pii_scrub_anonymizer)
        if client._anonymizer is not pii_scrub_anonymizer:
            # 싱글턴이 이미 존재했다면(정상 부팅 순서에선 불가능하지만 보조 진입점에서는
            # 가능) 위 호출의 kwargs가 조용히 무시된다 — 그 경우를 명시적으로 감지해
            # 강제 재할당한다(#1248에서 검증된 함정).
            client._anonymizer = pii_scrub_anonymizer
    except Exception:
        logger.exception(
            "LangSmith PII 스크럽 anonymizer 설치 실패 — 서비스 기동은 계속한다. "
            "이 상태에서 트레이싱이 켜져 있으면 PII가 스크럽되지 않은 채 전송될 수 있으니 "
            "즉시 확인이 필요하다(langsmith 버전 업그레이드로 private API가 바뀐 경우 등)."
        )
