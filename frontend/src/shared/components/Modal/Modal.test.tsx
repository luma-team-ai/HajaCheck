// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { useState } from 'react';
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

  it('열릴 때 모달 내부(첫 포커스 가능 요소)로 포커스가 이동하고, 닫히면 트리거 요소로 복귀한다', () => {
    function Wrapper() {
      const [open, setOpen] = useState(false);
      return (
        <div>
          <button onClick={() => setOpen(true)}>열기</button>
          <Modal open={open} onClose={() => setOpen(false)}>
            <button>확인</button>
          </Modal>
        </div>
      );
    }

    render(<Wrapper />);

    const trigger = screen.getByText('열기');
    trigger.focus();
    expect(document.activeElement).toBe(trigger);

    fireEvent.click(trigger);

    const confirmButton = screen.getByText('확인');
    expect(document.activeElement).toBe(confirmButton);

    fireEvent.keyDown(document, { key: 'Escape' });

    expect(document.activeElement).toBe(trigger);
  });

  it('Tab 키는 마지막 포커스 가능 요소에서 첫 요소로 순환한다(포커스 트랩)', () => {
    render(
      <Modal open onClose={vi.fn()}>
        <button>첫번째</button>
        <button>마지막</button>
      </Modal>,
    );

    const first = screen.getByText('첫번째');
    const last = screen.getByText('마지막');

    last.focus();
    expect(document.activeElement).toBe(last);

    fireEvent.keyDown(document, { key: 'Tab' });

    expect(document.activeElement).toBe(first);
  });

  it('Shift+Tab 키는 첫 포커스 가능 요소에서 마지막 요소로 순환한다(포커스 트랩)', () => {
    render(
      <Modal open onClose={vi.fn()}>
        <button>첫번째</button>
        <button>마지막</button>
      </Modal>,
    );

    const first = screen.getByText('첫번째');
    const last = screen.getByText('마지막');

    first.focus();
    expect(document.activeElement).toBe(first);

    fireEvent.keyDown(document, { key: 'Tab', shiftKey: true });

    expect(document.activeElement).toBe(last);
  });
});
