import { formatDisplay } from './i18n';
import { translateUi, translateDisplay, useLanguage, dateFormatter, getLocale, getDirection } from './i18n';
import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { useQuery, localizedKey } from './localizedQuery';
import { Check, Eye, PackageCheck, PackageOpen, Play, RefreshCw, Trash2, Wrench } from 'lucide-react';
import { api } from './api';
import { can, useAuth } from './access';
import { Busy, Denied, ErrorBox, IconButton, Modal, QueryError } from './ui';
import { StockTable, invalidateStock } from './Warehouse';
import { Files } from './Files';

type Defect = { id: string; taskId: string; pieceId: string; orderId: string; orderCode: string; machineSerial: string; positionCode: string; occurrence: number; serialNumber: string; partCode: string; partName: string; reportedBy: string; reporterName: string; reason: string; status: string; pieceStatus: string; version: number; reportedAt: string };
type Event = { id: string; action: string; actorName: string; notes: string; createdAt: string };
type Detail = { defect: Defect; events: Event[] };
type Action = 'receive' | 'repair-start' | 'repair-finish' | 'accept' | 'scrap';
const states: Record<string, string> = { REPORTED: 'في عهدة العامل', QUARANTINED: 'معزول بالمخزن', IN_REPAIR: 'تحت الإصلاح', REPAIRED: 'مصلح ينتظر الاستلام', RETURNED: 'استلم سليمًا', SCRAPPED: 'معدم' };
const actions: Record<Action, string> = { receive: 'استلام المعيب', 'repair-start': 'بدء الإصلاح', 'repair-finish': 'إنهاء الإصلاح', accept: 'استلام وفحص المصلح', scrap: 'إعدام القطعة' };
const events: Record<string, string> = { REPORTED: 'بلاغ العيب', RECEIVED: 'استلام المعيب', REPAIR_STARTED: 'بدء الإصلاح', REPAIR_COMPLETED: 'إنهاء الإصلاح', ACCEPTED: 'استلام وفحص المصلح', SCRAPPED: 'إعدام القطعة' };
const date = dateFormatter({ dateStyle: 'short', timeStyle: 'short', timeZone: 'Africa/Cairo' });
function State({ value }: { value: string }) {
  useLanguage(); return <span className={`badge ${translateDisplay(value === 'RETURNED' ? 'active' : value === 'SCRAPPED' ? 'inactive' : 'draft')}`}>{formatDisplay(states[value])}</span>; }

