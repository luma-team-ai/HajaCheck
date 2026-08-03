// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { AddSectionMenu } from './AddSectionMenu';

afterEach(() => cleanup());

describe('AddSectionMenu — 위치도ㆍ전경 사진ㆍ종ㆍ평면도ㆍ현황도 제목 선택', () => {
  it('실 서식처럼 뭉뚱그린 제목이 아니라 페이지별 제목을 골라 추가한다', () => {
    const handleAdd = vi.fn();
    render(<AddSectionMenu existingTypes={[]} onAdd={handleAdd} />);

    fireEvent.click(screen.getByRole('button', { name: '+ 서식 섹션 추가' }));
    fireEvent.click(screen.getByRole('button', { name: '위치도ㆍ전경 사진ㆍ종ㆍ평면도ㆍ현황도' }));

    fireEvent.change(screen.getByLabelText('섹션 제목 선택'), { target: { value: '전경 사진(1)' } });
    fireEvent.click(screen.getByRole('button', { name: '추가' }));

    expect(handleAdd).toHaveBeenCalledWith('location-drawing-photos', '전경 사진(1)');
  });

  it('직접 입력을 고르면 커스텀 제목으로 추가하고, 비어 있으면 추가 버튼이 비활성화된다', () => {
    const handleAdd = vi.fn();
    render(<AddSectionMenu existingTypes={[]} onAdd={handleAdd} />);

    fireEvent.click(screen.getByRole('button', { name: '+ 서식 섹션 추가' }));
    fireEvent.click(screen.getByRole('button', { name: '위치도ㆍ전경 사진ㆍ종ㆍ평면도ㆍ현황도' }));
    fireEvent.change(screen.getByLabelText('섹션 제목 선택'), { target: { value: '직접 입력' } });

    const confirmButton = screen.getByRole('button', { name: '추가' }) as HTMLButtonElement;
    expect(confirmButton.disabled).toBe(true);

    fireEvent.change(screen.getByLabelText('섹션 제목 직접 입력'), { target: { value: '종단면도' } });
    expect(confirmButton.disabled).toBe(false);
    fireEvent.click(confirmButton);

    expect(handleAdd).toHaveBeenCalledWith('location-drawing-photos', '종단면도');
  });

  it('이미 추가된 타입이어도 위치도ㆍ전경 사진 계열은 다시 추가할 수 있다(실 서식은 여러 장)', () => {
    render(<AddSectionMenu existingTypes={['location-drawing-photos', 'submission']} onAdd={vi.fn()} />);

    fireEvent.click(screen.getByRole('button', { name: '+ 서식 섹션 추가' }));

    expect(screen.getByRole('button', { name: '위치도ㆍ전경 사진ㆍ종ㆍ평면도ㆍ현황도' })).toBeTruthy();
    // 제출문처럼 1회만 두는 타입은 여전히 목록에서 빠진다.
    expect(screen.queryByRole('button', { name: '제출문' })).toBeNull();
  });

  it('다른 서식 타입(제출문 등)은 기존처럼 클릭 즉시 title 없이 추가된다', () => {
    const handleAdd = vi.fn();
    render(<AddSectionMenu existingTypes={[]} onAdd={handleAdd} />);

    fireEvent.click(screen.getByRole('button', { name: '+ 서식 섹션 추가' }));
    fireEvent.click(screen.getByRole('button', { name: '제출문' }));

    expect(handleAdd).toHaveBeenCalledWith('submission');
  });
});
