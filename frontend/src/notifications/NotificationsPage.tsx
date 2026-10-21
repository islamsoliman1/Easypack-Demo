import { translateUi, translateDisplay, useLanguage, dateFormatter } from '../i18n';
import { useState } from 'react';
import { useInfiniteQuery } from '@tanstack/react-query';
import { api } from '../api';
import { Busy, QueryError } from '../ui';
import { NotificationItems, severities } from './NotificationItems';
import { useNotifications, type NotificationPage } from './useNotifications';

export default function NotificationsPage() {
  useLanguage();
  const { key, count, markRead, refresh } = useNotifications();
  const [unread, setUnread] = useState(false); const [severity, setSeverity] = useState('');
  const query = useInfiniteQuery({ queryKey: [...key, 'inbox', unread, severity], initialPageParam: null as string | null,
    queryFn: ({ pageParam }) => api<NotificationPage>(`/notifications?limit=20&unreadOnly=${unread}${severity ? `&severity=${severity}` : ''}${pageParam ? `&cursor=${encodeURIComponent(pageParam)}` : ''}`),
    getNextPageParam: page => page.nextCursor ?? undefined });
  return <section className="notifications-page"><div className="page-heading"><h1>{translateUi('الإشعارات')}</h1><button disabled={!count} onClick={() => void markRead('all')}>{translateUi('تحديد الكل كمقروء')}</button></div>
    <p className="notification-shared" role="note">{translateUi('الإشعارات وحالة القراءة مشتركة بين جميع زوار الديمو.')}</p>
    <div className="toolbar"><label className="check-line"><input type="checkbox" checked={unread} onChange={e => setUnread(e.target.checked)} />{translateUi('غير المقروء فقط')}</label>
      <select aria-label={translateUi('أهمية الإشعار')} value={severity} onChange={e => setSeverity(e.target.value)}><option value="">{translateUi('كل الأهميات')}</option>{Object.entries(severities).map(([value, label]) => <option key={value} value={value}>{translateUi(label)}</option>)}</select>
      <button onClick={() => void refresh()} disabled={query.isFetching}>{translateUi('تحديث الإشعارات')}</button></div>
    {query.isPending ? <Busy /> : <>{query.error ? <QueryError error={query.error} retry={() => void query.refetch()} /> : null}<NotificationItems items={query.data?.pages.flatMap(page => page.items) ?? []} />{query.hasNextPage && <button disabled={query.isFetchingNextPage} onClick={() => void query.fetchNextPage()}>{translateUi('تحميل المزيد')}</button>}</>}
  </section>;
}
