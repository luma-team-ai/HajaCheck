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

## 판정은 자체 파싱하지 않고 프레임워크 함수에 위임한다 (2차 리뷰 P1 후속)

env를 직접 파싱하면 프레임워크의 실제 판정과 어긋나는 순간 가드가 헛돈다. 실측
(langsmith 0.10.10 + langchain-core 0.3.86)으로 확정한 실제 판정:

- **트레이싱 ON 판정** = `langsmith.utils.tracing_is_enabled()` — 값은 소문자 `"true"`만
  인정하고(`"1"`·`"yes"`·`"TRUE"`는 전부 OFF), env 키는 네임스페이스 해석으로 **4개**
  (`{LANGSMITH,LANGCHAIN}_TRACING_V2`, `{LANGSMITH,LANGCHAIN}_TRACING`)를 본다.
  초기 구현은 키 2개를 자체 목록으로 봤다가 `LANGSMITH_TRACING_V2` 등으로 켜면 가드가
  통째로 우회되는 갭이 있었다 — 위임으로 값·키·우선순위가 구조적으로 항상 일치한다.
- **마스킹 ON 판정** = `Client`와 동일한 `get_env_var("HIDE_*") == "true"` (대소문자 민감,
  네임스페이스 포함). 초기 구현은 대소문자를 무시해 `HIDE_INPUTS=TRUE`면 가드는 통과하는데
  Client는 마스킹을 끄는(실측 `_hide_inputs=False`) 정반대 fail-open이 있었다.

두 판정 모두 `get_env_var`의 LRU 캐시를 타므로 **프로세스 기동 시점 env로 고정**된다 —
돌고 있는 프로세스에서 env를 바꿔도 효과가 없다(마스킹 설정 자체가 원래 그렇다,
test_langsmith_masking.py 픽스처 docstring 참고).

호출 시점: `main.py`의 `load_dotenv()` 직후, 라우터 임포트 전. 워커 프로세스마다 모듈 로드
시 1회 실행되면 충분하다.
"""
from __future__ import annotations

import re

from langsmith import utils as ls_utils

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


def _hide_env_missing() -> list[str]:
    """Client가 마스킹으로 인식하지 못할 HIDE 설정을 (대표 env 이름으로) 나열한다.

    Client의 판정 그대로 `get_env_var("HIDE_*") == "true"` — 대소문자·네임스페이스까지
    동일해야 "가드는 통과했는데 Client는 마스킹 안 하는" 어긋남이 원천 차단된다.
    """
    missing = []
    if ls_utils.get_env_var("HIDE_INPUTS", default=None) != "true":
        missing.append("LANGSMITH_HIDE_INPUTS")
    if ls_utils.get_env_var("HIDE_OUTPUTS", default=None) != "true":
        missing.append("LANGSMITH_HIDE_OUTPUTS")
    return missing


def enforce_masked_tracing() -> None:
    """트레이싱이 켜져 있으면 마스킹 완전성을 강제하고 error 스크럽을 싱글턴에 선점 설치한다.

    - 트레이싱 OFF(기본): 아무것도 하지 않는다 — 전송 자체가 없어 유출 표면이 없고,
      불필요한 Client 생성(API 키 경고 등)도 피한다.
    - 트레이싱 ON + HIDE 둘 중 하나라도 정확히 "true"가 아님: RuntimeError로 기동 중단.
    - 트레이싱 ON + HIDE 둘 다 "true": `get_cached_client(anonymizer=...)`로 전역 싱글턴을
      선점 생성해 이후 모든 트레이스의 error 필드가 스크럽되게 한다.
    """
    # 부팅 시점엔 활성 run tree·컨텍스트 오버라이드가 없으므로 순수 env 판정이다.
    # 반환값 "local"(코드로만 설정 가능)도 truthy로 취급 — 마스킹을 강제하는 안전한 방향.
    if not ls_utils.tracing_is_enabled():
        return

    missing = _hide_env_missing()
    if missing:
        raise RuntimeError(
            "LangSmith 트레이싱이 켜져 있는데 입출력 마스킹이 불완전합니다 — "
            f"{', '.join(missing)} 를 정확히 소문자 \"true\"로 설정하거나 트레이싱을 끄세요"
            "(Client는 소문자 \"true\" 외의 값을 전부 마스킹 OFF로 해석합니다). "
            "마스킹 없이 트레이싱하면 OCR 원문·고객 개인정보가 외부 LangSmith로 전송됩니다(#1240)."
        )

    from langsmith.run_trees import get_cached_client

    # 첫 트레이스 전에 싱글턴을 선점해야 anonymizer가 실린다 — 이미 생성돼 있으면(정상 부팅
    # 순서에선 불가능) 기존 인스턴스가 반환되며 kwargs는 무시된다.
    get_cached_client(anonymizer=scrub_error_anonymizer)
