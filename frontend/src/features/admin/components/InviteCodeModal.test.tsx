// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { InviteCodeModal } from './InviteCodeModal';
import { useInviteCode } from '../hooks/useInviteCode';

// PR머신 후속(#809) P2 — "복사→전달→닫기" 흐름에서 이미 복사한 코드가 닫기로 즉시 폐기되면 안 된다.
// 실제 API/react-query 대신 useInviteCode 훅 자체를 모킹해 이 계약만 가볍게 고정한다.
vi.mock('../hooks/useInviteCode');

const mockedUseInviteCode = vi.mocked(useInviteCode);

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

function setUp(revokeInviteCode = vi.fn()) {
  const issueInviteCode = vi.fn().mockResolvedValue({ code: '7B2-W9A', ttlSeconds: 180 });
  mockedUseInviteCode.mockReturnValue({
    issueInviteCode,
    isIssuing: false,
    issueError: null,
    revokeInviteCode,
  });
  const onClose = vi.fn();
  // useNavigate(#857 업그레이드 CTA)를 쓰므로 Router 컨텍스트가 필요하다.
  render(
    <MemoryRouter>
      <InviteCodeModal open onClose={onClose} />
    </MemoryRouter>,
  );
  return { issueInviteCode, revokeInviteCode, onClose };
}

// #857 — 좌석 만석으로 발급 자체가 실패하는 경우. issueInviteCode가 reject되므로 code는 끝까지 빈 값.
function setUpSeatQuotaExceeded() {
  const issueInviteCode = vi.fn().mockRejectedValue({
    code: 'PLAN_SEAT_QUOTA_EXCEEDED',
    message: '요금제의 좌석 한도를 초과했습니다. 요금제를 업그레이드해 주세요.',
    status: 403,
  });
  mockedUseInviteCode.mockReturnValue({
    issueInviteCode,
    isIssuing: false,
    issueError: {
      code: 'PLAN_SEAT_QUOTA_EXCEEDED',
      message: '요금제의 좌석 한도를 초과했습니다. 요금제를 업그레이드해 주세요.',
      status: 403,
    },
    revokeInviteCode: vi.fn(),
  });
  const onClose = vi.fn();
  render(
    <MemoryRouter>
      <InviteCodeModal open onClose={onClose} />
    </MemoryRouter>,
  );
  return { onClose };
}

describe('InviteCodeModal', () => {
  it('한 번도 복사하지 않고 닫으면 발급된 코드를 폐기한다', async () => {
    const { revokeInviteCode, onClose } = setUp();
    await waitFor(() => expect(screen.getByText('7B2-W9A')).toBeTruthy());

    fireEvent.click(screen.getByRole('button', { name: '닫기' }));

    expect(revokeInviteCode).toHaveBeenCalledWith('7B2-W9A');
    expect(onClose).toHaveBeenCalled();
  });

  it('복사한 뒤 닫으면 코드를 폐기하지 않는다(비동기 전달 흐름 지원)', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.assign(navigator, { clipboard: { writeText } });
    const { revokeInviteCode, onClose } = setUp();
    await waitFor(() => expect(screen.getByText('7B2-W9A')).toBeTruthy());

    fireEvent.click(screen.getByRole('button', { name: /코드 복사하기/ }));
    await waitFor(() => expect(writeText).toHaveBeenCalledWith('7B2-W9A'));

    fireEvent.click(screen.getByRole('button', { name: '닫기' }));

    expect(revokeInviteCode).not.toHaveBeenCalled();
    expect(onClose).toHaveBeenCalled();
  });

  // #857 — 좌석 잔여 선검사 실패 시 일반 오류(재시도 유도)와 구분해 업그레이드 CTA를 보여줘야 한다.
  it('좌석 한도 초과(PLAN_SEAT_QUOTA_EXCEEDED)면 업그레이드 안내와 CTA를 보여준다', async () => {
    setUpSeatQuotaExceeded();

    await waitFor(() => expect(screen.getByRole('alert')).toBeTruthy());

    expect(screen.getByText(/좌석이 모두 사용 중입니다/)).toBeTruthy();
    expect(screen.getByRole('button', { name: '플랜 업그레이드' })).toBeTruthy();
    // 코드 자체가 생성되지 않았으므로 복사 버튼은 비활성 상태여야 한다.
    const copyButton = screen.getByRole('button', { name: /코드 복사하기/ }) as HTMLButtonElement;
    expect(copyButton.disabled).toBe(true);
  });

  it('좌석 한도 초과가 아닌 다른 발급 실패는 기존처럼 재시도 문구를 보여준다', async () => {
    const issueInviteCode = vi.fn().mockRejectedValue({
      code: 'INTERNAL_ERROR',
      message: '일시적인 오류가 발생했습니다',
      status: 500,
    });
    mockedUseInviteCode.mockReturnValue({
      issueInviteCode,
      isIssuing: false,
      issueError: { code: 'INTERNAL_ERROR', message: '일시적인 오류가 발생했습니다', status: 500 },
      revokeInviteCode: vi.fn(),
    });
    render(
      <MemoryRouter>
        <InviteCodeModal open onClose={vi.fn()} />
      </MemoryRouter>,
    );

    expect(await screen.findByText(/일시적인 오류가 발생했습니다 · 다시 시도/)).toBeTruthy();
    expect(screen.queryByRole('button', { name: '플랜 업그레이드' })).toBeNull();
  });
});
