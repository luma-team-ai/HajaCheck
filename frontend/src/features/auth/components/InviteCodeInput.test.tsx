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
});
