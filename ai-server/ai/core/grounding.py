"""Grounding Check (사실 검증) — 생성 체인 후처리 환각 방어 게이트 (HAJA-117, PRD §6.2 line 274)

핵심 원칙(PRD): LLM이 언급한 **하자 개수·등급**을 `defects` 실측치와 **코드로 대조**한다.
불일치 시 해당 섹션 재생성(REGENERATE) 또는 경고 배지(WARN)로 처리 — sLLM 수치 환각 방어.

설계 의도:
- 이 단계는 **LLM 호출이 없다** (결정론적·재현 가능·크레딧 0). 모든 판정은 코드로만.
- 특정 체인 전용이 아니라 `ai/core/`의 **공통 재사용 모듈** — 보고서/브리핑 등 생성 체인이
  마지막에 `check_grounding(...)` 을 호출해 게이트로 사용한다 (AI_개발_컨벤션.md §0 공통 기반 원칙).
- 서술형 텍스트만 가진 체인은 `check_generated_report(...)` 로 텍스트에서 주장 수치를 추출해 대조.
"""
from enum import Enum
import re

from pydantic import BaseModel, Field

VALID_GRADES = ("A", "B", "C", "D", "E")  # 시설물 안전점검 등급 (defect_explain.md 등급 기준)


class CheckStatus(str, Enum):
    MATCH = "MATCH"  # 주장 == 실측치
    MISMATCH = "MISMATCH"  # 주장 != 실측치 (환각)
    UNVERIFIABLE = "UNVERIFIABLE"  # 대조할 실측 근거 없음


class GroundingAction(str, Enum):
    PASS = "PASS"  # 근거 일치 — 통과
    REGENERATE = "REGENERATE"  # 불일치 — 해당 섹션 재생성 권고 (기본)
    WARN = "WARN"  # 불일치 — 경고 배지 표시 (재생성 대신)


class MismatchPolicy(str, Enum):
    """불일치 발생 시 취할 조치 — 호출 측(체인)이 선택."""
    REGENERATE = "regenerate"
    WARN = "warn"


class GroundingDefect(BaseModel):
    """대조 기준이 되는 실측 하자 1건 (defects 테이블의 grounding 필요 부분집합)."""
    defect_type: str  # 하자 유형 (예: 균열, 박리, 누수)
    grade: str  # 심각도 등급 A~E


class GroundingClaims(BaseModel):
    """생성물(LLM 출력)이 주장하는 수치·등급. structured output에서 채우거나 텍스트에서 추출."""
    total_count: int | None = None  # 주장한 총 하자 건수
    count_by_grade: dict[str, int] = Field(default_factory=dict)  # 등급별 주장 건수 {"C": 3}
    count_by_type: dict[str, int] = Field(default_factory=dict)  # 유형별 주장 건수 {"균열": 2}
    mentioned_grades: list[str] = Field(default_factory=list)  # 서술 중 언급된 등급 (존재 검증용)


class GroundTruth(BaseModel):
    """실측 defects에서 코드로 집계한 사실."""
    total_count: int
    count_by_grade: dict[str, int]
    count_by_type: dict[str, int]


class CheckItem(BaseModel):
    field: str  # 대조 항목 (예: total_count, grade:C, type:균열, mentioned_grade:F)
    claimed: str  # 생성물이 주장한 값
    actual: str  # 실측치
    status: CheckStatus
    detail: str = ""


class GroundingResult(BaseModel):
    grounded: bool  # 불일치가 하나도 없으면 True
    action: GroundingAction  # 통과(PASS) 또는 불일치 조치(REGENERATE/WARN)
    ground_truth: GroundTruth
    checks: list[CheckItem]  # 수행한 전체 대조 내역
    mismatches: list[CheckItem]  # 그중 불일치 항목만 (프론트 경고/재생성 트리거용)


def _norm_grade(value: str) -> str:
    """'C등급', ' c ' 등을 대문자 등급 문자로 정규화. 등급 문자가 아니면 원본 유지."""
    stripped = value.strip().upper()
    return stripped[0] if stripped and stripped[0] in VALID_GRADES else stripped


def summarize_defects(defects: list[GroundingDefect]) -> GroundTruth:
    """실측 defects → 총계·등급별·유형별 집계 (대조 기준)."""
    by_grade: dict[str, int] = {}
    by_type: dict[str, int] = {}
    for d in defects:
        grade = _norm_grade(d.grade)
        by_grade[grade] = by_grade.get(grade, 0) + 1
        by_type[d.defect_type] = by_type.get(d.defect_type, 0) + 1
    return GroundTruth(total_count=len(defects), count_by_grade=by_grade, count_by_type=by_type)


