import { useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { Check, ChevronLeft, ChevronRight, ClipboardList, PackageCheck, PackagePlus, Play, RefreshCw, RotateCcw, Search, Send } from 'lucide-react';
import { api } from './api';
import { can, useAuth } from './access';
import { Busy, Denied, ErrorBox, IconButton, Modal, QueryError } from './ui';
import type { Part } from './catalog';

type Piece = { id: string; serialNumber: string; partId: string; partCode: string; partName: string; location: string; status: string; version: number; requirementId: string | null; orderId: string | null; orderCode: string | null; positionCode: string | null; recipientName: string | null; taskId: string | null; defectId: string | null };
type Balance = { partId: string; partCode: string; partName: string; available: number; reserved: number; issued: number; quarantined: number; installed: number; rejected: number; inRepair: number; repaired: number; scrapped: number };
type Movement = { id: string; serialNumber: string; action: string; orderCode: string | null; positionCode: string | null; recipientName: string | null; actorName: string; reference: string; notes: string; createdAt: string };
type Coverage = { requirementId: string; positionCode: string; partCode: string; partName: string; required: number; reserved: number; issued: number; installed: number; missing: number; preparationId: string | null; preparationStatus: string | null };
type OrderStock = { orderId: string; version: number; status: string; requirements: Coverage[]; pieces: Piece[] };
export type Preparation = { id: string; orderId: string; orderCode: string; orderTitle: string; positionCode: string; partCode: string; partName: string; requestedQuantity: number; missing: number; status: string; notes: string; version: number; requestedAt: string };
const states: Record<string, string> = { AVAILABLE: 'متاح', RESERVED: 'محجوز', ISSUED: 'مصروف', INSTALLED: 'مركب', QUARANTINED: 'معزول', REQUESTED: 'مطلوب تجهيز', IN_PROGRESS: 'قيد التجهيز', FULFILLED: 'مغطى بالمخزون', CANCELLED: 'ملغي', REJECTED: 'مرفوض بعهدة العامل', IN_REPAIR: 'تحت الإصلاح', REPAIRED: 'مصلح ينتظر الاستلام', SCRAPPED: 'معدم' };
const actions: Record<string, string> = { RECEIVED: 'استلام', RESERVED: 'حجز', UNRESERVED: 'فك الحجز', ISSUED: 'صرف', RETURNED_GOOD: 'إرجاع سليم', RETURNED_DEFECTIVE: 'إرجاع معيب', RESTORED: 'إتاحة بعد الفحص', INSTALLED: 'تركيب', UNINSTALLED: 'فك', REJECTED: 'رفض العامل', DEFECT_RECEIVED: 'استلام المعيب', REPAIR_STARTED: 'إرسال للإصلاح', REPAIR_COMPLETED: 'إنهاء الإصلاح', REPAIR_RECEIVED: 'استلام وفحص المصلح', SCRAPPED: 'إعدام' };
const dateFormat = new Intl.DateTimeFormat('ar-EG', { dateStyle: 'short', timeStyle: 'short', timeZone: 'Africa/Cairo' });
function Badge({ status }: { status: string }) { return <span className={`badge ${['AVAILABLE', 'FULFILLED'].includes(status) ? 'active' : ['QUARANTINED', 'CANCELLED'].includes(status) ? 'inactive' : 'draft'}`}>{states[status] ?? status}</span>; }
function PartName({ code, name }: { code: string; name: string }) { return <><strong>{name}</strong><span className="record-code" dir="ltr">{code}</span></>; }
export async function invalidateStock(client: ReturnType<typeof useQueryClient>) {
  await Promise.all([['warehouse'], ['/manufacturing-orders'], ['assembly'], ['/machines'], ['defects'], ['operations'], ['reports']].map(queryKey => client.invalidateQueries({ queryKey })));
}
export function StockTable<T>({ rows, columns, searchText, placeholder = 'بحث', filters, searchValue, onSearchChange }: { rows: T[]; columns: { label: string; cell: (row: T) => ReactNode }[]; searchText: (row: T) => string; placeholder?: string; filters?: ReactNode; searchValue?: string; onSearchChange?: (value: string) => void }) {
  const [localSearch, setLocalSearch] = useState(''), [page, setPage] = useState(0);
  const search = searchValue ?? localSearch;
  const setSearch = onSearchChange ?? setLocalSearch;
  const filtered = searchValue === undefined ? rows.filter(r => searchText(r).toLowerCase().includes(search.toLowerCase())) : rows;
  const pages = Math.max(1, Math.ceil(filtered.length / 10)), current = Math.min(page, pages - 1);
  return <><div className="toolbar"><label className="search"><Search size={18} /><input aria-label="بحث المخزن" placeholder={placeholder} value={search} onChange={e => { setSearch(e.target.value); setPage(0); }} /></label>{filters}</div>
    <div className="table-wrap"><table><thead><tr>{columns.map(c => <th key={c.label}>{c.label}</th>)}</tr></thead><tbody>{filtered.slice(current * 10, current * 10 + 10).map((r, i) => <tr key={i}>{columns.map(c => <td key={c.label}>{c.cell(r)}</td>)}</tr>)}</tbody></table>{!filtered.length && <div className="state">لا توجد نتائج</div>}</div>
    <div className="pagination"><span>{filtered.length} سجل</span><div><IconButton label="الصفحة السابقة" disabled={!current} onClick={() => setPage(current - 1)}><ChevronRight size={18} /></IconButton><span>{current + 1} / {pages}</span><IconButton label="الصفحة التالية" disabled={current === pages - 1} onClick={() => setPage(current + 1)}><ChevronLeft size={18} /></IconButton></div></div></>;
}
export function WarehousePage() {
  const { user } = useAuth(); const client = useQueryClient();
  const allowed = can(user, 'stock.view'); const [tab, setTab] = useState('balances'), [receiving, setReceiving] = useState(false), [status, setStatus] = useState('all');
  const [restore, setRestore] = useState<Piece | null>(null), [notes, setNotes] = useState(''), [error, setError] = useState<unknown>(null), [pending, setPending] = useState(false), [notice, setNotice] = useState('');
  const balances = useQuery({ queryKey: ['warehouse', 'balances'], queryFn: () => api<Balance[]>('/warehouse/balances'), enabled: allowed && tab === 'balances' });
  const pieces = useQuery({ queryKey: ['warehouse', 'pieces'], queryFn: () => api<Piece[]>('/warehouse/pieces'), enabled: allowed && tab === 'pieces' });
  const movements = useQuery({ queryKey: ['warehouse', 'movements'], queryFn: () => api<Movement[]>('/warehouse/movements'), enabled: allowed && tab === 'movements' });
  if (!allowed) return <Denied />;
  const query = tab === 'balances' ? balances : tab === 'pieces' ? pieces : movements;
  return <><div className="page-heading"><div><div className="eyebrow">المخزن الرئيسي</div><h1>المخزن</h1></div><div className="bom-actions">
    <IconButton label="تحديث المخزن" disabled={query.isFetching} onClick={() => void query.refetch()}><RefreshCw size={18} /></IconButton>
    {can(user, 'stock.receive') && can(user, 'catalog.view') && <button className="primary" onClick={() => setReceiving(true)}><PackagePlus size={18} />استلام قطع</button>}</div></div>
    {notice && <div className="notice" role="status"><Check size={17} />{notice}</div>}
    <div className="view-tabs" role="tablist" aria-label="عرض المخزن">{[['balances', 'الأرصدة'], ['pieces', 'القطع المسلسلة'], ['movements', 'سجل الحركات']].map(([value, label]) => <button key={value} role="tab" aria-selected={tab === value} onClick={() => setTab(value)}>{label}</button>)}</div>
    {query.isPending ? <Busy /> : query.error ? <QueryError error={query.error} retry={() => void query.refetch()} /> : tab === 'balances' ? <StockTable key={tab} rows={balances.data ?? []} searchText={r => `${r.partCode} ${r.partName}`} columns={[
      { label: 'الصنف', cell: r => <PartName code={r.partCode} name={r.partName} /> }, { label: 'متاح', cell: r => r.available }, { label: 'محجوز', cell: r => r.reserved }, { label: 'داخل المخزن', cell: r => r.available + r.reserved }, { label: 'مصروف غير مركب', cell: r => r.issued }, { label: 'مركب', cell: r => r.installed }, { label: 'معزول', cell: r => r.quarantined },
      { label: 'مرفوض بعهدة العامل', cell: r => r.rejected }, { label: 'تحت الإصلاح', cell: r => r.inRepair }, { label: 'مصلح ينتظر الاستلام', cell: r => r.repaired }, { label: 'معدم', cell: r => r.scrapped },
    ]} /> : tab === 'pieces' ? <StockTable key={tab} rows={(pieces.data ?? []).filter(p => status === 'all' || p.status === status)} searchText={r => `${r.serialNumber} ${r.partCode} ${r.partName} ${r.orderCode ?? ''} ${r.location}`} filters={<select aria-label="حالة القطعة" value={status} onChange={e => setStatus(e.target.value)}><option value="all">كل الحالات</option>{['AVAILABLE', 'RESERVED', 'ISSUED', 'INSTALLED', 'REJECTED', 'QUARANTINED', 'IN_REPAIR', 'REPAIRED', 'SCRAPPED'].map(s => <option key={s} value={s}>{states[s]}</option>)}</select>} columns={[
      { label: 'Serial Number', cell: r => <strong dir="ltr">{r.serialNumber}</strong> }, { label: 'الصنف', cell: r => <PartName code={r.partCode} name={r.partName} /> }, { label: 'الموقع', cell: r => r.location || '—' }, { label: 'الحالة', cell: r => <Badge status={r.status} /> },
      { label: 'الطلب / الموضع', cell: r => r.orderId && can(user, 'orders.view') ? <Link className="inline-link" to={`/orders/${r.orderId}`}>{r.orderCode} · {r.positionCode}</Link> : '—' }, { label: 'المستلم', cell: r => r.recipientName || '—' },
      { label: 'الإجراءات', cell: r => r.defectId ? can(user, 'defects.view') && can(user, 'orders.view') && <Link className="inline-link" to={`/defects?case=${r.defectId}`}>بلاغ العيب</Link> : r.status === 'QUARANTINED' && can(user, 'stock.receive') && <IconButton label={`إتاحة ${r.serialNumber} بعد الفحص`} onClick={() => { setRestore(r); setNotes(''); setError(null); }}><PackageCheck size={18} /></IconButton> },
    ]} /> : <StockTable key={tab} rows={movements.data ?? []} searchText={r => `${r.serialNumber} ${r.orderCode ?? ''} ${r.reference} ${r.actorName}`} columns={[
      { label: 'التاريخ · القاهرة', cell: r => dateFormat.format(new Date(r.createdAt)) }, { label: 'Serial Number', cell: r => <span dir="ltr">{r.serialNumber}</span> }, { label: 'الحركة', cell: r => actions[r.action] }, { label: 'الطلب / الموضع', cell: r => r.orderCode ? `${r.orderCode} · ${r.positionCode}` : '—' },
      { label: 'المنفذ / المستلم', cell: r => <>{r.actorName}{r.recipientName && <div className="muted">{r.recipientName}</div>}</> }, { label: 'مرجع الاستلام', cell: r => r.reference || '—' }, { label: 'ملاحظات', cell: r => <span className="cell-notes">{r.notes || '—'}</span> },
    ]} />}
    {receiving && <ReceiptForm cancel={() => setReceiving(false)} saved={async () => { setReceiving(false); setNotice('تم استلام القطع'); await invalidateStock(client); }} />}
    {restore && <Modal title={`إتاحة ${restore.serialNumber} بعد الفحص`} close={() => { if (!pending) setRestore(null); }}><form className="form-stack" onSubmit={async e => {
      e.preventDefault(); setPending(true); setError(null);
      try { await api(`/warehouse/pieces/${restore.id}/restore`, 'POST', { version: restore.version, notes }); setRestore(null); setNotice('تمت إعادة القطعة للإتاحة'); await invalidateStock(client); } catch (e) { setError(e); } finally { setPending(false); }
    }}><ErrorBox error={error} /><label>نتيجة الفحص<textarea aria-label="نتيجة الفحص" value={notes} onChange={e => setNotes(e.target.value)} maxLength={1000} required rows={3} /></label><div className="form-actions"><button className="primary" disabled={pending || !notes.trim()}><Check size={17} />تأكيد الإتاحة</button></div></form></Modal>}
  </>;
}
function ReceiptForm({ saved, cancel }: { saved: () => Promise<void>; cancel: () => void }) {
  const [error, setError] = useState<unknown>(null);
  const parts = useQuery({ queryKey: ['/parts'], queryFn: () => api<Part[]>('/parts') });
  const form = useForm({ defaultValues: { partId: '', serialText: '', location: '', reference: '', notes: '' } });
  return <Modal title="استلام قطع" close={() => { if (!form.formState.isSubmitting) cancel(); }}><form className="form-stack" onSubmit={form.handleSubmit(async v => {
    setError(null); try { await api('/warehouse/receipts', 'POST', { ...v, serials: v.serialText.split(/\r?\n/).map(s => s.trim()).filter(Boolean) }); await saved(); } catch (e) { setError(e); }
  })}><ErrorBox error={error} />{parts.error && <QueryError error={parts.error} retry={() => void parts.refetch()} />}
    <label>الصنف<select aria-label="الصنف" {...form.register('partId')} required disabled={parts.isPending || parts.isError}><option value="">اختر الصنف</option>{parts.data?.filter(p => p.active && p.kind === 'SERIALIZED_PIECE').map(p => <option key={p.id} value={p.id}>{p.code} · {p.name}</option>)}</select></label>
    <label>الأرقام المسلسلة · رقم لكل سطر<textarea aria-label="الأرقام المسلسلة" {...form.register('serialText')} required rows={5} maxLength={13000} dir="ltr" /></label>
    <label>الموقع بالمخزن<input {...form.register('location')} maxLength={120} /></label><label>مرجع الاستلام<input {...form.register('reference')} maxLength={120} /></label><label>ملاحظات<textarea {...form.register('notes')} maxLength={1000} rows={2} /></label>
    <div className="form-actions"><button className="primary" disabled={form.formState.isSubmitting || parts.isPending || parts.isError}><PackagePlus size={17} />تأكيد الاستلام</button><button type="button" onClick={cancel} disabled={form.formState.isSubmitting}>إلغاء</button></div>
  </form></Modal>;
}
type StockAction = { kind: 'issue' | 'return' | 'preparation'; version: number; piece?: Piece; requirement?: Coverage; pieceIds?: string[] };
export function OrderWarehouse({ orderId, responsibleName }: { orderId: string; responsibleName: string }) {
  const { user } = useAuth(); const client = useQueryClient();
  const query = useQuery({ queryKey: ['warehouse', 'order', orderId], queryFn: () => api<OrderStock>(`/manufacturing-orders/${orderId}/stock`) });
  const [selection, setSelection] = useState<string[]>([]), [action, setAction] = useState<StockAction | null>(null);
  const [notes, setNotes] = useState(''), [condition, setCondition] = useState('GOOD'), [recipientId, setRecipientId] = useState(''), [error, setError] = useState<unknown>(null), [pending, setPending] = useState(false), [notice, setNotice] = useState('');
  const stock = query.data;
  const manage = !!stock && can(user, 'stock.view') && can(user, 'orders.view') && ['OPEN', 'IN_PROGRESS', 'AWAITING_TESTING', 'TESTING', 'REWORK'].includes(stock.status);
  const recipients = useQuery({ queryKey: ['assembly', 'recipients', orderId], queryFn: () => api<{ id: string; name: string }[]>(`/manufacturing-orders/${orderId}/stock/recipients`), enabled: manage && can(user, 'stock.issue') });
  const selected = selection.filter(id => stock?.pieces.some(p => p.id === id && p.status === 'RESERVED'));
  const saved = async (result: OrderStock) => { client.setQueryData(['warehouse', 'order', orderId], result); setAction(null); setSelection([]); setNotice('تم حفظ حركة المخزن'); await invalidateStock(client); };
  const send = async (path: string, payload: unknown) => {
    setPending(true); setError(null); setNotice('');
    try { await saved(await api<OrderStock>(`/manufacturing-orders/${orderId}/stock/${path}`, 'POST', payload)); } catch (e) { setError(e); } finally { setPending(false); }
  };
  const open = (next: StockAction) => { setError(null); setNotes(''); setCondition('GOOD'); setRecipientId(''); setAction(next); };
  return <section className="order-warehouse"><div className="bom-summary"><h2>تجهيز المخزن</h2><div className="bom-actions">
    <IconButton label="تحديث تجهيز المخزن" onClick={() => void query.refetch()} disabled={query.isFetching || pending}><RefreshCw size={18} /></IconButton>
    {manage && can(user, 'stock.reserve') && <button disabled={pending} onClick={() => void send('reserve', { version: stock.version })}><PackageCheck size={17} />حجز المتاح</button>}
    {manage && can(user, 'stock.issue') && <button className="primary" disabled={pending || !selected.length} onClick={() => open({ kind: 'issue', version: stock.version, pieceIds: selected })}><Send size={17} />صرف المحدد ({selected.length})</button>}
  </div></div>{notice && <div className="notice" role="status"><Check size={17} />{notice}</div>}{!action && <ErrorBox error={error} />}
    {query.isPending ? <Busy /> : query.error ? <QueryError error={query.error} retry={() => void query.refetch()} /> : stock && <>
      <div className="table-wrap"><table><thead><tr><th>الموضع</th><th>القطعة</th><th>المطلوب</th><th>محجوز</th><th>مصروف</th><th>مركب</th><th>غير مغطى</th><th>التجهيز</th></tr></thead><tbody>{stock.requirements.map(r => <tr key={r.requirementId}>
        <td dir="ltr"><strong>{r.positionCode}</strong></td><td><PartName code={r.partCode} name={r.partName} /></td><td>{r.required}</td><td>{r.reserved}</td><td>{r.issued}</td><td>{r.installed}</td><td>{r.missing}</td>
        <td>{r.preparationStatus && <Badge status={r.preparationStatus} />}{manage && can(user, 'stock.reserve') && r.missing > 0 && (!r.preparationStatus || r.preparationStatus === 'FULFILLED') && <button className="stock-request" onClick={() => open({ kind: 'preparation', version: stock.version, requirement: r })} disabled={pending}><ClipboardList size={16} />طلب تجهيز {r.positionCode}</button>}</td>
      </tr>)}</tbody></table></div>
      <h3 className="stock-subheading">القطع المخصصة للطلب <span className="count">{stock.pieces.length}</span></h3>
      <div className="table-wrap"><table><thead><tr>{manage && can(user, 'stock.issue') && <th>تحديد</th>}<th>Serial Number</th><th>الموضع</th><th>الحالة</th><th>المستلم</th><th>الإجراءات</th></tr></thead><tbody>{stock.pieces.map(p => <tr key={p.id}>
        {manage && can(user, 'stock.issue') && <td>{p.status === 'RESERVED' && <input aria-label={`تحديد ${p.serialNumber}`} type="checkbox" checked={selected.includes(p.id)} disabled={pending || (!selected.includes(p.id) && selected.length >= 200)} onChange={e => setSelection(e.target.checked ? [...selected, p.id] : selected.filter(id => id !== p.id))} />}</td>}
        <td><strong dir="ltr">{p.serialNumber}</strong></td><td dir="ltr">{p.positionCode}</td><td><Badge status={p.status} /></td><td>{p.recipientName || '—'}</td>
        <td>{manage && can(user, 'stock.return') && p.status === 'ISSUED' && <IconButton label={`إرجاع ${p.serialNumber}`} disabled={pending} onClick={() => open({ kind: 'return', version: stock.version, piece: p })}><RotateCcw size={17} /></IconButton>}</td>
      </tr>)}</tbody></table>{!stock.pieces.length && <div className="state">لا توجد قطع مخصصة</div>}</div>
    </>}
    {action && <Modal title={action.kind === 'issue' ? 'صرف القطع' : action.kind === 'return' ? `إرجاع ${action.piece!.serialNumber}` : `طلب تجهيز ${action.requirement!.positionCode}`} close={() => { if (!pending) setAction(null); }}><form className="form-stack" onSubmit={e => {
      e.preventDefault(); void send(action.kind === 'return' ? `pieces/${action.piece!.id}/return` : action.kind, { version: action.version, pieceIds: action.pieceIds, requirementId: action.requirement?.requirementId, recipientId: recipientId || null, condition, notes });
    }}><ErrorBox error={error} />{action.kind === 'issue' && <><p>{action.pieceIds!.length} قطعة</p>{recipients.error && <QueryError error={recipients.error} retry={() => void recipients.refetch()} />}<label>المستلم<select aria-label="المستلم" value={recipientId} onChange={e => setRecipientId(e.target.value)} disabled={pending || recipients.isPending}><option value="">مسؤول الطلب · {responsibleName}</option>{recipients.data?.map(r => <option key={r.id} value={r.id}>{r.name}</option>)}</select></label></>}
      {action.kind === 'preparation' && <p>{action.requirement!.partName} · {action.requirement!.missing} قطعة</p>}
      {action.kind === 'return' && <label>حالة المرتجع<select aria-label="حالة المرتجع" value={condition} onChange={e => setCondition(e.target.value)}><option value="GOOD">سليم ومفحوص · متاح</option><option value="DEFECTIVE">معيب · عزل</option></select></label>}
      <label>{action.kind === 'return' ? 'سبب الإرجاع' : 'ملاحظات'}<textarea aria-label={action.kind === 'return' ? 'سبب الإرجاع' : 'ملاحظات الحركة'} value={notes} onChange={e => setNotes(e.target.value)} maxLength={1000} required={action.kind === 'return'} rows={3} /></label>
      <div className="form-actions"><button className="primary" disabled={pending || (action.kind === 'return' && !notes.trim()) || (action.kind === 'issue' && (recipients.isPending || recipients.isError))}><Check size={17} />{action.kind === 'issue' ? 'تأكيد الصرف' : action.kind === 'return' ? 'تأكيد الإرجاع' : 'إرسال للتصنيع'}</button><button type="button" disabled={pending} onClick={() => setAction(null)}>رجوع</button></div>
    </form></Modal>}
  </section>;
}
export function PreparationsPage() {
  const { user } = useAuth(); const client = useQueryClient();
  const allowed = can(user, 'orders.view') && (can(user, 'stock.view') || can(user, 'preparation.manage'));
  const query = useQuery({ queryKey: ['warehouse', 'preparations'], queryFn: () => api<Preparation[]>('/warehouse/preparations'), enabled: allowed });
  const [status, setStatus] = useState('all'), [pending, setPending] = useState(false), [error, setError] = useState<unknown>(null);
  if (!allowed) return <Denied />;
  return <><div className="page-heading"><div><div className="eyebrow">التصنيع</div><h1>نواقص وتجهيز القطع</h1></div><IconButton label="تحديث النواقص" disabled={query.isFetching || pending} onClick={() => void query.refetch()}><RefreshCw size={18} /></IconButton></div><ErrorBox error={error} />
    {query.isPending ? <Busy /> : query.error ? <QueryError error={query.error} retry={() => void query.refetch()} /> : <StockTable rows={(query.data ?? []).filter(p => status === 'all' || p.status === status)} searchText={r => `${r.orderCode} ${r.orderTitle} ${r.partCode} ${r.partName} ${r.positionCode}`} filters={<select aria-label="حالة التجهيز" value={status} onChange={e => setStatus(e.target.value)}><option value="all">كل الحالات</option>{['REQUESTED', 'IN_PROGRESS', 'FULFILLED', 'CANCELLED'].map(s => <option key={s} value={s}>{states[s]}</option>)}</select>} columns={[
      { label: 'الطلب', cell: r => <><Link className="inline-link" to={`/orders/${r.orderId}`}>{r.orderTitle}</Link><span className="record-code" dir="ltr">{r.orderCode}</span></> }, { label: 'الموضع', cell: r => r.positionCode }, { label: 'القطعة', cell: r => <PartName code={r.partCode} name={r.partName} /> },
      { label: 'عند الطلب', cell: r => r.requestedQuantity }, { label: 'المتبقي', cell: r => r.missing }, { label: 'الحالة', cell: r => <Badge status={r.status} /> }, { label: 'ملاحظات', cell: r => <span className="cell-notes">{r.notes || '—'}</span> },
      { label: 'الإجراءات', cell: r => <div className="bom-actions">{can(user, 'preparation.manage') && r.status === 'REQUESTED' && <button disabled={pending} onClick={async () => {
        setPending(true); setError(null); try { await api(`/warehouse/preparations/${r.id}/start`, 'POST', { version: r.version }); await invalidateStock(client); } catch (e) { setError(e); } finally { setPending(false); }
      }}><Play size={16} />بدء التجهيز</button>}{can(user, 'stock.view') && can(user, 'raw.issue') && r.status === 'IN_PROGRESS' && r.missing > 0 && <Link className="inline-link" to={`/raw-stock?preparation=${r.id}`}><Send size={16} />صرف خامة</Link>}</div> },
    ]} />}
  </>;
}
