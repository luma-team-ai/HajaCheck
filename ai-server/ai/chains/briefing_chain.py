"""대시보드 AI 주간 브리핑 체인 (대시보드 담당, PRD §4 대시보드 P1 — AI 주간 브리핑 카드)

현황 데이터 → 자연어 주간 브리핑. 설계 원칙:
- **수치는 코드로 계산·주입, LLM은 자연어만 생성** (전주 대비 변화율·추세를 LLM이 지어내지 않도록 — 수치 환각 방지).
- AI_개발_컨벤션.md §8 예시 체인 절차 준수: 프롬프트 파일 분리 + structured output.
"""
from pathlib import Path

from pydantic import BaseModel, Field

from ai.core.llm_client import SHORT_CACHE_TTL_SECONDS, get_llm
from ai.core.prompt_safety import wrap_untrusted

PROMPTS_DIR = Path(__file__).resolve().parent.parent / "prompts"


class DashboardStats(BaseModel):
    """대시보드 현황 데이터 (브리핑 입력) — 값은 백엔드 집계 실측치."""
    total_facilities: int  # 전체 시설물 수
    monthly_analysis: int  # 이번 달 분석 장수
    pending_review: int  # 검수 대기 건수
    pending_action: int  # 조치 대기 건수
    this_week_defects: int  # 이번 주 등록 하자
    last_week_defects: int  # 지난 주 등록 하자 (변화율 계산용)
    top_defect_type: str  # 주요 발생 유형
    critical_defects: int  # D등급 이상 중대 결함 건수
    grade_distribution: dict[str, int] = Field(default_factory=dict)  # 등급별 건수 A~E (선택)


class BriefingFacts(BaseModel):
    """코드로 계산한 파생 사실 — LLM 프롬프트 주입 + 프론트 배지(예: '12% 감소')용."""
    this_week_defects: int
    last_week_defects: int
    change_pct: int | None  # 전주 대비 변화율(절대값 %). 지난 주 0이면 None
    trend: str  # 감소 | 증가 | 유지
    top_defect_type: str
    critical_defects: int


class WeeklyBriefing(BaseModel):
    """LLM 자연어 출력 — structured output (자유 텍스트 파싱 금지, AI_개발_컨벤션 §4)."""
    briefing: str  # 카드 본문 (2~4문장, 제공 수치만 사용)
    recommendation: str  # 권고 조치 (1~2문장)


def derive_facts(stats: DashboardStats) -> BriefingFacts:
    """전주 대비 변화율·추세를 코드로 계산 (LLM에 맡기지 않음)."""
    delta = stats.this_week_defects - stats.last_week_defects
    change_pct = round(abs(delta) / stats.last_week_defects * 100) if stats.last_week_defects > 0 else None
    trend = "유지" if delta == 0 else ("증가" if delta > 0 else "감소")
    return BriefingFacts(
        this_week_defects=stats.this_week_defects,
        last_week_defects=stats.last_week_defects,
        change_pct=change_pct,
        trend=trend,
        top_defect_type=stats.top_defect_type,
        critical_defects=stats.critical_defects,
    )


def _change_text(facts: BriefingFacts) -> str:
    if facts.change_pct is None:
        return f"{facts.trend}(전주 데이터 없음)"
    if facts.change_pct == 0:
        return "유지"
    return f"{facts.change_pct}% {facts.trend}"


def _build_prompt(stats: DashboardStats, facts: BriefingFacts) -> str:
    system = (PROMPTS_DIR / "_system_base.md").read_text(encoding="utf-8")
    template = (PROMPTS_DIR / "dashboard_briefing.md").read_text(encoding="utf-8")
    filled = template.format(
        total_facilities=stats.total_facilities,
        monthly_analysis=stats.monthly_analysis,
        pending_review=stats.pending_review,
        pending_action=stats.pending_action,
        this_week_defects=stats.this_week_defects,
        last_week_defects=stats.last_week_defects,
        change_text=_change_text(facts),
        trend=facts.trend,
        # top_defect_type은 집계 파이프라인상 사용자 입력(하자 유형 문자열)에서 유래하는 유일한
        # 자유 문자열 필드라 wrap_untrusted로 감싼다 — 나머지 필드는 전부 코드가 계산한 수치라
        # 마커가 필요 없다(PR머신 검수 P3: 마커 없이 직삽입되던 방어 구멍).
        top_defect_type_text=wrap_untrusted(stats.top_defect_type),
        critical_defects=stats.critical_defects,
    )
    return f"{system}\n\n{filled}"


def run_briefing_chain(stats: DashboardStats) -> tuple[WeeklyBriefing, BriefingFacts]:
    """현황 데이터로 주간 브리핑 생성. (브리핑 자연어, 코드 계산 파생사실) 반환."""
    facts = derive_facts(stats)
    prompt = _build_prompt(stats, facts)
    # stats(회사 현황 수치·주요 하자유형)가 프롬프트에 섞이므로 캐시 TTL을 짧게 둔다(#623 P2 픽스).
    briefing = get_llm().with_structured_output(WeeklyBriefing, ttl=SHORT_CACHE_TTL_SECONDS).invoke(prompt)
    return briefing, facts