def _cmp(field: str, claimed: int, actual: int) -> CheckItem:
    status = CheckStatus.MATCH if claimed == actual else CheckStatus.MISMATCH
    detail = "" if status is CheckStatus.MATCH else "생성물 주장이 실측치와 불일치"
    return CheckItem(field=field, claimed=str(claimed), actual=str(actual), status=status, detail=detail)


def check_grounding(
    defects: list[GroundingDefect],
    claims: GroundingClaims,
    on_mismatch: MismatchPolicy = MismatchPolicy.REGENERATE,
) -> GroundingResult:
    """생성물의 주장 수치·등급을 실측 defects와 코드로 대조.

    반환 GroundingResult.action 으로 통과/재생성/경고를 판정한다.
    """
    truth = summarize_defects(defects)
    checks: list[CheckItem] = []

    if claims.total_count is not None:
        checks.append(_cmp("total_count", claims.total_count, truth.total_count))

    for grade, cnt in claims.count_by_grade.items():
        g = _norm_grade(grade)
        checks.append(_cmp(f"grade:{g}", cnt, truth.count_by_grade.get(g, 0)))

    for dtype, cnt in claims.count_by_type.items():
        checks.append(_cmp(f"type:{dtype}", cnt, truth.count_by_type.get(dtype, 0)))

    # 서술 중 언급된 등급이 실제로 존재하는지 (없는 등급을 지어냈는지) 검증
    for raw_grade in claims.mentioned_grades:
        g = _norm_grade(raw_grade)
        actual = truth.count_by_grade.get(g, 0)
        if g not in VALID_GRADES:
            checks.append(CheckItem(
                field=f"mentioned_grade:{raw_grade}", claimed=raw_grade, actual="유효 등급 아님(A~E)",
                status=CheckStatus.MISMATCH, detail="존재하지 않는 등급을 언급",
            ))
        elif actual == 0:
            checks.append(CheckItem(
                field=f"mentioned_grade:{g}", claimed="언급됨", actual="0건",
                status=CheckStatus.MISMATCH, detail="실측에 없는 등급을 언급",
            ))
        else:
            checks.append(CheckItem(
                field=f"mentioned_grade:{g}", claimed="언급됨", actual=f"{actual}건",
                status=CheckStatus.MATCH,
            ))

    mismatches = [c for c in checks if c.status is CheckStatus.MISMATCH]
    grounded = not mismatches
    if grounded:
        action = GroundingAction.PASS
    else:
        action = GroundingAction.REGENERATE if on_mismatch is MismatchPolicy.REGENERATE else GroundingAction.WARN

    return GroundingResult(
        grounded=grounded, action=action, ground_truth=truth, checks=checks, mismatches=mismatches,
    )


# --- 서술형 텍스트에서 주장 수치 추출 (structured output이 없는 체인용 보조) --------------------

_TOTAL_PATTERNS = (
    re.compile(r"(?:총|전체)\s*(\d+)\s*(?:건|개)"),  # 총 12건 / 전체 12개
    re.compile(r"하자(?:는|가|를)?\s*(?:총\s*)?(\d+)\s*(?:건|개)"),  # 하자 12건 / 하자는 총 12건
    re.compile(r"(\d+)\s*(?:건|개)\s*의?\s*하자"),  # 12건의 하자
)
_GRADE_COUNT_PATTERN = re.compile(r"([A-Ea-e])\s*등급\s*(?:하자\s*)?(\d+)\s*(?:건|개)")  # C등급 3건
_GRADE_MENTION_PATTERN = re.compile(r"([A-Ea-e])\s*등급|등급\s*([A-Ea-e])\b")  # C등급 / 등급 C


def extract_claims_from_text(text: str) -> GroundingClaims:
    """서술형 생성 텍스트에서 주장 하자 건수·등급을 best-effort 추출."""
    total_count: int | None = None
    for pat in _TOTAL_PATTERNS:
        m = pat.search(text)
        if m:
            total_count = int(m.group(1))
            break

    count_by_grade: dict[str, int] = {}
    for g, cnt in _GRADE_COUNT_PATTERN.findall(text):
        count_by_grade[_norm_grade(g)] = int(cnt)

    mentioned: list[str] = []
    for m in _GRADE_MENTION_PATTERN.finditer(text):
        grade = _norm_grade(m.group(1) or m.group(2))
        if grade not in mentioned:
            mentioned.append(grade)

    return GroundingClaims(
        total_count=total_count, count_by_grade=count_by_grade, mentioned_grades=mentioned,
    )


def check_generated_report(
    defects: list[GroundingDefect],
    generated_text: str,
    on_mismatch: MismatchPolicy = MismatchPolicy.REGENERATE,
) -> GroundingResult:
    """서술형 보고서 텍스트를 실측 defects와 대조하는 편의 래퍼 (추출 → 대조)."""
    return check_grounding(defects, extract_claims_from_text(generated_text), on_mismatch)
