import type { ButtonHTMLAttributes, ReactNode } from 'react';

type ButtonVariant = 'primary' | 'secondary' | 'danger' | 'danger-soft';
type ButtonSize = 'sm' | 'md' | 'lg';

interface ButtonProps extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'type'> {
  children: ReactNode;
  variant?: ButtonVariant;
  size?: ButtonSize;
  disabled?: boolean;
  onClick?: () => void;
  type?: 'button' | 'submit';
}

const BASE_CLASSES =
  'inline-flex items-center justify-center gap-1.5 rounded-full font-semibold cursor-pointer transition duration-150 disabled:opacity-50 disabled:cursor-not-allowed enabled:hover:opacity-85';

const SIZE_CLASSES: Record<ButtonSize, string> = {
  sm: 'px-3 py-1.5 text-[13px]',
  md: 'px-[18px] py-2.5 text-sm',
  lg: 'px-6 py-3.5 text-base',
};

const VARIANT_CLASSES: Record<ButtonVariant, string> = {
  primary: 'bg-primary text-surface',
  secondary: 'bg-secondary-bg text-secondary-fg border border-border',
  danger: 'bg-danger text-surface',
  'danger-soft':
    'border border-danger-soft-border bg-danger-soft-bg text-text-default hover:bg-danger-soft-hover',
};

export function Button({
  children,
  variant = 'primary',
  size = 'md',
  disabled = false,
  onClick,
  type = 'button',
  className,
  ...rest
}: ButtonProps) {
  const classNames = [BASE_CLASSES, SIZE_CLASSES[size], VARIANT_CLASSES[variant], className]
    .filter(Boolean)
    .join(' ');

  return (
    <button
      type={type}
      className={classNames}
      disabled={disabled}
      aria-disabled={disabled}
      onClick={onClick}
      {...rest}
    >
      {children}
    </button>
  );
}
