"""AI 보고서 생성 체인 — 4섹션(개요/요약/상세/권고) 병렬 생성 + Grounding Check (dev-07-01, HAJA-31)

설계: docs/design/ai/report-chain-design.md §2-§6. 컨벤션: AI_개발_컨벤션.md §8 예시 체인 절차.

- 4개 섹션은 서로 독립적이므로 RunnableParallel로 동시 invoke (design §3)
- summary/detail 은 briefing_chain.py 패턴과 동일하게 **수치는 코드로 집계해 프롬프트에 주입**하고,
  LLM에는 "그대로 옮겨 적기"만 지시한다 (LLM이 수치를 직접 계산·창작하지 않도록 — 환각 방지 원칙)
- Grounding Check(ai.core.grounding)는 그대로 재사용 — 자체 대조 로직을 새로 만들지 않는다 (design §5)
- detail 섹션의 items 개수 대조는 grounding 공통 모듈의 범위 밖이므로 이 파일에서 별도로 검증한다 (design §5-4)
- recommendation 섹션의 RAG 조회(ai.core.vectorstore.get_vectorstore)는 아직 NotImplementedError 스텁이므로,
  실패 시(0건 검색과 동일하게) legal_basis를 "관련 근거 없음"으로 고정하고 체인 전체는 정상 진행한다
"""
import logging
from pathlib import Path

from langchain_core.runnables import RunnableLambda, RunnableParallel
from pydantic import BaseModel, Field

from ai.core.grounding import (
    VALID_GRADES,
    GroundingAction,
    GroundingClaims,
    GroundingDefect,
    MismatchPolicy,
    check_grounding,
    normalize_grade_strict,
)
from ai.core.llm_client import MAX_RETRIES, get_llm
from ai.core.vectorstore import COLLECTION_REGULATIONS, get_vectorstore

logger = logging.getLogger(__name__)

PROMPTS_DIR = Path(__file__).resolve().parent.parent / "prompts"

# facility_info 는 자유 dict(계약상 name/location 등)이므로, 알려진 키에는 한국어 라벨을 붙이고
# 나머지 키는 키 이름을 그대로 노출해 정보 손실 없이 프롬프트에 전달한다.
FACILITY_FIELD_LABELS = {
    "name": "명칭",
    "location": "위치",
    "facility_type": "시설물 유형",
    "scale": "규모",
    "inspection_date": "점검일자",
}


class ReportOverview(BaseModel):
    """LLM 응답은 structured output 으로만 수신 — 자유 텍스트 파싱 금지"""
    purpose: str  # 점검 목적
    facility_summary: str  # 시설물 개요
    scope: str  # 점검 범위·대상 부위


class ReportSummary(BaseModel):
    """total_count/count_by_grade 필드명은 ai.core.grounding.GroundingClaims와 동일해야 함(design §5-1).

    count_by_grade/key_findings는 design §6.2 기준 필수 출력값이라 기본값을 두지 않는다 — LLM이
    필드를 누락하면 `_StructuredLLM`의 PydanticOutputParser 파싱 단계에서 실패해 기존 재시도 경로를
    타야 하며, 여기서 조용히 빈 dict/list로 통과되면 grounding 검사가 "0건 주장"으로 오판할 수 있다.
    """
    overall_opinion: str  # 종합 의견
    total_count: int  # 총 하자 수
    count_by_grade: dict[str, int]  # 등급별 개수 {"A": n, ...} — 필수(누락 시 파싱 실패 → 재시도)
    key_findings: list[str]  # 주요 발견사항 3~5개 — 필수(누락 시 파싱 실패 → 재시도)


class DefectDetailItem(BaseModel):
    defect_type: str
    location: str
    severity_grade: str
    description: str
    cause: str


class ReportDetail(BaseModel):
    items: list[DefectDetailItem] = Field(default_factory=list)  # confirmed_defects와 1:1


class RecommendationItem(BaseModel):
    target: str
    method: str
    priority: str
    legal_basis: str  # RAG 근거 인용, 검색 결과 없으면 "관련 근거 없음"
    # LLM 출력 스키마의 일부가 아니라 _run_recommendation_chain이 생성 후 계산해 채운다(PR머신 P2 후속) —
    # legal_basis 문자열이 실제 검색된 legal_basis_context에 (부분)포함되는지 대조한 결과.
    # RAG 검색 결과가 일부라도 있으면 기존에는 legal_basis를 그대로 신뢰했는데, LLM이 검색된 문서 중
    # 무관한 조문을 인용하거나 존재하지 않는 조문을 지어내도 걸러내지 못했다 — 이 플래그로 하류(FE)가
    # 미검증 인용을 구분해 표시할 수 있게 한다. False라고 응답 자체를 막지는 않는다(컨벤션 §5).
    legal_basis_verified: bool = False


