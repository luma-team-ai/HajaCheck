// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { useState } from 'react';
import { afterEach, describe, expect, it } from 'vitest';
import { InviteCodeInput } from './InviteCodeInput';

afterEach(cleanup);

function Controlled() {
  const [value, setValue] = useState('');
  return <InviteCodeInput value={value} onChange={setValue} />;
}

function getInputs() {
  return screen.getAllByLabelText(/초대 코드 \d번째 자리/) as HTMLInputElement[];
}

describe('InviteCodeInput', () => {
  it('한 칸에 입력하면 값이 대문자로 반영되고 다음 칸으로 포커스가 이동한다', () => {
    render(<Controlled />);
    const inputs = getInputs();

    fireEvent.change(inputs[0], { target: { value: 'a' } });

    expect(inputs[0].value).toBe('A');
    expect(document.activeElement).toBe(inputs[1]);
  });

  it('빈 칸에서 백스페이스를 누르면 이전 칸으로 이동해 값을 지운다', () => {
    render(<Controlled />);
    const inputs = getInputs();
    fireEvent.change(inputs[0], { target: { value: '1' } });
    fireEvent.change(inputs[1], { target: { value: '2' } });

    fireEvent.keyDown(inputs[2], { key: 'Backspace' });

    expect(document.activeElement).toBe(inputs[1]);
    expect(inputs[1].value).toBe('');
  });

  it('붙여넣기하면 6자리가 각 칸에 순서대로 채워진다', () => {
    render(<Controlled />);
    const inputs = getInputs();

    fireEvent.paste(inputs[0], {
      clipboardData: { getData: () => 'ab-c123' },
    });

    expect(inputs.map((input) => input.value)).toEqual(['A', 'B', 'C', '1', '2', '3']);
  });

  // #816 P2 회귀 방지 — 압축 문자열(join)을 매 렌더 6칸으로 재분해하던 이전 구현은, 가운데 칸을
  // 지워 join 결과가 짧아지면 그 문자열을 슬롯에 다시 채우는 과정에서 뒤 칸 값이 왼쪽으로 밀렸다.
  it('가운데 칸(index 2)을 채운 뒤 지우면 뒤 칸(index 3~5) 값이 이동하지 않는다', () => {
    render(<Controlled />);
    const inputs = getInputs();
    'ABCDEF'.split('').forEach((char, index) => {
      fireEvent.change(inputs[index], { target: { value: char } });
    });
    expect(inputs.map((input) => input.value)).toEqual(['A', 'B', 'C', 'D', 'E', 'F']);

    // 3번째 칸(index 2, 'C')을 직접 지운다 — 백스페이스 가드(빈 칸에서만 이전 칸 이동)가 아니라
    // 값이 있는 칸의 기본 삭제 경로(브라우저가 onChange('')를 발생)를 재현한다.
    fireEvent.change(inputs[2], { target: { value: '' } });

    expect(inputs.map((input) => input.value)).toEqual(['A', 'B', '', 'D', 'E', 'F']);
  });
});
