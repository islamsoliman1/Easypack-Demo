import { useEffect, useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useQuery } from './localizedQuery';
import { MessageCircle, Send } from 'lucide-react';
import { api } from './api';
import { ResourceVisual } from './CatalogMedia';
import { useAuth } from './access';
import { dateFormatter, translateUi as t, useLanguage } from './i18n';
import { Busy, ErrorBox, Modal, QueryError } from './ui';

type Message = { id: number; senderId: string; senderName: string; body: string; createdAt: string };
type Page = { messages: Message[]; hasMore: boolean };
type Unread = { machineId: string; count: number };
export function useChatUnread(enabled: boolean) {
  return useQuery({ queryKey: ['machine-chat-unread'], queryFn: () => api<Unread[]>('/machine-chat/unread'), enabled, refetchInterval: 5000 });
}
export function MachineChatButton({ id, name, unread = 0 }: { id: string; name: string; unread?: number }) {
  useLanguage();
  const [open, setOpen] = useState(false);
  return <><button type="button" className="machine-chat-button" aria-label={t('محادثة {{v0}}', { v0: name })} onClick={() => setOpen(true)}>
    <MessageCircle size={18} /><span>{t('المحادثة')}</span>{unread > 0 && <b className="chat-unread">{unread > 99 ? '99+' : unread}</b>}
  </button>{open && <Modal title={t('محادثة {{v0}}', { v0: name })} close={() => setOpen(false)}><MachineChat key={id} id={id} name={name} /></Modal>}</>;
}
function MachineChat({ id, name }: { id: string; name: string }) {
  useLanguage();
  const { user } = useAuth(), client = useQueryClient();
  const endpoint = `/machines/${id}/chat`, key = ['machine-chat', id];
  const query = useQuery({ queryKey: key, queryFn: () => api<Page>(endpoint), refetchInterval: 5000 });
  const [older, setOlder] = useState<Message[]>([]), [olderMore, setOlderMore] = useState(true);
  const [body, setBody] = useState(''), [pending, setPending] = useState(false), [loadingOlder, setLoadingOlder] = useState(false), [error, setError] = useState<unknown>(null);
  const end = useRef<HTMLDivElement>(null), readThrough = useRef(0);
  const messages = [...new Map([...older, ...(query.data?.messages ?? [])].map(m => [m.id, m])).values()].sort((a, b) => a.id - b.id);
  const latest = query.data?.messages.at(-1)?.id;
  // Retain earlier loaded messages as the live window advances beyond 50 messages.
  useEffect(() => { if (query.data) setOlder(previous => [...new Map([...previous, ...query.data.messages].map(m => [m.id, m])).values()]); }, [query.data]);
  useEffect(() => { end.current?.scrollIntoView({ block: 'nearest' }); }, [latest]);
  useEffect(() => {
    const mark = () => {
      if (!latest || latest <= readThrough.current || document.visibilityState !== 'visible' || !document.hasFocus()) return;
      const through = latest;
      void api(`${endpoint}/read`, 'PUT', { through }).then(() => {
        readThrough.current = Math.max(readThrough.current, through);
        void client.invalidateQueries({ queryKey: ['machine-chat-unread'] });
      }).catch(setError);
    };
    mark(); window.addEventListener('focus', mark); document.addEventListener('visibilitychange', mark);
    return () => { window.removeEventListener('focus', mark); document.removeEventListener('visibilitychange', mark); };
  }, [latest, endpoint, client]);
  return <div className="modal-body machine-chat"><div className="record-with-visual"><ResourceVisual kind="MACHINE" id={id} name={name} compact /><strong>{name}</strong></div><p className="muted">{t('محادثة داخلية لمستخدمي النظام المصرح لهم بعرض الماكينات. التحديث كل 5 ثوانٍ.')}</p><ErrorBox error={error} />
    {query.isPending ? <Busy /> : query.error ? <QueryError error={query.error} retry={() => void query.refetch()} /> : <>
      {query.data?.hasMore && olderMore && <button disabled={loadingOlder} onClick={async () => {
        setLoadingOlder(true); setError(null);
        try { const page = await api<Page>(`${endpoint}?before=${messages[0].id}`); setOlder(previous => [...page.messages, ...previous]); setOlderMore(page.hasMore); }
        catch (e) { setError(e); } finally { setLoadingOlder(false); }
      }}>{t('تحميل رسائل أقدم')}</button>}
      <div className="chat-messages" role="log" aria-label={t('رسائل الماكينة')} aria-live="polite">
        {!messages.length && <p className="state">{t('ابدأ المحادثة الأولى لهذه الماكينة.')}</p>}
        {messages.map(m => <article key={m.id} className={`chat-message${m.senderId === user.id ? ' chat-mine' : ''}`}>
          <div><strong>{m.senderName}</strong><time dateTime={m.createdAt}>{dateFormatter({ dateStyle: 'short', timeStyle: 'short', timeZone: 'Africa/Cairo' }).format(new Date(m.createdAt))}</time></div><p dir="auto">{m.body}</p>
        </article>)}<div ref={end} />
      </div>
    </>}
    <form className="chat-compose" onSubmit={async e => {
      e.preventDefault(); if (!body.trim() || pending) return;
      setPending(true); setError(null);
      try { await api(endpoint, 'POST', { body: body.trim() }); setBody(''); await client.invalidateQueries({ queryKey: key }); }
      catch (e) { setError(e); } finally { setPending(false); }
    }}><label htmlFor={`chat-${id}`}>{t('رسالة جديدة')}</label><textarea id={`chat-${id}`} dir="auto" maxLength={2000} rows={3} value={body} disabled={pending} onChange={e => setBody(e.target.value)} required />
      <div><small className="muted">{body.length} / 2000</small><button className="primary" disabled={pending || !body.trim() || !!query.error}><Send size={17} />{t(pending ? 'جارٍ الإرسال' : 'إرسال')}</button></div>
    </form>
  </div>;
}
