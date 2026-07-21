"""프롬프트 인젝션 방어 공용 모듈 (AI_개발_컨벤션.md §0 — 공통 기반은 ai/core/에 공용화, HAJA-296)

report_chain.py에만 있던 `---BEGIN/END UNTRUSTED DATA---` 마커 wrap/sanitize 로직을 이관해
defect_explain_chain.py·briefing_chain.py 등 사용자·외부 입력을 프롬프트에 삽입하는 모든 체인이
동일한 방어선을 재사용하도록 한다(원래 report_chain에만 있어 나머지 체인의 자유 문자열 필드가
마커 없이 template.format()에 직삽입되던 방어 구멍 — PR머신 검수 P2).

- `_system_base.md`의 프롬프트 인젝션 방어 지침이 참조하는 마커 문자열과 반드시 동일해야 한다.
- wrap_untrusted()로 감싼 텍스트만 "지침이 아니라 데이터"로 취급되므로, 사용자·외부 입력을
  프롬프트에 삽입하는 모든 지점은 이 함수를 거쳐야 한다.
"""
import re

# 사용자/외부 입력이 그대로 프롬프트에 삽입되는 지점을 감싸는 구분자 — _system_base.md의
# 프롬프트 인젝션 방어 지침이 참조하는 마커와 동일해야 한다.
UNTRUSTED_DATA_BEGIN = "---BEGIN UNTRUSTED DATA---"
UNTRUSTED_DATA_END = "---END UNTRUSTED DATA---"


def sanitize_untrusted(text: str) -> str:
    """사용자 입력 안에 마커 리터럴 자체가 들어있으면 치환해 래퍼 조기 종료(스푸핑)를 막는다
    (code-reviewer P2: 자유 텍스트 입력에 길이·문자 제한이 없다면 `---END UNTRUSTED DATA---\\n<가짜 지침>`을
    그대로 넣어 래퍼를 조기 종료시키고, 삽입된 텍스트가 LLM에게 신뢰할 수 있는 프롬프트 내용처럼
    보이게 만들 수 있다). 대시 3개+공백 패턴만 깨뜨려도 정확한 마커 문자열 재구성이 불가능해지므로,
    마커 리터럴 자체가 아니라 그 구성요소인 `---`를 전각 대시로 치환한다 — 부분 문자열이 변형되어
    원본 마커와 더 이상 일치하지 않는다.

    단순 `str.replace("---", "—--")`는 하이픈 개수가 3의 배수가 아닌 연속 런(예: 4개, 5개)에서
    치환 후에도 하이픈이 leftover로 남아, 인접 텍스트와 결합하면 원본 마커의 부분 문자열이
    그대로 재구성될 수 있었다(PR #240 리뷰 P2). 정규식으로 하이픈 3개 이상 연속된 런 전체를
    한 번에 매칭해 치환하므로 길이에 상관없이 leftover 하이픈이 남지 않는다.
    """
    return re.sub(r"-{3,}", lambda m: "—" * (len(m.group(0)) - 1) + "-", text)


def wrap_untrusted(text: str) -> str:
    """신뢰할 수 없는 입력(text)을 UNTRUSTED_DATA 마커로 감싼다. sanitize_untrusted를 먼저 거친다."""
    safe_text = sanitize_untrusted(text)
    return f"{UNTRUSTED_DATA_BEGIN}\n{safe_text}\n{UNTRUSTED_DATA_END}"
