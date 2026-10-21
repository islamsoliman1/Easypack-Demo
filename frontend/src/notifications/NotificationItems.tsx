import { Link } from 'react-router-dom';
import { useNotifications, type Notification } from './useNotifications';
export const severities = { INFO: 'معلومة', WARNING: 'تنبيه', URGENT: 'عاجل' };
const date = new Intl.DateTimeFormat("ar-EG", { dateStyle: 'short', timeStyle: 'short', timeZone: 'Africa/Cairo' });
export function NotificationItems({ items, close }: {
    items: Notification[];
    close?: () => void;
}) {
    const { markRead } = useNotifications();
    if (!items.length)
        return <p className="state">{'لا توجد إشعارات'}</p>;
    return <ul className="notification-items">{items.map(item => <li key={item.id} className={item.readAt ? '' : 'notification-unread'}>
    <Link to={item.target} onClick={() => { close?.(); if (!item.readAt)
            void markRead(item.id); }}>
      <span className={`badge notification-${item.severity.toLowerCase()}`}>{severities[item.severity]}</span>
      {item.sample && <span className="notification-sample">{'بيانات عرض توضيحية'}</span>}
      <strong>{item.title}</strong><span>{"\u0637\u0644\u0628 \u0627\u0644\u062A\u0635\u0646\u064A\u0639 " + item.orderCode}</span>
      {item.sample && <small>{'لقطة عند التهيئة؛ افتح السجل للاطلاع على حالته الحالية.'}</small>}
      <time dateTime={item.createdAt}>{date.format(new Date(item.createdAt))} · {'القاهرة'}</time>
    </Link>
    {!item.readAt && <button className="quiet" onClick={() => void markRead(item.id)}>{'تحديد كمقروء'}</button>}
  </li>)}</ul>;
}
