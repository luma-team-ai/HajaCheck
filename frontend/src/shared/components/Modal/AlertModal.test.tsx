// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AlertModal } from './AlertModal';

afterEach(cleanup);

describe('AlertModal', () => {
  it('open=false이면 렌더되지 않는다', () => {
    render(<AlertModal open={false} message="메시지" onClose={vi.fn()} />);
    expect(screen.queryByText('메시지')).toBeNull();
  });

  it('open=true이면 제목과 메시지, 확인 버튼을 보여준다', () => {
    render(<AlertModal open title="알림 제목" message="알림 메시지 내용" onClose={vi.fn()} />);

    expect(screen.getByRole('dialog')).toBeTruthy();
    expect(screen.getByText('알림 제목')).toBeTruthy();
    expect(screen.getByText('알림 메시지 내용')).toBeTruthy();
    expect(screen.getByRole('button', { name: '확인' })).toBeTruthy();
  });

  it('확인 버튼 클릭 시 onClose가 호출된다', () => {
    const handleClose = vi.fn();
    render(<AlertModal open message="알림 메시지" onClose={handleClose} />);

    fireEvent.click(screen.getByRole('button', { name: '확인' }));

    expect(handleClose).toHaveBeenCalledTimes(1);
  });

  it('confirmLabel을 지정하면 버튼 라벨이 바뀐다', () => {
    render(<AlertModal open message="메시지" onClose={vi.fn()} confirmLabel="닫기" />);
    expect(screen.getByRole('button', { name: '닫기' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: '확인' })).toBeNull();
  });
});
