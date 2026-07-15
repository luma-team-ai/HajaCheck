import type { ReactNode } from 'react';

export interface TableColumn<T> {
  key: keyof T & string;
  header: string;
  render?: (row: T) => ReactNode;
}

interface TableProps<T extends { id: string | number }> {
  columns: TableColumn<T>[];
  data: T[];
  emptyMessage?: string;
}

export function Table<T extends { id: string | number }>({
  columns,
  data,
  emptyMessage = '데이터가 없습니다',
}: TableProps<T>) {
  return (
    <table className="w-full border-collapse text-sm">
      <thead>
        <tr>
          {columns.map((column) => (
            <th
              key={column.key}
              className="border-b border-neutral-100 bg-surface-muted px-4 py-3 text-left font-semibold text-text-default"
            >
              {column.header}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {data.length === 0 ? (
          <tr>
            <td className="px-4 py-8 text-center text-[#999]" colSpan={columns.length}>
              {emptyMessage}
            </td>
          </tr>
        ) : (
          data.map((row) => (
            <tr key={row.id}>
              {columns.map((column) => (
                <td key={column.key} className="border-b border-neutral-100 px-4 py-3">
                  {column.render ? column.render(row) : String(row[column.key] ?? '')}
                </td>
              ))}
            </tr>
          ))
        )}
      </tbody>
    </table>
  );
}
