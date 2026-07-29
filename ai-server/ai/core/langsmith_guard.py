"""LangSmith 마스킹 부팅 가드 + 예외(error) 필드 스크럽 (#1240 P1/P2 후속).

#1240은 체인별 `tracing_context(enabled=False)`를 제거하고 전역 입출력 마스킹
(`LANGSMITH_HIDE_INPUTS/HIDE_OUTPUTS`)으로 전환했다. 그 전환이 남긴 구멍 두 개를
앱 기동 시점에 코드로 막는다.

1. **예외 경로 유출(P1)** — HIDE_INPUTS/HIDE_OUTPUTS는 run의 `inputs`/`outputs`만 가린다.
   LLM 호출이 예외를 던지면(예: structured output 파싱 실패 시 원문이 예외 메시지에 포함)
   run의 `error` 필드에 예외 문자열+트레이스백이 실려 **마스킹이 켜져 있어도** 평문 전송된다
   (langsmith 0.10.10 실측). `error`를 가리는 유일한 경로는 `Client._hide_run_error`가 쓰는
   `anonymizer`인데, 이는 Callable이라 env로 설정할 수 없고 코드로만 주입 가능하다.
   `langsmith.run_trees._CLIENT`가 전역 싱글턴이므로, 첫 트레이스가 만들어지기 전에
   `get_cached_client(anonymizer=...)`로 선점 생성해야 한다 — 이 모듈이 그 일을 한다.

2. **env 누락 시 fail-open(P2)** — 기존 체인별 차단은 env와 무관한 fail-safe였지만, 전환 후엔
   마스킹 env가 빠진 환경(compose 미사용 네이티브 실행 등)에서 트레이싱만 켜지면 OCR 원문·
   고객 개인정보가 그대로 나간다. 그래서 "트레이싱 ON + 마스킹 불완전" 조합이면 **기동 자체를
   중단**한다(RuntimeError). 조용히 강제 활성화하지 않는 이유: 운영자가 의도적으로 끈 것인지
   실수인지 코드가 구분할 수 없고, 이 레포는 fail-closed가 관례다(main.py의 /docs 가드와 동일).

호출 시점: `main.py`의 `load_dotenv()` 직후, 라우터 임포트 전. 워커 프로세스마다 모듈 로드
시 1회 실행되면 충분하다(마스킹 설정은 프로세스 기동 시점에 싱글턴에 고정된다 —
test_langsmith_masking.py 픽스처 docstring 참고).
"""
from __future__ import annotations

import os
import re

# 트레이싱 활성 판정에 쓰는 env 이름들 — langsmith 0.10.x가 인식하는 두 이름 모두 본다.
_TRACING_ENV_KEYS = ("LANGCHAIN_TRACING_V2", "LANGSMITH_TRACING")
_HIDE_ENV_KEYS = ("LANGSMITH_HIDE_INPUTS", "LANGSMITH_HIDE_OUTPUTS")

# 예외 문자열 선두의 예외 타입명(예: "OutputParserException(...)" -> "OutputParserException").
# 실패 "종류"는 추적 목적상 남기고, 원문이 실릴 수 있는 메시지·트레이스백만 버린다.
_EXCEPTION_TYPE_RE = re.compile(r"[A-Za-z_][A-Za-z0-9_.]*")


def scrub_error_anonymizer(data: dict) -> dict:
    """LangSmith Client의 anonymizer 훅 — run의 error 필드에서 내용(메시지·트레이스백)을 제거한다.

    `Client._hide_run_error`는 error 문자열을 `{"error": ...}`로 감싸 anonymizer에 넘기고
    반환 dict의 "error" 키를 다시 꺼낸다(langsmith 0.10.10). 예외 타입명만 남기고 나머지를
    치환해 "무엇이 실패했는지"는 추적 가능하되 원문은 전송되지 않게 한다.

    inputs/outputs 경로: HIDE env가 true면 anonymizer보다 먼저 `{}`로 대체되므로 이 함수는
    호출되지 않는다. 혹시 HIDE가 꺼진 채 이 anonymizer만 살아있는 비정상 조합이 되면 전부
    `{}`를 돌려줘 내용을 비전송한다 — env 마스킹과 같은 방향의 이중 안전장치.
    """
    if set(data.keys()) == {"error"}:
        error = data["error"]
        match = _EXCEPTION_TYPE_RE.match(str(error) if error is not None else "")
        exception_type = match.group(0) if match else "Exception"
        return {"error": f"{exception_type} [메시지·트레이스백 마스킹 — #1240]"}
    return {}


def enforce_masked_tracing() -> None:
    """트레이싱이 켜져 있으면 마스킹 완전성을 강제하고 error 스크럽을 싱글턴에 선점 설치한다.

    - 트레이싱 OFF(기본): 아무것도 하지 않는다 — 전송 자체가 없어 유출 표면이 없고,
      불필요한 Client 생성(API 키 경고 등)도 피한다.
    - 트레이싱 ON + HIDE 둘 중 하나라도 true가 아님: RuntimeError로 기동 중단.
    - 트레이싱 ON + HIDE 둘 다 true: `get_cached_client(anonymizer=...)`로 전역 싱글턴을
      선점 생성해 이후 모든 트레이스의 error 필드가 스크럽되게 한다.
    """
    tracing_on = any(os.getenv(k, "").strip().lower() == "true" for k in _TRACING_ENV_KEYS)
    if not tracing_on:
        return

    missing = [k for k in _HIDE_ENV_KEYS if os.getenv(k, "").strip().lower() != "true"]
    if missing:
        raise RuntimeError(
            "LangSmith 트레이싱이 켜져 있는데 입출력 마스킹이 불완전합니다 — "
            f"{', '.join(missing)}=true 를 설정하거나 트레이싱을 끄세요. "
            "(마스킹 없이 트레이싱하면 OCR 원문·고객 개인정보가 외부 LangSmith로 전송됩니다, #1240)"
        )

    from langsmith.run_trees import get_cached_client

    # 첫 트레이스 전에 싱글턴을 선점해야 anonymizer가 실린다 — 이미 생성돼 있으면(정상 부팅
    # 순서에선 불가능) 기존 인스턴스가 반환되며 kwargs는 무시된다.
    get_cached_client(anonymizer=scrub_error_anonymizer)