class ReportRecommendation(BaseModel):
    items: list[RecommendationItem] = Field(default_factory=list)
    monitoring_points: list[str] = Field(default_factory=list)


# ── 공통: 코드로 집계한 사실을 텍스트로 조립 (LLM이 재계산하지 않도록 — briefing_chain.py 패턴) ──

def _format_facility_info(facility_info: dict) -> str:
    lines = []
    for key, label in FACILITY_FIELD_LABELS.items():
        value = facility_info.get(key)
        if value not in (None, ""):
            lines.append(f"- {label}: {value}")
    for key, value in facility_info.items():
        if key not in FACILITY_FIELD_LABELS and value not in (None, ""):
            lines.append(f"- {key}: {value}")
    return "\n".join(lines) if lines else "- (제공된 시설물 정보 없음)"


def _normalize_grade(raw: str) -> str:
    """grounding.py의 통일된 정규화 헬퍼를 재사용 — 자체 first-char 휴리스틱을 복붙하지 않는다
    (PR머신 3차 리뷰 지적: 첫 글자만 보는 방식은 "Bogus" 같은 값을 "B"등급으로 오인식했다).
    유효 등급으로 확정되지 않으면(예: "Bogus") full_grade_counts의 `if grade in counts` 필터에서
    자연히 걸러지도록, 매칭 실패 시 원본(strip+upper)을 그대로 반환한다(기존 계약 유지)."""
    normalized = normalize_grade_strict(str(raw or ""))
    return normalized if normalized is not None else str(raw or "").strip().upper()


def full_grade_counts(confirmed_defects: list[dict]) -> dict[str, int]:
    """등급별 개수를 A~E 전체(0 포함)로 집계 — contract.md 응답 예시와 동일하게 결측 등급도 0으로 채움."""
    counts = {g: 0 for g in VALID_GRADES}
    for d in confirmed_defects:
        grade = _normalize_grade(d.get("severity_grade", ""))
        if grade in counts:
            counts[grade] += 1
    return counts


def _type_counts(confirmed_defects: list[dict]) -> dict[str, int]:
    counts: dict[str, int] = {}
    for d in confirmed_defects:
        dtype = str(d.get("defect_type", "")).strip() or "-"
        counts[dtype] = counts.get(dtype, 0) + 1
    return counts


def _format_defects_list(confirmed_defects: list[dict]) -> str:
    if not confirmed_defects:
        return "(확정된 하자 없음)"
    lines = []
    for i, d in enumerate(confirmed_defects, start=1):
        lines.append(
            f"{i}. 유형: {d.get('defect_type', '-')} / 위치: {d.get('location', '-')} / "
            f"등급: {d.get('severity_grade', '-')} / 설명: {d.get('description', '-')}"
        )
    return "\n".join(lines)


# ── overview ──

def _build_prompt_overview(facility_info: dict) -> str:
    system = (PROMPTS_DIR / "_system_base.md").read_text(encoding="utf-8")
    template = (PROMPTS_DIR / "report_overview.md").read_text(encoding="utf-8")
    filled = template.format(facility_info_text=_format_facility_info(facility_info))
    return f"{system}\n\n{filled}"


def _run_overview_chain(facility_info: dict) -> ReportOverview:
    prompt = _build_prompt_overview(facility_info)
    return get_llm().with_structured_output(ReportOverview).invoke(prompt)


# ── summary ──

def _build_prompt_summary(confirmed_defects: list[dict]) -> str:
    system = (PROMPTS_DIR / "_system_base.md").read_text(encoding="utf-8")
    template = (PROMPTS_DIR / "report_summary.md").read_text(encoding="utf-8")
    grade_counts = full_grade_counts(confirmed_defects)
    grade_text = ", ".join(f"{g}등급 {c}건" for g, c in grade_counts.items())
    type_counts = _type_counts(confirmed_defects)
    type_text = ", ".join(f"{t} {c}건" for t, c in type_counts.items()) or "없음"
    filled = template.format(
        total_count=len(confirmed_defects),
        count_by_grade_text=grade_text,
        type_breakdown_text=type_text,
    )
    return f"{system}\n\n{filled}"


def _run_summary_chain(confirmed_defects: list[dict]) -> ReportSummary:
    prompt = _build_prompt_summary(confirmed_defects)
    return get_llm().with_structured_output(ReportSummary).invoke(prompt)


# ── detail ──

