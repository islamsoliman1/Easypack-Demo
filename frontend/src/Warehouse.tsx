import { formatDisplay } from './i18n';
import { searchable } from './i18n';
import { translateUi, translateDisplay, useLanguage, dateFormatter, getLocale, getDirection } from './i18n';
import { useState, type ReactNode } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { useQuery, localizedKey } from './localizedQuery';
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
const dateFormat = dateFormatter({ dateStyle: 'short', timeStyle: 'short', timeZone: 'Africa/Cairo' });
function Badge({ status }: { status: string }) {
  useLanguage(); return <span className={`badge ${translateDisplay(['AVAILABLE', 'FULFILLED'].includes(status) ? 'active' : ['QUARANTINED', 'CANCELLED'].includes(status) ? 'inactive' : 'draft')}`}>{formatDisplay(states[status] ?? status)}</span>; }
function PartName({ code, name }: { code: string; name: string }) {
  useLanguage(); return <><strong>{formatDisplay(name)}</strong><span className="record-code" dir="ltr">{formatDisplay(code)}</span></>; }
export async function invalidateStock(client: ReturnType<typeof useQueryClient>) {
  await Promise.all([['warehouse'], ['/manufacturing-orders'], ['assembly'], ['/machines'], ['defects'], ['operations'], ['reports']].map(queryKey => client.invalidateQueries({ queryKey })));
}
export function StockTable<T>({ rows, columns, searchText, placeholder = 'بحث', filters, searchValue, onSearchChange }: { rows: T[]; columns: { label: string; cell: (row: T) => ReactNode }[]; searchText: (row: T) => string; placeholder?: string; filters?: ReactNode; searchValue?: string; onSearchChange?: (value: string) => void }) {
  useLanguage();
  const [localSearch, setLocalSearch] = useState(''), [page, setPage] = useState(0);
  const search = searchValue ?? localSearch;
  const setSearch = onSearchChange ?? setLocalSearch;
  const filtered = searchValue === undefined ? rows.filter(r => searchable(searchText(r)).toLowerCase().includes(search.toLowerCase())) : rows;
  const pages = Math.max(1, Math.ceil(filtered.length / 10)), current = Math.min(page, pages - 1);
  return <><div className="toolbar"><label className="search"><Search size={18} /><input aria-label={translateUi("بحث المخزن")} placeholder={formatDisplay(placeholder)} value={search} onChange={e => { setSearch(e.target.value); setPage(0); }} /></label>{formatDisplay(filters)}</div>
    <div className="table-wrap"><table><thead><tr>{formatDisplay(columns.map(c => <th key={c.label}>{formatDisplay(c.label)}</th>))}</tr></thead><tbody>{formatDisplay(filtered.slice(current * 10, current * 10 + 10).map((r, i) => <tr key={i}>{formatDisplay(columns.map(c => <td key={c.label}>{formatDisplay(c.cell(r))}</td>))}</tr>))}</tbody></table>{formatDisplay(!filtered.length && <div className="state">{translateUi("لا توجد نتائج")}</div>)}</div>
    <div className="pagination"><span>{formatDisplay(filtered.length)} {translateUi("سجل")}</span><div><IconButton label={translateUi("الصفحة السابقة")} disabled={!current} onClick={() => setPage(current - 1)}><ChevronRight size={18} /></IconButton><span>{formatDisplay(current + 1)} / {formatDisplay(pages)}</span><IconButton label={translateUi("الصفحة التالية")} disabled={current === pages - 1} onClick={() => setPage(current + 1)}><ChevronLeft size={18} /></IconButton></div></div></>;
}
export function WarehousePage() {
  useLanguage();
  const { user } = useAuth(); const client = useQueryClient();
  const allowed = can(user, 'stock.view'); const [tab, setTab] = useState('balances'), [receiving, setReceiving] = useState(false), [status, setStatus] = useState('all');
  const [restore, setRestore] = useState<Piece | null>(null), [notes, setNotes] = useState(''), [error, setError] = useState<unknown>(null), [pending, setPending] = useState(false), [notice, setNotice] = useState('');
  const balances = useQuery({ queryKey: ['warehouse', 'balances'], queryFn: () => api<Balance[]>('/warehouse/balances'), enabled: allowed && tab === 'balances' });
  const pieces = useQuery({ queryKey: ['warehouse', 'pieces'], queryFn: () => api<Piece[]>('/warehouse/pieces'), enabled: allowed && tab === 'pieces' });
  const movements = useQuery({ queryKey: ['warehouse', 'movements'], queryFn: () => api<Movement[]>('/warehouse/movements'), enabled: allowed && tab === 'movements' });
  if (!allowed) return <Denied />;
  const query = tab === 'balances' ? balances : tab === 'pieces' ? pieces : movements;
  return <><div className="page-heading"><div><div className="eyebrow">{translateUi("المخزن الرئيسي")}</div><h1>{translateUi("المخزن")}</h1></div><div className="bom-actions">
    <IconButton label={translateUi("تحديث المخزن")} disabled={query.isFetching} onClick={() => void query.refetch()}><RefreshCw size={18} /></IconButton>
    {formatDisplay(can(user, 'stock.receive') && can(user, 'catalog.view') && <button className="primary" onClick={() => setReceiving(true)}><PackagePlus size={18} />{translateUi("استلام قطع")}</button>)}</div></div>
    {formatDisplay(notice && <div className="notice" role="status"><Check size={17} />{formatDisplay(notice)}</div>)}
    <div className="view-tabs" role="tablist" aria-label={translateUi("عرض المخزن")}>{formatDisplay([['balances', 'الأرصدة'], ['pieces', 'القطع المسلسلة'], ['movements', 'سجل الحركات']].map(([value, label]) => <button key={value} role="tab" aria-selected={tab === value} onClick={() => setTab(value)}>{formatDisplay(label)}</button>))}</div>
    {formatDisplay(query.isPending ? <Busy /> : query.error ? <QueryError error={query.error} retry={() => void query.refetch()} /> : tab === 'balances' ? <StockTable key={tab} rows={balances.data ?? []} searchText={r => `${translateDisplay(r.partCode)} ${translateDisplay(r.partName)}`} columns={[
      { label: 'الصنف', cell: r => <PartName code={r.partCode} name={r.partName} /> }, { label: 'متاح', cell: r => r.available }, { label: 'محجوز', cell: r => r.reserved }, { label: 'داخل المخزن', cell: r => r.available + r.reserved }, { label: 'مصروف غير مركب', cell: r => r.issued }, { label: 'مركب', cell: r => r.installed }, { label: 'معزول', cell: r => r.quarantined },
      { label: 'مرفوض بعهدة العامل', cell: r => r.rejected }, { label: 'تحت الإصلاح', cell: r => r.inRepair }, { label: 'مصلح ينتظر الاستلام', cell: r => r.repaired }, { label: 'معدم', cell: r => r.scrapped },
    ]} /> : tab === 'pieces' ? <StockTable key={tab} rows={(pieces.data ?? []).filter(p => status === 'all' || p.status === status)} searchText={r => `${translateDisplay(r.serialNumber)} ${translateDisplay(r.partCode)} ${translateDisplay(r.partName)} ${translateDisplay(r.orderCode ?? '')} ${translateDisplay(r.location)}`} filters={<select aria-label={translateUi("حالة القطعة")} value={status} onChange={e => setStatus(e.target.value)}><option value="all">{translateUi("كل الحالات")}</option>{formatDisplay(['AVAILABLE', 'RESERVED', 'ISSUED', 'INSTALLED', 'REJECTED', 'QUARANTINED', 'IN_REPAIR', 'REPAIRED', 'SCRAPPED'].map(s => <option key={s} value={s}>{formatDisplay(states[s])}</option>))}</select>} columns={[
      { label: 'Serial Number', cell: r => <strong dir="ltr">{formatDisplay(r.serialNumber)}</strong> }, { label: 'الصنف', cell: r => <PartName code={r.partCode} name={r.partName} /> }, { label: 'الموقع', cell: r => r.location || '—' }, { label: 'الحالة', cell: r => <Badge status={r.status} /> },
      { label: 'الطلب / الموضع', cell: r => r.orderId && can(user, 'orders.view') ? <Link className="inline-link" to={`/orders/${translateDisplay(r.orderId)}`}>{formatDisplay(r.orderCode)} · {formatDisplay(r.positionCode)}</Link> : '—' }, { label: 'المستلم', cell: r => r.recipientName || '—' },
      { label: 'الإجراءات', cell: r => r.defectId ? can(user, 'defects.view') && can(user, 'orders.view') && <Link className="inline-link" to={`/defects?case=${translateDisplay(r.defectId)}`}>{translateUi("بلاغ العيب")}</Link> : r.status === 'QUARANTINED' && can(user, 'stock.receive') && <IconButton label={formatDisplay(translateUi("إتاحة {{v0}} بعد الفحص", {v0: r.serialNumber}))} onClick={() => { setRestore(r); setNotes(''); setError(null); }}><PackageCheck size={18} /></IconButton> },
    ]} /> : <StockTable key={tab} rows={movements.data ?? []} searchText={r => `${translateDisplay(r.serialNumber)} ${translateDisplay(r.orderCode ?? '')} ${translateDisplay(r.reference)} ${translateDisplay(r.actorName)}`} columns={[
      { label: 'التاريخ · القاهرة', cell: r => dateFormat.format(new Date(r.createdAt)) }, { label: 'Serial Number', cell: r => <span dir="ltr">{formatDisplay(r.serialNumber)}</span> }, { label: 'الحركة', cell: r => actions[r.action] }, { label: 'الطلب / الموضع', cell: r => r.orderCode ? `${translateDisplay(r.orderCode)} · ${translateDisplay(r.positionCode)}` : '—' },
      { label: 'المنفذ / المستلم', cell: r => <>{formatDisplay(r.actorName)}{formatDisplay(r.recipientName && <div className="muted">{formatDisplay(r.recipientName)}</div>)}</> }, { label: 'مرجع الاستلام', cell: r => r.reference || '—' }, { label: 'ملاحظات', cell: r => <span className="cell-notes">{formatDisplay(r.notes || '—')}</span> },
    ]} />)}
    {formatDisplay(receiving && <ReceiptForm cancel={() => setReceiving(false)} saved={async () => { setReceiving(false); setNotice('تم استلام القطع'); await invalidateStock(client); }} />)}
    {formatDisplay(restore && <Modal title={formatDisplay(translateUi("إتاحة {{v0}} بعد الفحص", {v0: restore.serialNumber}))} close={() => { if (!pending) setRestore(null); }}><form className="form-stack" onSubmit={async e => {
      e.preventDefault(); setPending(true); setError(null);
      try { await api(`/warehouse/pieces/${translateDisplay(restore.id)}/restore`, 'POST', { version: restore.version, notes }); setRestore(null); setNotice('تمت إعادة القطعة للإتاحة'); await invalidateStock(client); } catch (e) { setError(e); } finally { setPending(false); }
    }}><ErrorBox error={error} /><label>{translateUi("نتيجة الفحص")}<textarea aria-label={translateUi("نتيجة الفحص")} value={notes} onChange={e => setNotes(e.target.value)} maxLength={1000} required rows={3} /></label><div className="form-actions"><button className="primary" disabled={pending || !notes.trim()}><Check size={17} />{translateUi("تأكيد الإتاحة")}</button></div></form></Modal>)}
  </>;
}
function ReceiptForm({ saved, cancel }: { saved: () => Promise<void>; cancel: () => void }) {
  useLanguage();
  const [error, setError] = useState<unknown>(null);
  const parts = useQuery({ queryKey: ['/parts'], queryFn: () => api<Part[]>('/parts') });
  const form = useForm({ defaultValues: { partId: '', serialText: '', location: '', reference: '', notes: '' } });
  return <Modal title={translateUi("استلام قطع")} close={() => { if (!form.formState.isSubmitting) cancel(); }}><form className="form-stack" onSubmit={form.handleSubmit(async v => {
    setError(null); try { await api('/warehouse/receipts', 'POST', { ...v, serials: v.serialText.split(/\r?\n/).map(s => s.trim()).filter(Boolean) }); await saved(); } catch (e) { setError(e); }
  })}><ErrorBox error={error} />{formatDisplay(parts.error && <QueryError error={parts.error} retry={() => void parts.refetch()} />)}
    <label>{translateUi("الصنف")}<select aria-label={translateUi("الصنف")} {...form.register('partId')} required disabled={parts.isPending || parts.isError}><option value="">{translateUi("اختر الصنف")}</option>{formatDisplay(parts.data?.filter(p => p.active && p.kind === 'SERIALIZED_PIECE').map(p => <option key={p.id} value={p.id}>{formatDisplay(p.code)} · {formatDisplay(p.name)}</option>))}</select></label>
    <label>{translateUi("الأرقام المسلسلة · رقم لكل سطر")}<textarea aria-label={translateUi("الأرقام المسلسلة")} {...form.register('serialText')} required rows={5} maxLength={13000} dir="ltr" /></label>
    <label>{translateUi("الموقع بالمخزن")}<input {...form.register('location')} maxLength={120} /></label><label>{translateUi("مرجع الاستلام")}<input {...form.register('reference')} maxLength={120} /></label><label>{translateUi("ملاحظات")}<textarea {...form.register('notes')} maxLength={1000} rows={2} /></label>
    <div className="form-actions"><button className="primary" disabled={form.formState.isSubmitting || parts.isPending || parts.isError}><PackagePlus size={17} />{translateUi("تأكيد الاستلام")}</button><button type="button" onClick={cancel} disabled={form.formState.isSubmitting}>{translateUi("إلغاء")}</button></div>
  </form></Modal>;
}
type StockAction = { kind: 'issue' | 'return' | 'preparation'; version: number; piece?: Piece; requirement?: Coverage; pieceIds?: string[] };
export function OrderWarehouse({ orderId, responsibleName }: { orderId: string; responsibleName: string }) {
  useLanguage();
  const { user } = useAuth(); const client = useQueryClient();
  const query = useQuery({ queryKey: ['warehouse', 'order', orderId], queryFn: () => api<OrderStock>(`/manufacturing-orders/${translateDisplay(orderId)}/stock`) });
  const [selection, setSelection] = useState<string[]>([]), [action, setAction] = useState<StockAction | null>(null);
  const [notes, setNotes] = useState(''), [condition, setCondition] = useState('GOOD'), [recipientId, setRecipientId] = useState(''), [error, setError] = useState<unknown>(null), [pending, setPending] = useState(false), [notice, setNotice] = useState('');
  const stock = query.data;
  const manage = !!stock && can(user, 'stock.view') && can(user, 'orders.view') && ['OPEN', 'IN_PROGRESS', 'AWAITING_TESTING', 'TESTING', 'REWORK'].includes(stock.status);
  const recipients = useQuery({ queryKey: ['assembly', 'recipients', orderId], queryFn: () => api<{ id: string; name: string }[]>(`/manufacturing-orders/${translateDisplay(orderId)}/stock/recipients`), enabled: manage && can(user, 'stock.issue') });
  const selected = selection.filter(id => stock?.pieces.some(p => p.id === id && p.status === 'RESERVED'));
  const saved = async (result: OrderStock) => { client.setQueryData(localizedKey(['warehouse', 'order', orderId]), result); setAction(null); setSelection([]); setNotice('تم حفظ حركة المخزن'); await invalidateStock(client); };
  const send = async (path: string, payload: unknown) => {
    setPending(true); setError(null); setNotice('');
    try { await saved(await api<OrderStock>(`/manufacturing-orders/${translateDisplay(orderId)}/stock/${translateDisplay(path)}`, 'POST', payload)); } catch (e) { setError(e); } finally { setPending(false); }
  };
  const open = (next: StockAction) => { setError(null); setNotes(''); setCondition('GOOD'); setRecipientId(''); setAction(next); };
  return <section className="order-warehouse"><div className="bom-summary"><h2>{translateUi("تجهيز المخزن")}</h2><div className="bom-actions">
    <IconButton label={translateUi("تحديث تجهيز المخزن")} onClick={() => void query.refetch()} disabled={query.isFetching || pending}><RefreshCw size={18} /></IconButton>
    {formatDisplay(manage && can(user, 'stock.reserve') && <button disabled={pending} onClick={() => void send('reserve', { version: stock.version })}><PackageCheck size={17} />{translateUi("حجز المتاح")}</button>)}
    {formatDisplay(manage && can(user, 'stock.issue') && <button className="primary" disabled={pending || !selected.length} onClick={() => open({ kind: 'issue', version: stock.version, pieceIds: selected })}><Send size={17} />{translateUi("صرف المحدد (")}{' '}{formatDisplay(selected.length)})</button>)}
  </div></div>{formatDisplay(notice && <div className="notice" role="status"><Check size={17} />{formatDisplay(notice)}</div>)}{formatDisplay(!action && <ErrorBox error={error} />)}
    {formatDisplay(query.isPending ? <Busy /> : query.error ? <QueryError error={query.error} retry={() => void query.refetch()} /> : stock && <>
      <div className="table-wrap"><table><thead><tr><th>{translateUi("الموضع")}</th><th>{translateUi("القطعة")}</th><th>{translateUi("المطلوب")}</th><th>{translateUi("محجوز")}</th><th>{translateUi("مصروف")}</th><th>{translateUi("مركب")}</th><th>{translateUi("غير مغطى")}</th><th>{translateUi("التجهيز")}</th></tr></thead><tbody>{formatDisplay(stock.requirements.map(r => <tr key={r.requirementId}>
        <td dir="ltr"><strong>{formatDisplay(r.positionCode)}</strong></td><td><PartName code={r.partCode} name={r.partName} /></td><td>{formatDisplay(r.required)}</td><td>{formatDisplay(r.reserved)}</td><td>{formatDisplay(r.issued)}</td><td>{formatDisplay(r.installed)}</td><td>{formatDisplay(r.missing)}</td>
        <td>{formatDisplay(r.preparationStatus && <Badge status={r.preparationStatus} />)}{formatDisplay(manage && can(user, 'stock.reserve') && r.missing > 0 && (!r.preparationStatus || r.preparationStatus === 'FULFILLED') && <button className="stock-request" onClick={() => open({ kind: 'preparation', version: stock.version, requirement: r })} disabled={pending}><ClipboardList size={16} />{translateUi("طلب تجهيز")}{' '}{formatDisplay(r.positionCode)}</button>)}</td>
      </tr>))}</tbody></table></div>
      <h3 className="stock-subheading">{translateUi("القطع المخصصة للطلب")}<span className="count">{formatDisplay(stock.pieces.length)}</span></h3>
      <div className="table-wrap"><table><thead><tr>{formatDisplay(manage && can(user, 'stock.issue') && <th>{translateUi("تحديد")}</th>)}<th>Serial Number</th><th>{translateUi("الموضع")}</th><th>{translateUi("الحالة")}</th><th>{translateUi("المستلم")}</th><th>{translateUi("الإجراءات")}</th></tr></thead><tbody>{formatDisplay(stock.pieces.map(p => <tr key={p.id}>
        {formatDisplay(manage && can(user, 'stock.issue') && <td>{formatDisplay(p.status === 'RESERVED' && <input aria-label={formatDisplay(translateUi("تحديد {{v0}}", {v0: p.serialNumber}))} type="checkbox" checked={selected.includes(p.id)} disabled={pending || (!selected.includes(p.id) && selected.length >= 200)} onChange={e => setSelection(e.target.checked ? [...selected, p.id] : selected.filter(id => id !== p.id))} />)}</td>)}
        <td><strong dir="ltr">{formatDisplay(p.serialNumber)}</strong></td><td dir="ltr">{formatDisplay(p.positionCode)}</td><td><Badge status={p.status} /></td><td>{formatDisplay(p.recipientName || '—')}</td>
        <td>{formatDisplay(manage && can(user, 'stock.return') && p.status === 'ISSUED' && <IconButton label={formatDisplay(translateUi("إرجاع {{v0}}", {v0: p.serialNumber}))} disabled={pending} onClick={() => open({ kind: 'return', version: stock.version, piece: p })}><RotateCcw size={17} /></IconButton>)}</td>
      </tr>))}</tbody></table>{formatDisplay(!stock.pieces.length && <div className="state">{translateUi("لا توجد قطع مخصصة")}</div>)}</div>
    </>)}
    {formatDisplay(action && <Modal title={formatDisplay(action.kind === 'issue' ? 'صرف القطع' : action.kind === 'return' ? translateUi("إرجاع {{v0}}", {v0: action.piece!.serialNumber}) : translateUi("طلب تجهيز {{v0}}", {v0: action.requirement!.positionCode}))} close={() => { if (!pending) setAction(null); }}><form className="form-stack" onSubmit={e => {
      e.preventDefault(); void send(action.kind === 'return' ? `pieces/${translateDisplay(action.piece!.id)}/return` : action.kind, { version: action.version, pieceIds: action.pieceIds, requirementId: action.requirement?.requirementId, recipientId: recipientId || null, condition, notes });
    }}><ErrorBox error={error} />{formatDisplay(action.kind === 'issue' && <><p>{formatDisplay(action.pieceIds!.length)} {translateUi("قطعة")}</p>{formatDisplay(recipients.error && <QueryError error={recipients.error} retry={() => void recipients.refetch()} />)}<label>{translateUi("المستلم")}<select aria-label={translateUi("المستلم")} value={recipientId} onChange={e => setRecipientId(e.target.value)} disabled={pending || recipients.isPending}><option value="">{translateUi("مسؤول الطلب ·")}{' '}{formatDisplay(responsibleName)}</option>{formatDisplay(recipients.data?.map(r => <option key={r.id} value={r.id}>{formatDisplay(r.name)}</option>))}</select></label></>)}
      {formatDisplay(action.kind === 'preparation' && <p>{formatDisplay(action.requirement!.partName)} · {formatDisplay(action.requirement!.missing)} {translateUi("قطعة")}</p>)}
      {formatDisplay(action.kind === 'return' && <label>{translateUi("حالة المرتجع")}<select aria-label={translateUi("حالة المرتجع")} value={condition} onChange={e => setCondition(e.target.value)}><option value="GOOD">{translateUi("سليم ومفحوص · متاح")}</option><option value="DEFECTIVE">{translateUi("معيب · عزل")}</option></select></label>)}
      <label>{formatDisplay(action.kind === 'return' ? 'سبب الإرجاع' : 'ملاحظات')}<textarea aria-label={formatDisplay(action.kind === 'return' ? 'سبب الإرجاع' : 'ملاحظات الحركة')} value={notes} onChange={e => setNotes(e.target.value)} maxLength={1000} required={action.kind === 'return'} rows={3} /></label>
      <div className="form-actions"><button className="primary" disabled={pending || (action.kind === 'return' && !notes.trim()) || (action.kind === 'issue' && (recipients.isPending || recipients.isError))}><Check size={17} />{formatDisplay(action.kind === 'issue' ? 'تأكيد الصرف' : action.kind === 'return' ? 'تأكيد الإرجاع' : 'إرسال للتصنيع')}</button><button type="button" disabled={pending} onClick={() => setAction(null)}>{translateUi("رجوع")}</button></div>
    </form></Modal>)}
  </section>;
}
export function PreparationsPage() {
  useLanguage();
  const { user } = useAuth(); const client = useQueryClient();
  const [params, setParams] = useSearchParams(); const selectedId = params.get('preparation');
  const allowed = can(user, 'orders.view') && (can(user, 'stock.view') || can(user, 'preparation.manage'));
  const query = useQuery({ queryKey: ['warehouse', 'preparations'], queryFn: () => api<Preparation[]>('/warehouse/preparations'), enabled: allowed });
  const [status, setStatus] = useState('all'), [pending, setPending] = useState(false), [error, setError] = useState<unknown>(null);
  const selected = query.data?.find(p => p.id === selectedId);
  if (!allowed) return <Denied />;
  return <><div className="page-heading"><div><div className="eyebrow">{translateUi("التصنيع")}</div><h1>{translateUi("نواقص وتجهيز القطع")}</h1></div><IconButton label={translateUi("تحديث النواقص")} disabled={query.isFetching || pending} onClick={() => void query.refetch()}><RefreshCw size={18} /></IconButton></div><ErrorBox error={error} />
    {selectedId && <Modal title={translateUi('طلب التجهيز المحدد')} close={() => { if (pending) return; const next = new URLSearchParams(params); next.delete('preparation'); setParams(next); }}>
      {query.isPending ? <Busy /> : query.error ? <QueryError error={query.error} retry={() => void query.refetch()} /> : selected ? <section className="preparation-selected">
        <h3>{formatDisplay(selected.partName)}</h3><p dir="ltr">{selected.orderCode} · {selected.positionCode}</p><Badge status={selected.status} />
        <p>{translateUi('عند الطلب: {{requested}} · المتبقي: {{missing}}', { requested: selected.requestedQuantity, missing: selected.missing })}</p><p>{formatDisplay(selected.notes)}</p>
        <Link className="inline-link" to={`/orders/${selected.orderId}`}>{translateUi('فتح طلب التصنيع')}</Link>
        {can(user, 'preparation.manage') && selected.status === 'REQUESTED' && <button disabled={pending} onClick={async () => {
          setPending(true); setError(null); try { await api(`/warehouse/preparations/${selected.id}/start`, 'POST', { version: selected.version }); await invalidateStock(client); } catch (e) { setError(e); } finally { setPending(false); }
        }}>{translateUi('بدء التجهيز المحدد')}</button>}<ErrorBox error={error} />
      </section> : <p role="status">{translateUi('طلب التجهيز غير متاح أو لم يعد لديك حق الوصول إليه.')}</p>}
    </Modal>}
    {formatDisplay(query.isPending ? <Busy /> : query.error ? <QueryError error={query.error} retry={() => void query.refetch()} /> : <StockTable rows={(query.data ?? []).filter(p => status === 'all' || p.status === status)} searchText={r => `${translateDisplay(r.orderCode)} ${translateDisplay(r.orderTitle)} ${translateDisplay(r.partCode)} ${translateDisplay(r.partName)} ${translateDisplay(r.positionCode)}`} filters={<select aria-label={translateUi("حالة التجهيز")} value={status} onChange={e => setStatus(e.target.value)}><option value="all">{translateUi("كل الحالات")}</option>{formatDisplay(['REQUESTED', 'IN_PROGRESS', 'FULFILLED', 'CANCELLED'].map(s => <option key={s} value={s}>{formatDisplay(states[s])}</option>))}</select>} columns={[
      { label: 'الطلب', cell: r => <><Link className="inline-link" to={`/orders/${translateDisplay(r.orderId)}`}>{formatDisplay(r.orderTitle)}</Link><span className="record-code" dir="ltr">{formatDisplay(r.orderCode)}</span></> }, { label: 'الموضع', cell: r => r.positionCode }, { label: 'القطعة', cell: r => <PartName code={r.partCode} name={r.partName} /> },
      { label: 'عند الطلب', cell: r => r.requestedQuantity }, { label: 'المتبقي', cell: r => r.missing }, { label: 'الحالة', cell: r => <Badge status={r.status} /> }, { label: 'ملاحظات', cell: r => <span className="cell-notes">{formatDisplay(r.notes || '—')}</span> },
      { label: 'الإجراءات', cell: r => <div className="bom-actions">{formatDisplay(can(user, 'preparation.manage') && r.status === 'REQUESTED' && <button disabled={pending} onClick={async () => {
        setPending(true); setError(null); try { await api(`/warehouse/preparations/${translateDisplay(r.id)}/start`, 'POST', { version: r.version }); await invalidateStock(client); } catch (e) { setError(e); } finally { setPending(false); }
      }}><Play size={16} />{translateUi("بدء التجهيز")}</button>)}{formatDisplay(can(user, 'stock.view') && can(user, 'raw.issue') && r.status === 'IN_PROGRESS' && r.missing > 0 && <Link className="inline-link" to={`/raw-stock?preparation=${translateDisplay(r.id)}`}><Send size={16} />{translateUi("صرف خامة")}</Link>)}</div> },
    ]} />)}
  </>;
}
