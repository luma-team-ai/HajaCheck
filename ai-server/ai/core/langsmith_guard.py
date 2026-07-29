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
API 키가 있으면 트레이싱이 부팅 시 꺼져 있어도 싱글턴을 미리 만들어 anonymizer를 실어둔다.
API 키 자체가 없으면 Client를 만들 이유가 없다(어차피 전송 실패) — `get_env_var`가 빈
문자열/공백을 "미설정"과 동일하게 `None`으로 취급하므로(실측), compose가 기본값으로 항상
주입하는 `LANGCHAIN_API_KEY=${LANGCHAIN_API_KEY:-}`(빈 문자열) 환경에서 이 분기가 잘못
활성화될 걱정은 없다.

## HIDE 완전성 강제 기준도 "트레이싱 여부"가 아니라 "API 키 존재"로 넓힌다 (5차 리뷰 P2 후속)

4차 수정은 "부팅 시 트레이싱이 켜져 있을 때만" RuntimeError(fail-closed)를 던졌다. 그런데
anonymizer 선점을 API 키 기준으로 넓혀 놓고 HIDE 강제는 트레이싱 기준으로 좁게 남겨두면
비대칭이 생긴다 — "부팅 시 트레이싱 OFF + API 키 있음 + HIDE 미설정" 조합에서 anonymizer는
설치되지만 `Client._hide_inputs/_hide_outputs`는 그 순간의 HIDE 값(미설정=false)으로
**영구 고정**된 채 싱글턴이 만들어진다. 이 PR이 막으려는 시나리오(나중에 요청 단위
`tracing_context(enabled=True)` 재도입)가 실제로 벌어지면, error는 스크럽되지만
inputs/outputs 원문은 그대로 나간다 — 딱 이 PR의 존재 이유인 유출이 재발한다.

그래서 HIDE 완전성 강제도 anonymizer 선점과 **같은 조건**(API 키 존재 OR 부팅 시 트레이싱
ON)으로 통일한다. API 키가 있다는 것 자체가 "이 프로세스에서 LangSmith로 뭔가 나갈 수
있다"는 뜻이므로, 그 시점에 마스킹이 불완전하면 나중에 새든 지금 새든 결과는 같다 — 둘을
가를 이유가 없다. API 키도 없고 트레이싱도 꺼져 있으면 전송 자체가 불가능하므로 검사하지
않는다.

## anonymizer 선점이 조용히 무시될 수 있는 경로를 명시적으로 막는다 (5차 리뷰 P2 후속)

`get_cached_client(anonymizer=...)`는 싱글턴이 이미 존재하면 전달한 kwargs를 조용히
무시하고 기존 인스턴스를 그대로 반환한다(langsmith 자체 동작). 정상 부팅 순서(이 함수가
가장 먼저 호출됨)에서는 발생하지 않지만, 어떤 보조 진입점이 langsmith를 먼저 건드려
anonymizer 없는 싱글턴을 만들어 버리면 이 함수가 호출돼도 아무 신호 없이 스크럽이 빠진
채로 넘어간다. 그래서 반환된 client의 `_anonymizer`를 직접 확인하고, 기대한 함수가 아니면
명시적으로 재할당한다 — "이미 있으니 통과"가 아니라 "이미 있어도 우리가 원하는 상태로
맞춘다."

## anonymizer는 게이트 없이 무조건 설치한다 — 비대칭의 뿌리 제거 (6차 리뷰 P1 후속)

4~6차 리뷰가 매번 찾아낸 결함은 전부 같은 구조였다: 보호장치 2개(HIDE 강제, anonymizer
설치)가 **각자 다른 조건식**을 갖고 있어서, 조건이 갈라지는 조합이 반드시 하나 남았다.
4차는 "API키 있음+트레이싱 OFF"에서 HIDE 강제가 빠졌고, 5차 수정 후 6차는 정반대
"API키 없음+트레이싱 ON"에서 anonymizer가 빠졌다. 조건식이 2개인 한 이 두더지잡기는
끝나지 않는다.

