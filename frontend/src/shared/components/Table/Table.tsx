import type { ReactNode } from 'react';
import './Table.css';

export interface TableColumn<T> {
  key: string;
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
    <table className="table">
      <thead>
        <tr>
          {columns.map((column) => (
            <th key={column.key}>{column.header}</th>
          ))}
        </tr>
      </thead>
      <tbody>
        {data.length === 0 ? (
          <tr>
            <td className="table-empty" colSpan={columns.length}>
              {emptyMessage}
            </td>
          </tr>
        ) : (
          data.map((row) => (
            <tr key={row.id}>
              {columns.map((column) => (
                <td key={column.key}>
                  {column.render ? column.render(row) : String(row[column.key as keyof T] ?? '')}
                </td>
              ))}
            </tr>
          ))
        )}
      </tbody>
    </table>
  );
}