def _build_prompt_detail(confirmed_defects: list[dict]) -> str:
    system = (PROMPTS_DIR / "_system_base.md").read_text(encoding="utf-8")
    template = (PROMPTS_DIR / "report_detail.md").read_text(encoding="utf-8")
    filled = template.format(
        defect_count=len(confirmed_defects),
        defects_list_text=_format_defects_list(confirmed_defects),
    )
    return f"{system}\n\n{filled}"


def _run_detail_chain(confirmed_defects: list[dict]) -> ReportDetail:
    prompt = _build_prompt_detail(confirmed_defects)
    return get_llm().with_structured_output(ReportDetail).invoke(prompt)


# ── recommendation (+ RAG, vectorstore 미구현 시 "관련 근거 없음" 폴백) ──

def _retrieve_legal_basis_context(confirmed_defects: list[dict]) -> str:
    """Chroma regulations 컬렉션 검색. get_vectorstore()가 아직 NotImplementedError 스텁이거나
    그 어떤 이유로든 검색 경로가 실패하면, design 문서가 이미 정의한 "0건 검색" 케이스와
    동일하게 취급 — 빈 컨텍스트를 반환하고 체인은 정상 진행한다(요청 전체를 실패시키지 않음).
    vectorstore.py가 나중에 실제 구현되면 이 함수는 코드 변경 없이 실제 검색 결과를 그대로 흘려보낸다.
    """
    try:
        vectorstore = get_vectorstore(COLLECTION_REGULATIONS)
        query = " ".join(sorted({str(d.get("defect_type", "")) for d in confirmed_defects if d.get("defect_type")}))
        docs = vectorstore.similarity_search(query, k=3)
    except NotImplementedError as e:
        # 현재 예상된 상태(vectorstore.py 미구현) — 소음 방지 위해 info, 하지만 로그는 남긴다
        logger.info("vectorstore 미구현(NotImplementedError) — 검색 결과 없음으로 폴백: %s", e)
        return ""
    except Exception:  # noqa: BLE001 — vectorstore 실구현 이후의 진짜 오류를 놓치지 않기 위해 warning으로 남김
        logger.warning("법규 검색 실패 — 검색 결과 없음으로 폴백", exc_info=True)
        return ""
    if not docs:
        return ""
    return "\n".join(f"- {getattr(doc, 'page_content', str(doc))}" for doc in docs)


def _build_prompt_recommendation(confirmed_defects: list[dict], legal_basis_context: str) -> str:
    system = (PROMPTS_DIR / "_system_base.md").read_text(encoding="utf-8")
    template = (PROMPTS_DIR / "report_recommendation.md").read_text(encoding="utf-8")
    filled = template.format(
        defect_count=len(confirmed_defects),
        defects_list_text=_format_defects_list(confirmed_defects),
        legal_basis_context=legal_basis_context or "(검색 결과 없음)",
    )
    return f"{system}\n\n{filled}"


def _legal_basis_verified(legal_basis: str, legal_basis_context: str) -> bool:
    """legal_basis 인용문이 실제 검색된 legal_basis_context에 (부분)포함되는지 최소한으로 대조한다.

    엄격한 조문 매칭이 아니라 공백 제거 후 부분 문자열 포함 여부만 보는 저비용 휴리스틱 — LLM이
    검색 결과와 무관하거나 존재하지 않는 조문을 인용해도 legal_basis 자체를 뒤집지는 않고(오탐 시
    보고서 내용을 임의로 훼손하지 않기 위해), legal_basis_verified 플래그로만 신호를 남긴다.
    """
    if not legal_basis_context or not legal_basis:
        return False
    normalized_context = legal_basis_context.replace(" ", "")
    normalized_basis = legal_basis.replace(" ", "")
    return normalized_basis in normalized_context


def _run_recommendation_chain(confirmed_defects: list[dict]) -> ReportRecommendation:
    legal_basis_context = _retrieve_legal_basis_context(confirmed_defects)
    prompt = _build_prompt_recommendation(confirmed_defects, legal_basis_context)
    result: ReportRecommendation = get_llm().with_structured_output(ReportRecommendation).invoke(prompt)

    if not legal_basis_context:
        # RAG 검색 결과가 없음(또는 vectorstore 미구현) — legal_basis를 코드에서 고정값으로 강제해
        # LLM이 그럴듯한 법규·조문을 창작(환각)하는 경로를 원천 차단한다.
        result = ReportRecommendation(
            items=[
                item.model_copy(update={"legal_basis": "관련 근거 없음", "legal_basis_verified": False})
                for item in result.items
            ],
            monitoring_points=result.monitoring_points,
        )
    else:
        # 검색 결과가 일부라도 있는 경우 — 기존에는 이 경로에서 legal_basis를 무조건 신뢰했다(PR머신 P2).
        # 강제 대체는 하지 않되(부분 검색 결과 중 실제로 관련된 인용일 수 있으므로), 검증 결과를 플래그로 남긴다.
        result = ReportRecommendation(
            items=[
                item.model_copy(
                    update={
                        "legal_basis_verified": _legal_basis_verified(item.legal_basis, legal_basis_context)
                    }
                )
                for item in result.items
            ],
            monitoring_points=result.monitoring_points,
        )
    return result


