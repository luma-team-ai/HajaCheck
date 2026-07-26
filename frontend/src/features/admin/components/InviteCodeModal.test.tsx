// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ApiError } from '../../../shared/api/types';
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

function setUp(revokeInviteCode = vi.fn(), issueError: ApiError | null = null) {
  const issueInviteCode = issueError
    ? vi.fn().mockRejectedValue(issueError)
    : vi.fn().mockResolvedValue({ code: '7B2-W9A', ttlSeconds: 180 });
  mockedUseInviteCode.mockReturnValue({
    issueInviteCode,
    isIssuing: false,
    issueError,
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

  // #872 — 발급 시점 좌석 한도 초과는 재시도가 무의미하므로 "다시 시도" 링크 없이 안내 문구만 보여준다.
  // 문구는 <br/>로 2줄 표시되므로(디자인 요청) 텍스트 노드가 쪼개진다 — 부모 요소 기준으로 확인한다.
  it('발급이 좌석 한도 초과로 실패하면 다시 시도 링크 없이 안내 문구만 2줄로 보여준다', async () => {
    setUp(vi.fn(), { code: 'PLAN_SEAT_QUOTA_EXCEEDED', message: '요금제의 좌석 한도를 초과했습니다. 요금제를 업그레이드해 주세요.' });

    const message = await screen.findByText((_, element) =>
      element?.tagName === 'P' && element.textContent === '요금제의 좌석 한도를 초과했습니다.요금제를 업그레이드해 주세요.',
    );
    expect(message.querySelector('br')).toBeTruthy();
    expect(screen.queryByRole('button', { name: /다시 시도/ })).toBeNull();
    expect(screen.queryByRole('link')).toBeNull();
  });

  it('발급이 그 외 사유로 실패하면 다시 시도 버튼을 보여준다', async () => {
    const { issueInviteCode } = setUp(vi.fn(), { code: 'INTERNAL_ERROR', message: '일시적인 오류가 발생했습니다' });

    const retry = await screen.findByRole('button', { name: /다시 시도/ });
    expect(screen.queryByRole('button', { name: /플랜 업그레이드/ })).toBeNull();

    fireEvent.click(retry);

    expect(issueInviteCode).toHaveBeenCalledTimes(2);
  });
});