그래서 anonymizer 쪽 조건식을 **아예 없앤다** — 이 함수가 호출되면 항상 설치한다.
근거(2026-07-30 실측):
- API 키 없이도 `Client()`는 정상 생성된다(경고 1줄, 예외 없음).
- **API 키 없이도 트레이싱이 켜지면 전송을 실제로 시도한다** — `request_with_retries`에
  본문(bytes)이 도달함을 확인. "키가 없으면 어차피 전송 실패"라던 이전 가정은 틀렸다:
  전송 실패는 서버의 인증 거부(401/403)이고, **본문은 그 전에 이미 프로세스를 떠난다.**
- 우리 anonymizer는 error가 아닌 형태(inputs/outputs)에는 `{}`를 돌려주므로, 무조건
  설치는 "HIDE env가 어떤 상태든 내용은 절대 안 나간다"는 **전 필드 백스톱**을 겸한다.

무조건 설치 후의 전체 상태 행렬 (K=API키, T=부팅시 트레이싱, H=HIDE 둘 다 "true"):

| K | T | H | 동작 |
|---|---|---|---|
| ✗ | ✗ | 무관 | 기동 허용. anonymizer 설치됨 — 훗날 코드가 트레이싱을 켜도 내용·error 비전송 |
| ✗ | ✓ | ✗ | RuntimeError 기동 중단 (전송 시도가 실재하므로 — 위 실측) |
| ✗ | ✓ | ✓ | 기동 허용. HIDE가 내용 차단 + anonymizer가 error 차단 |
| ✓ | ✗ | ✗ | RuntimeError 기동 중단 (싱글턴에 HIDE=false가 영구 고정되는 것을 차단) |
| ✓ | ✗ | ✓ | 기동 허용. 마스킹 고정 + anonymizer 설치 — 훗날 재도입 대비 완비 |
| ✓ | ✓ | ✗ | RuntimeError 기동 중단 |
| ✓ | ✓ | ✓ | 기동 허용. 정상 운영 상태 |

남는 조건식은 "HIDE 강제를 언제 하나" 하나뿐이고(K∨T), anonymizer에는 조건식 자체가
없다 — 갈라질 짝이 없으므로 이 계열의 비대칭은 구조적으로 재발할 수 없다.

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

# langsmith(run_helpers._format_error_with_exceptions_to_handle, 실측)는 error 문자열을
# f"{repr(error)}\n\n{stacktrace}"로 채운다 — 표준 예외의 repr()은 항상 "ClassName(args...)"
# 형태다(파이썬 BaseException 기본 __repr__ 보장). 그래서 식별자 뒤에 **여는 괄호가 바로
# 이어지는 경우만** "타입명 접두사"로 신뢰한다 — 괄호가 없으면 우연히 식별자로 시작하는
# 순수 메시지(str(e), 커스텀 포맷 등)일 수 있으므로 타입명도 남기지 않는다(5차 리뷰 P2 —
# 이전엔 괄호 유무를 안 봐서 순수 메시지의 선두 토큰까지 "타입명"으로 오인해 반향할 수 있었다).
_EXCEPTION_REPR_RE = re.compile(r"^([A-Za-z_][A-Za-z0-9_.]*)\(")


