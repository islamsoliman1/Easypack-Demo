import { useState } from 'react';
import { useInfiniteQuery } from '@tanstack/react-query';
import { api } from '../api';
import { Busy, QueryError } from '../ui';
import { NotificationItems, severities } from './NotificationItems';
import { useNotifications, type NotificationPage } from './useNotifications';
export default function NotificationsPage() {
    const { key, count, markRead, refresh } = useNotifications();
    const [unread, setUnread] = useState(false);
    const [severity, setSeverity] = useState('');
    const query = useInfiniteQuery({ queryKey: [...key, 'inbox', unread, severity], initialPageParam: null as string | null,
        queryFn: ({ pageParam }) => api<NotificationPage>(`/notifications?limit=20&unreadOnly=${unread}${severity ? `&severity=${severity}` : ''}${pageParam ? `&cursor=${encodeURIComponent(pageParam)}` : ''}`),
        getNextPageParam: page => page.nextCursor ?? undefined });
    return <section className="notifications-page"><div className="page-heading"><h1>{'الإشعارات'}</h1><button disabled={!count} onClick={() => void markRead('all')}>{'تحديد الكل كمقروء'}</button></div>
    <p className="notification-shared" role="note">{'الإشعارات وحالة القراءة مشتركة بين جميع زوار الديمو.'}</p>
    <div className="toolbar"><label className="check-line"><input type="checkbox" checked={unread} onChange={e => setUnread(e.target.checked)}/>{'غير المقروء فقط'}</label>
      <select aria-label={'أهمية الإشعار'} value={severity} onChange={e => setSeverity(e.target.value)}><option value="">{'كل الأهميات'}</option>{Object.entries(severities).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select>
      <button onClick={() => void refresh()} disabled={query.isFetching}>{'تحديث الإشعارات'}</button></div>
    {query.isPending ? <Busy /> : <>{query.error ? <QueryError error={query.error} retry={() => void query.refetch()}/> : null}<NotificationItems items={query.data?.pages.flatMap(page => page.items) ?? []}/>{query.hasNextPage && <button disabled={query.isFetchingNextPage} onClick={() => void query.fetchNextPage()}>{'تحميل المزيد'}</button>}</>}
  </section>;
}
