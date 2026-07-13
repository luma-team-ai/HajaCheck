// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Modal } from './Modal';

afterEach(cleanup);

describe('Modal', () => {
  it('open=false이면 컨텐츠가 렌더되지 않는다', () => {
    render(
      <Modal open={false} onClose={vi.fn()}>
        모달 내용
      </Modal>,
    );

    expect(screen.queryByText('모달 내용')).toBeNull();
  });

  it('배경(overlay) 클릭 시 onClose가 호출된다', () => {
    const handleClose = vi.fn();
    render(
      <Modal open onClose={handleClose}>
        모달 내용
      </Modal>,
    );

    fireEvent.click(screen.getByRole('presentation'));

    expect(handleClose).toHaveBeenCalledTimes(1);
  });

  it('ESC 키 입력 시 onClose가 호출된다', () => {
    const handleClose = vi.fn();
    render(
      <Modal open onClose={handleClose}>
        모달 내용
      </Modal>,
    );

    fireEvent.keyDown(document, { key: 'Escape' });

    expect(handleClose).toHaveBeenCalledTimes(1);
  });

  it('모달 컨텐츠 클릭 시에는 onClose가 호출되지 않는다', () => {
    const handleClose = vi.fn();
    render(
      <Modal open onClose={handleClose}>
        모달 내용
      </Modal>,
    );

    fireEvent.click(screen.getByText('모달 내용'));

    expect(handleClose).not.toHaveBeenCalled();
  });
});
