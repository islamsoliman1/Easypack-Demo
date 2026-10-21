import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useQuery } from "@tanstack/react-query";
import { api } from '../api';
import { useAuth } from '../access';
export type Notification = {
    id: string;
    type: string;
    severity: 'INFO' | 'WARNING' | 'URGENT';
    title: string;
    body: string;
    createdAt: string;
    readAt: string | null;
    target: string;
    orderCode: string;
    sample: boolean;
};
export type NotificationPage = {
    items: Notification[];
    nextCursor: string | null;
};
type Context = {
    key: readonly string[];
    count: number;
    countError: unknown;
    readError: unknown;
    failedRead: string | null;
    markRead: (id: string) => Promise<void>;
    refresh: () => Promise<void>;
};
const Notifications = createContext<Context | null>(null);
export function NotificationProvider({ children }: {
    children: ReactNode;
}) {
    const { user } = useAuth();
    const client = useQueryClient();
    const permissions = [...user.permissions].sort().join(',');
    const key = ['notifications', user.id, permissions] as const;
    const [failure, setFailure] = useState<{
        id: string;
        error: unknown;
    } | null>(null);
    const refresh = useCallback(async () => { await client.invalidateQueries({ queryKey: ['notifications', user.id] }); }, [client, user.id]);
    const count = useQuery({ queryKey: [...key, 'count'], queryFn: () => api<{
            count: number;
        }>('/notifications/unread-count') });
    useEffect(() => {
        // One foreground timer refreshes every mounted inbox/count observer.
        const update = () => { if (document.visibilityState === 'visible' && navigator.onLine)
            void refresh(); };
        const timer = window.setInterval(update, 30000);
        window.addEventListener('focus', update);
        window.addEventListener('online', update);
        window.addEventListener('workflow-saved', update);
        document.addEventListener('visibilitychange', update);
        return () => { window.clearInterval(timer); window.removeEventListener('focus', update); window.removeEventListener('online', update); window.removeEventListener('workflow-saved', update); document.removeEventListener('visibilitychange', update); };
    }, [refresh]);
    useEffect(() => {
        setFailure(null);
        client.removeQueries({ predicate: q => q.queryKey[0] === 'notifications' && (q.queryKey[1] !== user.id || q.queryKey[2] !== permissions) });
    }, [client, user.id, permissions]);
    const markRead = async (id: string) => {
        try {
            await api(`/notifications/${id === 'all' ? 'read-all' : `${id}/read`}`, 'POST');
            setFailure(null);
            await refresh();
        }
        catch (error) {
            setFailure({ id, error });
        }
    };
    return <Notifications.Provider value={{ key, count: count.data?.count ?? 0, countError: count.error, readError: failure?.error, failedRead: failure?.id ?? null, markRead, refresh }}>{children}</Notifications.Provider>;
}
export function useNotifications() { const value = useContext(Notifications); if (!value)
    throw new Error('NotificationProvider is required'); return value; }
