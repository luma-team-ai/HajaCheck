interface ChartEmptyStateProps {
  ariaLabel: string;
  height: number;
  message: string;
}

export function ChartEmptyState({ ariaLabel, height, message }: ChartEmptyStateProps) {
  return (
    <div
      className="flex w-full items-center justify-center rounded-lg border border-dashed border-border bg-surface-muted px-4 text-sm text-text-muted"
      style={{ height }}
      role="status"
      aria-label={ariaLabel}
    >
      {message}
    </div>
  );
}
