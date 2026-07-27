import { http, HttpResponse } from 'msw';
import type { ApiResponse } from '../../../shared/api/types';
import { mockMenuTree } from '../mocks/menu.mock';
import type { MenuTreeItem } from '../types';

export const menuHandlers = [
  http.get('/api/menus', () => {
    const body: ApiResponse<MenuTreeItem[]> = { success: true, data: mockMenuTree };
    return HttpResponse.json(body);
  }),
];
