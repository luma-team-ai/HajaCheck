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

## error 스크럽 설치는 부팅 시점 tracing 여부와 분리한다 (4차 리뷰 P3 후속)

기존 구현은 "부팅 시 트레이싱이 켜져 있을 때만" anonymizer를 선점 설치했다. 그런데 표준
프로덕션 상태(docker-compose 기본값)는 `HIDE_*=true`이지만 `LANGCHAIN_TRACING_V2`는
대개 미설정이다 — 즉 "부팅 시 트레이싱 OFF"가 흔한 정상 상태다. 이 PR 이전까지 모든 체인이
요청 단위로 `tracing_context(enabled=...)`를 직접 감싸던 패턴이었으므로, 누군가 나중에
그 패턴을 되살리면(현실적인 회귀) 부팅 시점엔 anonymizer가 없던 채로 예외가 나 원문이
새어나간다 — 이 PR이 막으려던 P1이 그대로 재발한다.

그래서 anonymizer 선점은 **트레이스가 나갈 수 있는 조건(API 키 존재)** 하나로만 판단한다.
API 키가 있으면 트레이싱이 부팅 시 꺼져 있어도 싱글턴을 미리 만들어 anonymizer를 실어둔다 —
이러면 그 순간 `HIDE_*` env 값이 `Client._hide_inputs/_hide_outputs`에 함께 고정되므로(둘 다
Client 생성 시점 값), 이후 언제 트레이싱이 켜지든 입출력 마스킹도 그대로 유지된다. 부팅
시점에 트레이싱이 실제로 켜져 있을 때만 적용되는 RuntimeError(fail-closed)는 그대로 둔다 —
이건 "지금 이 순간 새는지"를 막는 것이고, anonymizer 선점은 "나중에 새는 것까지" 막는
별도 안전장치다. API 키 자체가 없으면 Client를 만들 이유가 없다(어차피 전송 실패).

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
    """error 스크럽을 선점 설치하고, 부팅 시점에 트레이싱이 켜져 있으면 마스킹 완전성을 강제한다.

    - API 키 없음: 아무것도 하지 않는다 — Client를 만들 이유가 없다(전송 자체가 불가능).
    - API 키 있음: `get_cached_client(anonymizer=...)`로 전역 싱글턴을 선점 생성한다.
      **부팅 시 트레이싱이 꺼져 있어도 실행한다**(4차 리뷰 P3) — 나중에 요청 단위
      `tracing_context(enabled=True)`가 재도입돼도 error 스크럽이 이미 걸려 있게 하기 위함.
      이 시점의 HIDE_* env 값이 Client에 고정되므로 입출력 마스킹도 함께 미리 잠긴다.
    - 그중에서도 **부팅 시점에 트레이싱이 실제로 켜져 있으면** HIDE 완전성을 추가로 강제한다 —
      둘 중 하나라도 정확히 "true"가 아니면 RuntimeError로 기동 중단(fail-closed). 이건
      "지금 이 순간 새는지"를 막는 것이고, 위 anonymizer 선점은 "나중에 새는 것까지" 막는
      별도 안전장치다(둘 다 필요 — 모듈 docstring "4차 리뷰 P3 후속" 참고).
    """
    api_key_configured = ls_utils.get_env_var("API_KEY", default=None) is not None
    if api_key_configured:
        from langsmith.run_trees import get_cached_client

        # 첫 트레이스 전에 싱글턴을 선점해야 anonymizer가 실린다 — 이미 생성돼 있으면(정상
        # 부팅 순서에선 불가능) 기존 인스턴스가 반환되며 kwargs는 무시된다.
        get_cached_client(anonymizer=scrub_error_anonymizer)

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