def scrub_error_anonymizer(data: dict) -> dict:
    """LangSmith Client의 anonymizer 훅 — run의 error 필드에서 내용(메시지·트레이스백)을 제거한다.

    `Client._hide_run_error`는 error 문자열을 `{"error": ...}`로 감싸 anonymizer에 넘기고
    반환 dict의 "error" 키를 다시 꺼낸다(langsmith 0.10.10). `repr(error)` 형태(타입명 뒤에
    괄호가 바로 옴)로 확인되는 경우에만 타입명을 남기고 나머지를 치환한다 — 그 형태가 아니면
    타입명도 신뢰하지 않고 고정 문구만 돌려준다(무엇이 실패했는지는 가능한 선에서만 추적하되,
    원문·오인식 위험이 있는 토큰은 절대 전송하지 않는다).

    inputs/outputs 경로: HIDE env가 true면 anonymizer보다 먼저 `{}`로 대체되므로 이 함수는
    호출되지 않는다. 혹시 HIDE가 꺼진 채 이 anonymizer만 살아있는 비정상 조합이 되면 전부
    `{}`를 돌려줘 내용을 비전송한다 — env 마스킹과 같은 방향의 이중 안전장치.
    """
    if set(data.keys()) == {"error"}:
        error = data["error"]
        match = _EXCEPTION_REPR_RE.match(str(error) if error is not None else "")
        if match:
            return {"error": f"{match.group(1)}(...) [메시지·트레이스백 마스킹 — #1240]"}
        return {"error": "[예외 발생 — 내용 마스킹 #1240]"}
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
    """HIDE 완전성을 강제(전송 가능 조건일 때)하고, error 스크럽을 **무조건** 선점 설치한다.

    두 동작의 조건이 다르다 — 이 차이는 의도된 것이다(모듈 docstring의 상태 행렬 참고):

    - **HIDE 완전성 강제(RuntimeError)**: API 키가 있거나 부팅 시 트레이싱이 켜져 있을 때만.
      전송이 실제로 가능한 조건에서 마스킹이 불완전하면 기동을 중단한다(fail-closed).
      둘 다 없으면 강제하지 않는다 — 아무것도 설정 안 한 개발·테스트 프로세스까지 HIDE
      설정을 요구하면 과잉이고, 그 경우의 잔여 위험은 아래 무조건 설치가 흡수한다.
    - **anonymizer 선점 설치**: 조건 없음, 항상. 조건식을 두면 HIDE 강제 조건과 갈라지는
      조합이 반드시 생긴다는 것이 4~6차 리뷰의 교훈이다(6차 리뷰 P1 — 모듈 docstring
      "비대칭의 뿌리 제거" 참고). API 키 없이도 Client 생성·전송 시도가 모두 가능함을
      실측으로 확인했고, 우리 anonymizer는 inputs/outputs에 `{}`를 돌려주는 전 필드
      백스톱이라 어떤 상태에서 설치돼도 마스킹 방향으로만 작동한다.
    """
    api_key_configured = ls_utils.get_env_var("API_KEY", default=None) is not None
    # 부팅 시점엔 활성 run tree·컨텍스트 오버라이드가 없으므로 순수 env 판정이다.
    # 반환값 "local"(코드로만 설정 가능)도 truthy로 취급 — 마스킹을 강제하는 안전한 방향.
    tracing_enabled = ls_utils.tracing_is_enabled()

    if api_key_configured or tracing_enabled:
        missing = _hide_env_missing()
        if missing:
            raise RuntimeError(
                "LangSmith API 키가 설정됐거나 트레이싱이 켜져 있는데 입출력 마스킹이 "
                f"불완전합니다 — {', '.join(missing)} 를 정확히 소문자 \"true\"로 설정하거나 "
                "API 키를 제거하고 트레이싱을 끄세요"
                "(Client는 소문자 \"true\" 외의 값을 전부 마스킹 OFF로 해석합니다). "
                "API 키가 없어도 트레이싱이 켜져 있으면 전송은 시도되고 본문은 인증 거부 "
                "전에 이미 프로세스를 떠납니다 — 마스킹 없인 OCR 원문·고객 개인정보가 "
                "외부 LangSmith로 나갑니다(#1240)."
            )

    from langsmith.run_trees import get_cached_client

    # 첫 트레이스 전에 싱글턴을 선점해야 anonymizer가 실린다 — 게이트 없이 항상 설치한다.
    client = get_cached_client(anonymizer=scrub_error_anonymizer)
    if client._anonymizer is not scrub_error_anonymizer:
        # 싱글턴이 이미 존재했다면(정상 부팅 순서에선 불가능하지만 보조 진입점에서는
        # 가능) 위 호출의 kwargs가 조용히 무시된다 — 그 경우를 명시적으로 감지해 강제한다.
        client._anonymizer = scrub_error_anonymizer