export function DefectsPage() {
  useLanguage();
  const { user } = useAuth(); const client = useQueryClient(); const [params, setParams] = useSearchParams();
  const all = can(user, 'defects.view') && can(user, 'orders.view'), allowed = all || can(user, 'tasks.view-assigned') || (can(user, 'tasks.view') && can(user, 'orders.view'));
  const query = useQuery({ queryKey: ['defects', 'list'], queryFn: () => api<Defect[]>('/defects'), enabled: allowed });
  const [state, setState] = useState('active'), [action, setAction] = useState<{ kind: Action; defect: Defect } | null>(null), [notice, setNotice] = useState('');
  const detail = params.get('case');
  if (!allowed) return <Denied />;
  return <div className="defects-page"><div className="page-heading"><div><div className="eyebrow">{translateUi("متابعة القطع")}</div><h1>{formatDisplay(all ? 'العيوب والإصلاح' : 'بلاغاتي')}</h1></div><IconButton label={translateUi("تحديث بلاغات العيوب")} disabled={query.isFetching} onClick={() => void query.refetch()}><RefreshCw size={18} /></IconButton></div>
    {formatDisplay(notice && <div className="notice" role="status"><Check size={17} />{formatDisplay(notice)}</div>)}
    {formatDisplay(query.isPending ? <Busy /> : query.error ? <QueryError error={query.error} retry={() => void query.refetch()} /> : <StockTable rows={(query.data ?? []).filter(d => state === 'all' || (state === 'active' ? !['RETURNED', 'SCRAPPED'].includes(d.status) : d.status === state))} searchText={d => `${translateDisplay(d.serialNumber)} ${translateDisplay(d.partCode)} ${translateDisplay(d.partName)} ${translateDisplay(d.orderCode)} ${translateDisplay(d.machineSerial)} ${translateDisplay(d.reporterName)} ${translateDisplay(d.reason)}`} filters={<select aria-label={translateUi("حالة بلاغ العيب")} value={state} onChange={e => setState(e.target.value)}><option value="active">{translateUi("البلاغات المفتوحة")}</option><option value="all">{translateUi("كل البلاغات")}</option>{formatDisplay(Object.entries(states).map(([v, name]) => <option key={v} value={v}>{formatDisplay(name)}</option>))}</select>} columns={[
      { label: 'القطعة / Serial', cell: d => <><strong>{formatDisplay(d.partName)}</strong><span className="record-code" dir="ltr">{formatDisplay(d.partCode)}</span><span className="record-code" dir="ltr">{formatDisplay(d.serialNumber)}</span></> },
      { label: 'الطلب / الموضع', cell: d => <><strong dir="ltr">{formatDisplay(d.orderCode)}</strong><span className="record-code" dir="ltr">{formatDisplay(d.machineSerial)} · {formatDisplay(d.positionCode)} / {formatDisplay(d.occurrence)}</span></> },
      { label: 'البلاغ', cell: d => <><strong>{formatDisplay(d.reporterName)}</strong><span className="record-code">{formatDisplay(date.format(new Date(d.reportedAt)))}</span><span className="cell-notes">{formatDisplay(d.reason)}</span></> },
      { label: 'الحالة', cell: d => <State value={d.status} /> },
      { label: 'الإجراءات', cell: d => <div className="row-actions"><IconButton label={formatDisplay(translateUi("سجل {{v0}}", {v0: d.serialNumber}))} onClick={() => setParams({ case: d.id })}><Eye size={17} /></IconButton>
        {formatDisplay(all && <>{formatDisplay(d.status === 'REPORTED' && can(user, 'stock.view') && can(user, 'stock.return') && <IconButton label={formatDisplay(translateUi("استلام المعيب {{v0}}", {v0: d.serialNumber}))} onClick={() => setAction({ kind: 'receive', defect: d })}><PackageOpen size={17} /></IconButton>)}
          {formatDisplay(d.status === 'QUARANTINED' && can(user, 'defects.manage') && <IconButton label={formatDisplay(translateUi("بدء الإصلاح {{v0}}", {v0: d.serialNumber}))} onClick={() => setAction({ kind: 'repair-start', defect: d })}><Play size={17} /></IconButton>)}
          {formatDisplay(d.status === 'IN_REPAIR' && can(user, 'defects.manage') && <IconButton label={formatDisplay(translateUi("إنهاء الإصلاح {{v0}}", {v0: d.serialNumber}))} onClick={() => setAction({ kind: 'repair-finish', defect: d })}><Wrench size={17} /></IconButton>)}
          {formatDisplay(d.status === 'REPAIRED' && can(user, 'stock.view') && can(user, 'stock.receive') && <IconButton label={formatDisplay(translateUi("استلام المصلح {{v0}}", {v0: d.serialNumber}))} onClick={() => setAction({ kind: 'accept', defect: d })}><PackageCheck size={17} /></IconButton>)}
          {formatDisplay(['QUARANTINED', 'IN_REPAIR', 'REPAIRED'].includes(d.status) && can(user, 'defects.scrap') && <IconButton label={formatDisplay(translateUi("إعدام {{v0}}", {v0: d.serialNumber}))} onClick={() => setAction({ kind: 'scrap', defect: d })}><Trash2 size={17} /></IconButton>)}</>)}
      </div> },
    ]} />)}
    {formatDisplay(detail && <DefectDetail id={detail} close={() => setParams({})} />)}
    {formatDisplay(action && <DefectAction action={action} close={() => setAction(null)} saved={async () => { setAction(null); setNotice('تم حفظ إجراء القطعة'); await invalidateStock(client); }} />)}
  </div>;
}
function DefectAction({ action, close, saved }: { action: { kind: Action; defect: Defect }; close: () => void; saved: () => Promise<void> }) {
  useLanguage();
  const [notes, setNotes] = useState(''), [location, setLocation] = useState(''), [pending, setPending] = useState(false), [error, setError] = useState<unknown>(null);
  const receipt = ['receive', 'accept'].includes(action.kind), scrap = action.kind === 'scrap';
  const noteLabel = scrap ? 'سبب الإعدام' : action.kind === 'accept' ? 'نتيجة فحص القطعة المصلحة' : 'ملاحظات الإجراء';
  return <Modal title={formatDisplay(`${translateDisplay(actions[action.kind])} · ${translateDisplay(action.defect.serialNumber)}`)} close={() => { if (!pending) close(); }}><form className="form-stack" onSubmit={async e => {
    e.preventDefault(); setPending(true); setError(null);
    try { await api(`/defects/${translateDisplay(action.defect.id)}/${translateDisplay(action.kind)}`, 'POST', { version: action.defect.version, notes, location }); await saved(); } catch (e) { setError(e); } finally { setPending(false); }
  }}><ErrorBox error={error} /><div className="raw-source"><strong>{formatDisplay(action.defect.partName)}</strong><span>{formatDisplay(action.defect.reason)}</span><State value={action.defect.status} /></div>
    {formatDisplay(receipt && <label>{translateUi("موقع الاستلام")}<input aria-label={translateUi("موقع الاستلام")} value={location} onChange={e => setLocation(e.target.value)} maxLength={120} required disabled={pending} /></label>)}
    <label>{formatDisplay(noteLabel)}<textarea aria-label={formatDisplay(noteLabel)} value={notes} onChange={e => setNotes(e.target.value)} maxLength={1000} rows={4} required disabled={pending} /></label>
    <div className="form-actions"><button className={scrap ? 'danger' : 'primary'} disabled={pending || !notes.trim() || (receipt && !location.trim())}>{formatDisplay(scrap ? <Trash2 size={17} /> : <Check size={17} />)}{formatDisplay(actions[action.kind])}</button><button type="button" disabled={pending} onClick={close}>{translateUi("رجوع")}</button></div>
  </form></Modal>;
}
function DefectDetail({ id, close }: { id: string; close: () => void }) {
  useLanguage();
  const query = useQuery({ queryKey: ['defects', 'detail', id], queryFn: () => api<Detail>(`/defects/${translateDisplay(id)}`) }); const d = query.data?.defect;
  return <Modal title={formatDisplay(d ? translateUi("سجل {{v0}}", {v0: d.serialNumber}) : 'سجل العيب')} close={close}><div className="modal-body">{formatDisplay(query.isPending ? <Busy /> : query.error ? <QueryError error={query.error} retry={() => void query.refetch()} /> : d && <>
    <dl className="order-meta"><div><dt>{translateUi("القطعة")}</dt><dd>{formatDisplay(d.partName)}<span className="record-code" dir="ltr">{formatDisplay(d.serialNumber)}</span></dd></div><div><dt>{translateUi("الطلب / الموضع")}</dt><dd dir="ltr">{formatDisplay(d.orderCode)}<span className="record-code">{formatDisplay(d.positionCode)} / {formatDisplay(d.occurrence)}</span></dd></div><div><dt>{translateUi("مقدم البلاغ")}</dt><dd>{formatDisplay(d.reporterName)}</dd></div><div><dt>{translateUi("الحالة")}</dt><dd><State value={d.status} /></dd></div></dl><p className="order-notes">{formatDisplay(d.reason)}</p>
    <Files kind="DEFECT" id={id} /><ol className="task-history">{formatDisplay(query.data!.events.map(e => <li key={e.id}><div><strong>{formatDisplay(events[e.action])}</strong><time>{formatDisplay(date.format(new Date(e.createdAt)))}</time></div><span>{formatDisplay(e.actorName)}</span><p>{formatDisplay(e.notes)}</p></li>))}</ol>
  </>)}</div></Modal>;
}
