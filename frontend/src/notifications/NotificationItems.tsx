import { translateUi, translateDisplay, useLanguage, dateFormatter } from '../i18n';
import { Link } from 'react-router-dom';
import { useNotifications, type Notification } from './useNotifications';

export const severities = { INFO: 'معلومة', WARNING: 'تنبيه', URGENT: 'عاجل' };
const date = dateFormatter( { dateStyle: 'short', timeStyle: 'short', timeZone: 'Africa/Cairo' });
export function NotificationItems({ items, close }: { items: Notification[]; close?: () => void }) {
  useLanguage();
  const { markRead } = useNotifications();
  if (!items.length) return <p className="state">{translateUi('لا توجد إشعارات')}</p>;
  return <ul className="notification-items">{items.map(item => <li key={item.id} className={item.readAt ? '' : 'notification-unread'}>
    <Link to={item.target} onClick={() => { close?.(); if (!item.readAt) void markRead(item.id); }}>
      <span className={`badge notification-${item.severity.toLowerCase()}`}>{translateUi(severities[item.severity])}</span>
      {item.sample && <span className="notification-sample">{translateUi('بيانات عرض توضيحية')}</span>}
      <strong>{translateDisplay(item.title)}</strong><span>{translateUi('طلب التصنيع {{code}}', { code: item.orderCode })}</span>
      {item.sample && <small>{translateUi('لقطة عند التهيئة؛ افتح السجل للاطلاع على حالته الحالية.')}</small>}
      <time dateTime={item.createdAt}>{date.format(new Date(item.createdAt))} · {translateUi('القاهرة')}</time>
    </Link>
    {!item.readAt && <button className="quiet" onClick={() => void markRead(item.id)}>{translateUi('تحديد كمقروء')}</button>}
  </li>)}</ul>;
}