# ── 병렬 실행 + Grounding Check + 조립 ──

def _run_parallel(facility_info: dict, confirmed_defects: list[dict]) -> dict:
    """4개 RunnableLambda는 각자 내부에서 get_llm()을 직접 호출한다(각 _run_*_chain 참고) — 스레드풀에서
    동시 실행돼도 get_llm()이 매 호출마다 새 HuggingFaceEndpoint/ChatOllama + CachedLLM 인스턴스를
    생성하므로(ai.core.llm_client.get_llm, @lru_cache 없음) 4개 브랜치가 클라이언트 상태를 공유하지
    않는다(PR머신 P2 후속 확인 — 유일한 모듈 레벨 공유 상태는 get_llm() 내부가 아니라 llm_client._redis()의
    lru_cache 싱글턴인데, redis-py Redis 클라이언트는 커넥션 풀 기반으로 스레드 안전하다)."""
    parallel = RunnableParallel(
        overview=RunnableLambda(lambda _: _run_overview_chain(facility_info)),
        summary=RunnableLambda(lambda _: _run_summary_chain(confirmed_defects)),
        detail=RunnableLambda(lambda _: _run_detail_chain(confirmed_defects)),
        recommendation=RunnableLambda(lambda _: _run_recommendation_chain(confirmed_defects)),
    )
    return parallel.invoke({})


def _to_grounding_defects(confirmed_defects: list[dict]) -> list[GroundingDefect]:
    return [
        GroundingDefect(defect_type=d.get("defect_type", ""), grade=d.get("severity_grade", ""))
        for d in confirmed_defects
    ]


def run_report_chain(
    facility_info: dict,
    confirmed_defects: list[dict],
    on_mismatch: str = "regenerate",
) -> dict:
    """보고서 4섹션 병렬 생성 → detail 개수 검증 → Grounding Check → 응답 dict 조립.

    반환값은 contract.md `POST /ai/report` 응답의 `data` 필드 형태와 동일하다
    (overview/summary/detail/recommendation + grounding_ok).
    """
    mismatch_policy = MismatchPolicy(on_mismatch)

    # severity_grade 등 저비용 입력 검증(GroundingDefect 생성)은 LLM 호출 전에 먼저 수행한다 —
    # 4섹션 LLM 호출을 전부 마친 뒤에야 잘못된 입력이 드러나면 비용·지연이 낭비되므로(PR머신 지적, P2).
    grounding_defects = _to_grounding_defects(confirmed_defects)

    results = _run_parallel(facility_info, confirmed_defects)
    overview: ReportOverview = results["overview"]
    summary: ReportSummary = results["summary"]
    detail: ReportDetail = results["detail"]
    recommendation: ReportRecommendation = results["recommendation"]

    # detail 섹션 개수 대조는 grounding 공통 모듈의 범위 밖 — report_chain에서 직접 검증(design §5-4)
    if len(detail.items) != len(confirmed_defects):
        raise ValueError(
            "detail 섹션 items 개수"
            f"({len(detail.items)})가 확정 하자 수({len(confirmed_defects)})와 일치하지 않습니다."
        )

    claims = GroundingClaims(total_count=summary.total_count, count_by_grade=summary.count_by_grade)
    grounding_result = check_grounding(grounding_defects, claims, mismatch_policy)

    # 불일치 → 재생성(최대 MAX_RETRIES회, design §5-3). 재생성 후에도 불일치면 WARN(=grounding_ok False)로
    # 전환하되 보고서 생성 자체는 막지 않는다 (컨벤션 §5 "AI 실패가 비-AI 기능을 막으면 안 됨").
    attempts = 0
    while grounding_result.action is GroundingAction.REGENERATE and attempts < MAX_RETRIES:
        summary = _run_summary_chain(confirmed_defects)
        claims = GroundingClaims(total_count=summary.total_count, count_by_grade=summary.count_by_grade)
        grounding_result = check_grounding(grounding_defects, claims, mismatch_policy)
        attempts += 1

    return {
        "overview": overview.model_dump(),
        "summary": summary.model_dump(),
        "detail": detail.model_dump(),
        "recommendation": recommendation.model_dump(),
        "grounding_ok": grounding_result.grounded,
    }
