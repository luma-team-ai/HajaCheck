// @vitest-environment jsdom
// RagAnswer 마크다운 렌더 테스트(#1700) — 답변 가독성 개선(현상 2) 검증.
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { RagAnswer } from './RagAnswer';

afterEach(() => cleanup());

describe('RagAnswer', () => {
  it('**볼드**를 <strong>으로 렌더한다', () => {
    render(<RagAnswer text="이것은 **중요한** 내용입니다." />);

    const strong = screen.getByText('중요한');
    expect(strong.tagName).toBe('STRONG');
  });

  it('- 리스트 항목을 <li>로 렌더한다', () => {
    render(<RagAnswer text={'항목 목록:\n- 첫 번째 항목\n- 두 번째 항목'} />);

    const first = screen.getByText('첫 번째 항목');
    const second = screen.getByText('두 번째 항목');
    expect(first.tagName).toBe('LI');
    expect(second.tagName).toBe('LI');
    expect(first.closest('ul')).not.toBeNull();
  });

  // 보안(필수, #1700): RAG 답변의 링크는 근거 문서 링크가 아니라 모델 환각일 수 있어 클릭 가능한
  // 앵커로 렌더하지 않는다 — 텍스트로만 노출되고 <a> 요소가 생성되지 않아야 한다.
  it('링크를 클릭 가능한 앵커로 렌더하지 않고 텍스트로 떨군다', () => {
    const { container } = render(
      <RagAnswer text="자세한 내용은 [여기](https://example.com/malicious)를 참고하세요." />,
    );

    // <a>가 생성되지 않고, 링크 텍스트("여기")는 일반 텍스트로 남아 있어야 한다.
    expect(screen.queryByRole('link')).toBeNull();
    expect(container.querySelector('a')).toBeNull();
    expect(container.textContent).toBe('자세한 내용은 여기를 참고하세요.');
  });

  // RAG_NO_RESULT_TEXT처럼 개행이 의미 있는 안내 문구가 마크다운 렌더에서 깨지지 않아야 한다
  // (useRagChat.ts RAG_NO_RESULT_TEXT와 동일한 형태로 검증).
  it('개행이 있는 안내 문구의 줄바꿈이 유지된다(whitespace-pre-wrap)', () => {
    const text =
      '이 질문엔 답변드리기 어려워요. 점검 기준·법규 관련 질문을 해주시면 도움드릴게요.\n(예: "정기안전점검은 얼마나 자주 하나요?")';
    render(<RagAnswer text={text} />);

    const paragraph = screen.getByText((_, element) => element?.tagName === 'P' && element.textContent === text);
    expect(paragraph).toBeTruthy();
    expect(paragraph.className).toContain('whitespace-pre-wrap');
  });
});
