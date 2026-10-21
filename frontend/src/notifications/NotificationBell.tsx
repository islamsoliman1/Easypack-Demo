import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from "@tanstack/react-query";
import { Bell, X } from 'lucide-react';
import { api } from '../api';
import { Busy, QueryError } from '../ui';
import { NotificationItems } from './NotificationItems';
import { useNotifications, type NotificationPage } from './useNotifications';
export function NotificationBell() {
    const { key, count, countError, readError, failedRead, markRead, refresh } = useNotifications();
    const [open, setOpen] = useState(false);
    const trigger = useRef<HTMLButtonElement>(null);
    const panel = useRef<HTMLDivElement>(null);
    const query = useQuery({ queryKey: [...key, 'recent'], queryFn: () => api<NotificationPage>('/notifications?limit=10'), enabled: open });
    const close = () => { setOpen(false); trigger.current?.focus(); };
    useEffect(() => { if (open)
        panel.current?.focus(); }, [open]);
    return <div className="notification-bell">
    <button ref={trigger} className="icon-button" aria-label={"\u0627\u0644\u0625\u0634\u0639\u0627\u0631\u0627\u062A\u060C " + count + " \u063A\u064A\u0631 \u0645\u0642\u0631\u0648\u0621"} aria-expanded={open} aria-controls="notification-popover" onClick={() => open ? close() : setOpen(true)}><Bell size={19}/>{count > 0 && <span className="notification-count" aria-hidden="true">{count > 99 ? `${99}+` : count}</span>}</button>
    {countError ? <button className="quiet" onClick={() => void refresh()}>{'تعذر تحديث الإشعارات · إعادة المحاولة'}</button> : null}
    {readError && failedRead ? <span role="alert" className="notification-retry">{'تعذر تحديد الإشعار كمقروء'} <button onClick={() => void markRead(failedRead)}>{'إعادة المحاولة'}</button></span> : null}
    {open && <div id="notification-popover" ref={panel} className="notification-popover" role="region" aria-label={'آخر الإشعارات'} tabIndex={-1} onKeyDown={e => { if (e.key === 'Escape') {
            e.stopPropagation();
            close();
        } }}>
      <div className="notification-heading"><h2>{'الإشعارات'}</h2><button className="icon-button" aria-label={'إغلاق الإشعارات'} onClick={close}><X size={18}/></button></div>
      <p className="notification-shared" role="note">{'الإشعارات وحالة القراءة مشتركة بين جميع زوار الديمو.'}</p>
      <button className="quiet" disabled={!count} onClick={() => void markRead('all')}>{'تحديد الكل كمقروء'}</button>
      {query.isPending ? <Busy /> : query.error ? <QueryError error={query.error} retry={() => void query.refetch()}/> : <NotificationItems items={query.data?.items ?? []} close={close}/>}
      <Link className="inline-link" to="/notifications" onClick={close}>{'عرض كل الإشعارات'}</Link>
    </div>}
  </div>;
}
