"""Grounding Check (사실 검증) — 생성 체인 후처리 환각 방어 게이트 (HAJA-117, PRD §6.2 line 274)

핵심 원칙(PRD): LLM이 언급한 **하자 개수·등급**을 `defects` 실측치와 **코드로 대조**한다.
불일치 시 해당 섹션 재생성(REGENERATE) 또는 경고 배지(WARN)로 처리 — sLLM 수치 환각 방어.

설계 의도:
- 이 단계는 **LLM 호출이 없다** (결정론적·재현 가능·크레딧 0). 모든 판정은 코드로만.
- 특정 체인 전용이 아니라 `ai/core/`의 **공통 재사용 모듈** — 보고서/브리핑 등 생성 체인이
  마지막에 `check_grounding(...)` 을 호출해 게이트로 사용한다 (AI_개발_컨벤션.md §0 공통 기반 원칙).
- 입력 claims 는 생성 체인의 **structured output**(Pydantic)에서 채운다 — 자유 텍스트 정규식 파싱 금지
  (AI_개발_컨벤션.md §4). 서술형 텍스트 추출 경로는 후속 이슈 + AI 코치 협의로 분리.
"""
from enum import Enum

from pydantic import BaseModel, Field, field_validator

VALID_GRADES = ("A", "B", "C", "D", "E")  # 시설물 안전점검 등급 (defect_explain.md 등급 기준)

_GRADE_SUFFIX = "등급"


def normalize_grade_strict(value: str) -> str | None:
    """'C등급'·' c '→'C' 처럼 알려진 접미사(등급)만 제거한 뒤, 결과가 정확히 한 글자이고
    A~E에 속할 때만 유효로 인정한다. 그 외(다글자 잔존 등)는 None — first-char 매칭 휴리스틱은
    "Bogus" 같은 임의 문자열의 첫 글자가 우연히 A~E와 겹치면 오인식하므로 사용하지 않는다
    (PR머신 3차 리뷰 지적, grounding.py의 _norm_grade / GroundingDefect._validate_grade 공통 헬퍼).
    """
    normalized = value.strip().upper()
    if normalized.endswith(_GRADE_SUFFIX):
        normalized = normalized[: -len(_GRADE_SUFFIX)].strip()
    if len(normalized) == 1 and normalized in VALID_GRADES:
        return normalized
    return None


class CheckStatus(str, Enum):
    MATCH = "MATCH"  # 주장 == 실측치
    MISMATCH = "MISMATCH"  # 주장 != 실측치 (환각)
    # 대조할 실측 근거(defects)가 아예 없어 양성 주장의 참/거짓을 판정할 수 없는 상태 (HAJA-117 후속 #117).
    # defects 가 빈 경우 "3건 주장 vs 실측 0"을 곧바로 환각(MISMATCH→재생성)으로 단정하면,
    # 실제 환각과 "실측 파이프라인 미반영으로 근거만 비어있는 정상 생성물"을 구분하지 못한다.
    # → 근거 부재 시 UNVERIFIABLE로 분기해 재생성 대신 사람 확인(WARN)을 유도. (0건 주장은 0==0이라 MATCH)
    UNVERIFIABLE = "UNVERIFIABLE"


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
    grade: str  # 심각도 등급 A~E (검증자가 정규화)

    @field_validator("grade")
    @classmethod
    def _validate_grade(cls, v: str) -> str:
        """실측 grade를 A~E 대문자로 정규화·검증. 'C등급'·' c '→'C'.

        시설 안전점검 도메인상 등급 오류는 grounding 판정 신뢰도에 직결되므로,
        오탈자·비정상값은 매칭 키로 흘러들기 전에 경계에서 차단(무결성 방어).
        """
        grade = normalize_grade_strict(v)
        if grade is None:
            raise ValueError(
                f"실측 grade는 A~E여야 합니다 (입력: {v!r}). defects 데이터 무결성 오류."
            )
        return grade


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
    """대조 결과. ⚠️ 소비처(라우터/프론트) 주의: `grounded`만 보고 통과 판정하지 말 것.
    근거 부재(UNVERIFIABLE)만 있는 경우 `grounded=True` 이면서 `action=WARN` 이 될 수 있다(#117, #121).
    즉 grounded=True 라도 action != PASS 이면 경고 배지를 노출해야 한다 — `action`(또는 `unverifiable`)을 함께 확인. (#121 P2)
    """
    grounded: bool  # 확정 불일치(MISMATCH)가 하나도 없으면 True (검증불가만 있어도 True — action으로 최종 판정)
    action: GroundingAction  # 통과(PASS) / 확정 불일치 조치(REGENERATE·WARN) / 검증불가(WARN) — 최종 판정 기준
    ground_truth: GroundTruth
    checks: list[CheckItem]  # 수행한 전체 대조 내역
    mismatches: list[CheckItem]  # 확정 불일치(환각) 항목 — 재생성 트리거용
    unverifiable: list[CheckItem] = Field(default_factory=list)  # 대조 근거 없어 검증 불가 항목 — 사람 확인용


