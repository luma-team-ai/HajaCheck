// @vitest-environment jsdom
// AiAssistantPage 통합 테스트 — 빈 상태 예시 질문 칩(오분류 방지 UX 개선).
// 실제 useRagChat 훅 + MSW supportHandlers를 통해 클릭 시 실제 전송까지 검증한다.
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { setupServer } from 'msw/node';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';
import { supportHandlers } from '../api/supportApi.handlers';
import { mockRagAnswer } from '../mocks/support.mock';
import { AiAssistantPage } from './AiAssistantPage';

const server = setupServer(...supportHandlers);

// jsdom은 Element.scrollIntoView를 구현하지 않는다 — 메시지 로그 자동 스크롤 useEffect가 던지는
// TypeError를 막기 위한 테스트 환경 전용 스텁(실제 스크롤 동작과 무관).
Element.prototype.scrollIntoView = () => {};

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
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
});
