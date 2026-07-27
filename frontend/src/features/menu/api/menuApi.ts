import { api } from '../../../shared/api/axios';
import type { MenuTreeItem } from '../types';

export const menuApi = {
  getMenuTree: () => api.get<MenuTreeItem[]>('/menus'),
};
