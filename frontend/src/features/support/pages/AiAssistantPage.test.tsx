// @vitest-environment jsdom
// AiAssistantPage 통합 테스트 — 빈 상태 예시 질문 칩(오분류 방지 UX 개선).
// 실제 useRagChat 훅 + MSW supportHandlers를 통해 클릭 시 실제 전송까지 검증한다.
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { supportHandlers } from '../api/supportApi.handlers';
import { mockRagAnswer } from '../mocks/support.mock';
import { AiAssistantPage } from './AiAssistantPage';

const server = setupServer(...supportHandlers);

// jsdom은 Element.scrollIntoView를 구현하지 않는다 — 메시지 로그 자동 스크롤 useEffect가 던지는
// TypeError를 막기 위한 테스트 환경 전용 스텁(실제 스크롤 동작과 무관).
Element.prototype.scrollIntoView = () => {};

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
beforeEach(() => {
  // RAG 세션 localStorage는 useRagChat의 SoT(HAJA-668) — 테스트 간 오염 방지를 위해 매번 비운다.
  localStorage.clear();
});
afterEach(() => {
  server.resetHandlers();
  cleanup();
});
afterAll(() => server.close());

describe('AiAssistantPage (통합 테스트)', () => {
  it('빈 상태에서 예시 질문 칩 3개를 보여준다', () => {
    render(<AiAssistantPage />);

    expect(screen.getByText('안전점검의 종류에는 어떤 것들이 있나요?')).toBeTruthy();
    expect(screen.getByText('정밀안전진단은 언제 실시하나요?')).toBeTruthy();
    expect(screen.getByText('안전등급은 어떻게 나뉘나요?')).toBeTruthy();
  });

  it('예시 질문 칩을 클릭하면 그 질문이 그대로 전송되어 응답이 렌더된다', async () => {
    render(<AiAssistantPage />);

    fireEvent.click(screen.getByText('안전점검의 종류에는 어떤 것들이 있나요?'));

    expect(screen.getByText('안전점검의 종류에는 어떤 것들이 있나요?')).toBeTruthy();
    expect(await screen.findByText(mockRagAnswer.answer)).toBeTruthy();
  });

  it('참고문서는 기본 접힘 상태이고, 토글을 누르면 개별 출처 칩이 펼쳐진다', async () => {
    render(<AiAssistantPage />);

    fireEvent.click(screen.getByText('안전점검의 종류에는 어떤 것들이 있나요?'));
    await screen.findByText(mockRagAnswer.answer);

    const toggle = screen.getByText(`참고문서 ${mockRagAnswer.sources.length}건`);
    expect(toggle).toBeTruthy();
    expect(
      screen.queryByText(`${mockRagAnswer.sources[0].title} ${mockRagAnswer.sources[0].locator}`),
    ).toBeNull();

    fireEvent.click(toggle);

    expect(
      screen.getByText(`${mockRagAnswer.sources[0].title} ${mockRagAnswer.sources[0].locator}`),
    ).toBeTruthy();
  });

  // #1700 완료 기준 ③: 칩 펼침 시 근거 발췌(snippet) 미리보기가 노출된다. 접힌 상태에선 안 보인다.
  it('참고문서 칩 펼침 시 snippet 미리보기가 노출된다', async () => {
    render(<AiAssistantPage />);

    fireEvent.click(screen.getByText('안전점검의 종류에는 어떤 것들이 있나요?'));
    await screen.findByText(mockRagAnswer.answer);

    const snippet = mockRagAnswer.sources[0].snippet as string;
    const toggle = screen.getByText(`참고문서 ${mockRagAnswer.sources.length}건`);
    expect(screen.queryByText(snippet)).toBeNull();

    fireEvent.click(toggle);

    expect(screen.getByText(snippet)).toBeTruthy();
  });

  // HAJA-668(#1548): "새 대화 시작" 버튼 — 대화가 없으면 비활성, 대화 후 클릭하면 초기화된다.
  it('새 대화 시작 버튼: 대화가 없으면 비활성이고, 대화 후 클릭하면 메시지가 비워지고 예시 질문이 다시 보인다', async () => {
    render(<AiAssistantPage />);

    const newChatButton = screen.getByRole('button', { name: '새 대화 시작' });
    expect(newChatButton.hasAttribute('disabled')).toBe(true);

    fireEvent.click(screen.getByText('안전점검의 종류에는 어떤 것들이 있나요?'));
    await screen.findByText(mockRagAnswer.answer);

    expect(newChatButton.hasAttribute('disabled')).toBe(false);

    fireEvent.click(newChatButton);

    expect(screen.queryByText(mockRagAnswer.answer)).toBeNull();
    expect(screen.getByText('안전점검의 종류에는 어떤 것들이 있나요?')).toBeTruthy();
  });
});
