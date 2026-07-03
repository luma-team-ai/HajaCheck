"""예시 체인 — 각 메뉴 담당은 이 파일을 복제해서 시작 (AI_개발_컨벤션.md §8)

절차:
1. 이 파일 복제 (예: defect_explain_chain.py)
2. 프롬프트 파일 작성 (ai/prompts/{기능}_{동작}.md)
3. 출력 Pydantic 스키마 정의
4. 체인 구현 -> ai_router.py 에 엔드포인트 등록 (공통 envelope 적용)
5. 자체 테스트: 정상 + 타임아웃 + 스키마 파싱 실패
6. PR -> 부담당 + AI-LLM 코치 리뷰
"""
from pydantic import BaseModel

from ai.core.llm_client import get_llm


class ExampleOutput(BaseModel):
    """LLM 응답은 structured output 으로만 수신 — 자유 텍스트 파싱 금지"""
    answer: str
    sources: list[str]


def run_example_chain(question: str) -> ExampleOutput:
    llm = get_llm()
    # TODO: prompts/ 파일 로드 -> _system_base.md 결합 -> with_structured_output(ExampleOutput)
    raise NotImplementedError