def _norm_grade(value: str) -> str:
    """'C등급', ' c ' 등을 대문자 등급 문자로 정규화. 유효 등급으로 확정되지 않으면(예: "Bogus")
    first-char 오인식 없이 strip+upper된 원본을 그대로 반환한다(기존 계약 유지 — 매칭 실패 시 원본 유지)."""
    normalized = normalize_grade_strict(value)
    return normalized if normalized is not None else value.strip().upper()


def _norm_type(value: str) -> str:
    """하자 유형 문자열 정규화 — 앞뒤 공백 제거 + 내부 연속 공백을 1칸으로 축약
    ('균열 '·' 균열'·'균열  균열'→일관 키). 단어 사이의 단일 공백은 보존한다(예: '철근 노출'은 그대로).

    grade와 달리 유형은 자유 문자열이라, 공백/서식 차이만으로 정상 데이터가
    MISMATCH(불필요한 재생성)로 오탐되지 않도록 실측·주장 양쪽을 같은 규칙으로 정규화한다.
    """
    return " ".join(value.split())


def summarize_defects(defects: list[GroundingDefect]) -> GroundTruth:
    """실측 defects → 총계·등급별·유형별 집계 (대조 기준)."""
    by_grade: dict[str, int] = {}
    by_type: dict[str, int] = {}
    for d in defects:
        grade = _norm_grade(d.grade)
        dtype = _norm_type(d.defect_type)
        by_grade[grade] = by_grade.get(grade, 0) + 1
        by_type[dtype] = by_type.get(dtype, 0) + 1
    return GroundTruth(total_count=len(defects), count_by_grade=by_grade, count_by_type=by_type)


def _cmp(field: str, claimed: int, actual: int, has_basis: bool = True) -> CheckItem:
    # 실측 근거가 아예 없는데 양성(>0) 주장 → 환각으로 단정하지 않고 검증 불가로 분기
    if not has_basis and claimed > 0:
        return CheckItem(
            field=field, claimed=str(claimed), actual=str(actual),
            status=CheckStatus.UNVERIFIABLE, detail="대조할 실측 근거(defects)가 없어 검증 불가",
        )
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
    has_basis = bool(defects)  # 대조 기준 실측이 하나라도 있는가 (없으면 양성 주장은 검증 불가)
    checks: list[CheckItem] = []

    if claims.total_count is not None:
        checks.append(_cmp("total_count", claims.total_count, truth.total_count, has_basis))

    for grade, cnt in claims.count_by_grade.items():
        g = _norm_grade(grade)
        checks.append(_cmp(f"grade:{g}", cnt, truth.count_by_grade.get(g, 0), has_basis))

    for dtype, cnt in claims.count_by_type.items():
        nt = _norm_type(dtype)  # 주장 유형도 실측과 동일 정규화 후 대조 (공백/서식 오탐 방지, #120)
        checks.append(_cmp(f"type:{nt}", cnt, truth.count_by_type.get(nt, 0), has_basis))

    # 서술 중 언급된 등급이 실제로 존재하는지 (없는 등급을 지어냈는지) 검증
    for raw_grade in claims.mentioned_grades:
        g = _norm_grade(raw_grade)
        actual = truth.count_by_grade.get(g, 0)
        if g not in VALID_GRADES:
            # 유효하지 않은 등급(A~E 밖)은 근거 유무와 무관하게 구조적 환각
            checks.append(CheckItem(
                field=f"mentioned_grade:{raw_grade}", claimed=raw_grade, actual="유효 등급 아님(A~E)",
                status=CheckStatus.MISMATCH, detail="존재하지 않는 등급을 언급",
            ))
        elif not has_basis:
            # 유효 등급이나 대조할 실측 근거가 없음 → 존재 여부 검증 불가
            checks.append(CheckItem(
                field=f"mentioned_grade:{g}", claimed="언급됨", actual="근거 없음",
                status=CheckStatus.UNVERIFIABLE, detail="대조할 실측 근거(defects)가 없어 검증 불가",
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
    unverifiable = [c for c in checks if c.status is CheckStatus.UNVERIFIABLE]
    grounded = not mismatches  # 확정 불일치가 없으면 grounded (검증불가만 있으면 grounded=True + WARN)
    if mismatches:
        action = GroundingAction.REGENERATE if on_mismatch is MismatchPolicy.REGENERATE else GroundingAction.WARN
    elif unverifiable:
        action = GroundingAction.WARN  # 근거 부재 — 재생성 대신 사람 확인
    else:
        action = GroundingAction.PASS

    return GroundingResult(
        grounded=grounded, action=action, ground_truth=truth,
        checks=checks, mismatches=mismatches, unverifiable=unverifiable,
    )
