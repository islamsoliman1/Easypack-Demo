import { translateUi, formatDisplay, useLanguage } from '../i18n';
import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '../localizedQuery';
import { Bell, X } from 'lucide-react';
import { api } from '../api';
import { Busy, QueryError } from '../ui';
import { NotificationItems } from './NotificationItems';
import { useNotifications, type NotificationPage } from './useNotifications';

export function NotificationBell() {
  useLanguage();
  const { key, count, countError, readError, failedRead, markRead, refresh } = useNotifications();
  const [open, setOpen] = useState(false); const trigger = useRef<HTMLButtonElement>(null); const panel = useRef<HTMLDivElement>(null);
  const query = useQuery({ queryKey: [...key, 'recent'], queryFn: () => api<NotificationPage>('/notifications?limit=10'), enabled: open });
  const close = () => { setOpen(false); trigger.current?.focus(); };
  useEffect(() => { if (open) panel.current?.focus(); }, [open]);
  return <div className="notification-bell">
    <button ref={trigger} className="icon-button" aria-label={translateUi('الإشعارات، {{count}} غير مقروء', { count })} aria-expanded={open} aria-controls="notification-popover" onClick={() => open ? close() : setOpen(true)}><Bell size={19} />{count > 0 && <span className="notification-count" aria-hidden="true">{count > 99 ? `${formatDisplay(99)}+` : formatDisplay(count)}</span>}</button>
    {countError ? <button className="quiet" onClick={() => void refresh()}>{translateUi('تعذر تحديث الإشعارات · إعادة المحاولة')}</button> : null}
    {readError && failedRead ? <span role="alert" className="notification-retry">{translateUi('تعذر تحديد الإشعار كمقروء')} <button onClick={() => void markRead(failedRead)}>{translateUi('إعادة المحاولة')}</button></span> : null}
    {open && <div id="notification-popover" ref={panel} className="notification-popover" role="region" aria-label={translateUi('آخر الإشعارات')} tabIndex={-1} onKeyDown={e => { if (e.key === 'Escape') { e.stopPropagation(); close(); } }}>
      <div className="notification-heading"><h2>{translateUi('الإشعارات')}</h2><button className="icon-button" aria-label={translateUi('إغلاق الإشعارات')} onClick={close}><X size={18} /></button></div>
      <p className="notification-shared" role="note">{translateUi('الإشعارات وحالة القراءة مشتركة بين جميع زوار الديمو.')}</p>
      <button className="quiet" disabled={!count} onClick={() => void markRead('all')}>{translateUi('تحديد الكل كمقروء')}</button>
      {query.isPending ? <Busy /> : query.error ? <QueryError error={query.error} retry={() => void query.refetch()} /> : <NotificationItems items={query.data?.items ?? []} close={close} />}
      <Link className="inline-link" to="/notifications" onClick={close}>{translateUi('عرض كل الإشعارات')}</Link>
    </div>}
  </div>;
}
