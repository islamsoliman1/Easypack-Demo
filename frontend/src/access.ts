import { createContext, useContext } from 'react';
import type { User } from './api';

export const Auth = createContext<{ user: User; reload: () => Promise<void>; exit: () => Promise<void> } | null>(null);
export function useAuth() { return useContext(Auth)!; }
export const can = (user: User, permission: string) => user.permissions.includes(permission);
