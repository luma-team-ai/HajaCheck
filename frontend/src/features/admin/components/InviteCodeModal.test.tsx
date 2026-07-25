import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
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
  render(<InviteCodeModal open onClose={onClose} />);
  return { issueInviteCode, revokeInviteCode, onClose };
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
});
