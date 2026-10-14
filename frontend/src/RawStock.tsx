import { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Check, Eye, PackagePlus, RefreshCw, RotateCcw, Scissors, Send } from 'lucide-react';
import { api } from './api';
import { can, useAuth } from './access';
import { Busy, Denied, ErrorBox, IconButton, Modal, QueryError } from './ui';
import { StockTable, invalidateStock, type Preparation } from './Warehouse';
import type { Part } from './catalog';

type Unit = { id: string; code: string; partId: string; partCode: string; partName: string; stockUnit: string; shape: string; lengthMm: number; widthMm: number | null; diameterMm: number | null; location: string; status: string; version: number; preparationId: string | null; parentId: string | null; parentCode: string | null; orderCode: string | null; positionCode: string | null };
type Balance = { partId: string; partCode: string; partName: string; stockUnit: string; available: number; issued: number; scrap: number };
type Movement = { id: string; code: string; partCode: string; stockUnit: string; action: string; amount: number; orderCode: string | null; positionCode: string | null; actorName: string; reference: string; notes: string; createdAt: string };
type Cut = { id: string; sourceCode: string; stockUnit: string; orderCode: string; positionCode: string; quantity: number; outputAmount: number; returnedAmount: number; scrapAmount: number; rotated: boolean; columns: number; outputLengthMm: number; outputWidthMm: number | null; actorName: string; notes: string; createdAt: string; serials: string[] };
type Plan = { stockUnit: string; sourceAmount: number; outputLengthMm: number; outputWidthMm: number | null; quantity: number; rows: number; columns: number; outputAmount: number; returnedAmount: number; scrapAmount: number; remainders: { index: number; lengthMm: number; widthMm: number | null; amount: number; returned: boolean }[] };
const root = '/warehouse/raw';
const statusNames: Record<string, string> = { AVAILABLE: 'متاح', ISSUED: 'مصروف للتصنيع', CONSUMED: 'تم قصه', SCRAPPED: 'هالك' };
const movementNames: Record<string, string> = { RECEIVED: 'استلام', ISSUED: 'صرف للتصنيع', RETURNED: 'إرجاع دون قص', CONSUMED: 'استهلاك بالقص', REMNANT_RETURNED: 'إرجاع باقي', SCRAPPED: 'هالك فعلي' };
const date = new Intl.DateTimeFormat('ar-EG', { dateStyle: 'short', timeStyle: 'short', timeZone: 'Africa/Cairo' });
const number = new Intl.NumberFormat('en-US', { maximumFractionDigits: 12, useGrouping: false });
function measured(value: number, unit: string) { return `${number.format(value / (unit === 'AREA' ? 1000000 : 1000))} ${unit === 'AREA' ? 'م²' : 'م'}`; }
function size(length: number, width: number | null, diameter?: number | null) { return `${number.format(length)}${width != null ? ` × ${number.format(width)}` : diameter != null ? ` × Ø ${number.format(diameter)}` : ''} مم`; }
function State({ value }: { value: string }) { return <span className={`badge ${value === 'AVAILABLE' ? 'active' : value === 'SCRAPPED' ? 'inactive' : 'draft'}`}>{statusNames[value]}</span>; }

export function RawStockPage() {
  const { user } = useAuth(); const client = useQueryClient(); const [params] = useSearchParams();
  const allowed = can(user, 'stock.view'), operations = allowed && can(user, 'orders.view');
  const [tab, setTab] = useState(params.has('preparation') ? 'units' : 'balances'), [status, setStatus] = useState('all');
  const [receiving, setReceiving] = useState(false), [action, setAction] = useState<{ kind: 'issue' | 'return' | 'cut'; unit: Unit } | null>(null), [notice, setNotice] = useState('');
  const balances = useQuery({ queryKey: ['warehouse', 'raw', 'balances'], queryFn: () => api<Balance[]>(`${root}/balances`), enabled: allowed && tab === 'balances' });
  const units = useQuery({ queryKey: ['warehouse', 'raw', 'units'], queryFn: () => api<Unit[]>(`${root}/units`), enabled: allowed && tab === 'units' });
  const movements = useQuery({ queryKey: ['warehouse', 'raw', 'movements'], queryFn: () => api<Movement[]>(`${root}/movements`), enabled: allowed && tab === 'movements' });
  const cuts = useQuery({ queryKey: ['warehouse', 'raw', 'cuts'], queryFn: () => api<Cut[]>(`${root}/cuts`), enabled: allowed && tab === 'cuts' });
  const preparations = useQuery({ queryKey: ['warehouse', 'preparations'], queryFn: () => api<Preparation[]>('/warehouse/preparations'), enabled: operations && (can(user, 'raw.issue') || can(user, 'raw.cut')) });
  if (!allowed) return <Denied />;
  const query = tab === 'balances' ? balances : tab === 'units' ? units : tab === 'movements' ? movements : cuts;
  const saved = async (message: string) => { setAction(null); setReceiving(false); setNotice(message); await invalidateStock(client); };
  return <div className="raw-stock"><div className="page-heading"><div><div className="eyebrow">المخزن الرئيسي</div><h1>الخامات والقص</h1></div><div className="bom-actions"><IconButton label="تحديث الخامات" disabled={query.isFetching} onClick={() => { void query.refetch(); if (operations) void preparations.refetch(); }}><RefreshCw size={18} /></IconButton>
    {can(user, 'raw.receive') && can(user, 'catalog.view') && <button className="primary" onClick={() => setReceiving(true)}><PackagePlus size={18} />استلام خامات</button>}</div></div>
    {notice && <div className="notice" role="status"><Check size={17} />{notice}</div>}
    {params.has('preparation') && operations && <div className="raw-context"><Link className="inline-link" to="/preparations">نواقص وتجهيز القطع</Link><span>{preparations.data?.find(p => p.id === params.get('preparation'))?.partName}</span></div>}
    <div className="view-tabs" role="tablist" aria-label="عرض الخامات">{[['balances', 'أرصدة الخامات'], ['units', 'الخامات والبواقي'], ['movements', 'حركات الخامات'], ['cuts', 'سجل القص']].map(([value, label]) => <button key={value} role="tab" aria-selected={tab === value} onClick={() => setTab(value)}>{label}</button>)}</div>
    {query.isPending ? <Busy /> : query.error ? <QueryError error={query.error} retry={() => void query.refetch()} /> : tab === 'balances' ? <StockTable key={tab} rows={balances.data ?? []} searchText={r => `${r.partCode} ${r.partName}`} columns={[
      { label: 'الخامة', cell: r => <><strong>{r.partName}</strong><span className="record-code" dir="ltr">{r.partCode}</span></> }, { label: 'وحدة الرصيد', cell: r => r.stockUnit === 'AREA' ? 'متر مربع' : 'متر طولي' },
      { label: 'متاح بالمخزن', cell: r => measured(r.available, r.stockUnit) }, { label: 'مصروف للتصنيع', cell: r => measured(r.issued, r.stockUnit) }, { label: 'هالك فعلي', cell: r => measured(r.scrap, r.stockUnit) },
    ]} /> : tab === 'units' ? <StockTable key={tab} rows={(units.data ?? []).filter(u => status === 'all' || u.status === status)} searchText={r => `${r.code} ${r.partCode} ${r.partName} ${r.parentCode ?? ''} ${r.orderCode ?? ''} ${r.location}`} filters={<select aria-label="حالة الخامة" value={status} onChange={e => setStatus(e.target.value)}><option value="all">كل الحالات</option>{Object.entries(statusNames).map(([v, label]) => <option key={v} value={v}>{label}</option>)}</select>} columns={[
      { label: 'كود الوحدة', cell: r => <><strong dir="ltr">{r.code}</strong>{r.parentCode && <span className="record-code" dir="ltr">{r.parentCode}</span>}</> }, { label: 'الخامة', cell: r => <><strong>{r.partName}</strong><span className="record-code" dir="ltr">{r.partCode}</span></> },
      { label: 'المقاسات', cell: r => <span className="dimensions" dir="ltr">{size(r.lengthMm, r.widthMm, r.diameterMm)}</span> }, { label: 'الكمية', cell: r => measured(r.stockUnit === 'AREA' ? r.lengthMm * r.widthMm! : r.lengthMm, r.stockUnit) },
      { label: 'الموقع', cell: r => r.location || '—' }, { label: 'الحالة', cell: r => <State value={r.status} /> }, { label: 'الطلب / الموضع', cell: r => r.orderCode ? `${r.orderCode} · ${r.positionCode}` : '—' },
      { label: 'الإجراءات', cell: r => <div className="row-actions">{operations && can(user, 'raw.issue') && r.status === 'AVAILABLE' && <IconButton label={`صرف خامة ${r.code}`} onClick={() => setAction({ kind: 'issue', unit: r })}><Send size={17} /></IconButton>}
        {operations && can(user, 'raw.cut') && r.status === 'ISSUED' && <IconButton label={`قص ${r.code}`} onClick={() => setAction({ kind: 'cut', unit: r })}><Scissors size={17} /></IconButton>}
        {operations && can(user, 'raw.issue') && r.status === 'ISSUED' && <IconButton label={`إرجاع خامة ${r.code}`} onClick={() => setAction({ kind: 'return', unit: r })}><RotateCcw size={17} /></IconButton>}</div> },
    ]} /> : tab === 'movements' ? <StockTable key={tab} rows={movements.data ?? []} searchText={r => `${r.code} ${r.partCode} ${r.orderCode ?? ''} ${r.reference} ${r.actorName}`} columns={[
      { label: 'التاريخ · القاهرة', cell: r => date.format(new Date(r.createdAt)) }, { label: 'الخامة', cell: r => <><strong dir="ltr">{r.code}</strong><span className="record-code" dir="ltr">{r.partCode}</span></> },
      { label: 'الحركة', cell: r => movementNames[r.action] }, { label: 'الكمية', cell: r => measured(r.amount, r.stockUnit) }, { label: 'الطلب / الموضع', cell: r => r.orderCode ? `${r.orderCode} · ${r.positionCode}` : '—' },
      { label: 'المنفذ', cell: r => r.actorName }, { label: 'المرجع', cell: r => <span className="cell-notes">{r.reference || '—'}</span> }, { label: 'ملاحظات', cell: r => <span className="cell-notes">{r.notes || '—'}</span> },
    ]} /> : <StockTable key={tab} rows={cuts.data ?? []} searchText={r => `${r.sourceCode} ${r.orderCode} ${r.serials.join(' ')}`} columns={[
      { label: 'التاريخ · القاهرة', cell: r => date.format(new Date(r.createdAt)) }, { label: 'الخامة الأصلية', cell: r => <span dir="ltr">{r.sourceCode}</span> }, { label: 'الطلب / الموضع', cell: r => `${r.orderCode} · ${r.positionCode}` },
      { label: 'القطع الناتجة', cell: r => <>{r.quantity}<span className="cell-notes" dir="ltr">{r.serials.join(', ')}</span></> }, { label: 'التخطيط', cell: r => <><span className="dimensions" dir="ltr">{size(r.outputLengthMm, r.outputWidthMm)}</span><div className="muted">{r.columns} أعمدة{r.rotated ? ' · مدوّر' : ''}</div></> },
      { label: 'المستهلك في القطع', cell: r => measured(r.outputAmount, r.stockUnit) }, { label: 'الباقي المرتجع', cell: r => measured(r.returnedAmount, r.stockUnit) }, { label: 'الهالك', cell: r => measured(r.scrapAmount, r.stockUnit) }, { label: 'المنفذ / ملاحظات', cell: r => <>{r.actorName}<span className="cell-notes">{r.notes}</span></> },
    ]} />}
    {receiving && <RawReceipt saved={() => saved('تم استلام الخامات')} close={() => setReceiving(false)} />}
    {action && <RawAction key={`${action.kind}-${action.unit.id}`} action={action} preparations={preparations.data} queryError={preparations.error} retry={() => void preparations.refetch()} preferred={params.get('preparation')} saved={() => saved('تم حفظ حركة الخامة')} close={() => setAction(null)} />}
  </div>;
}

function RawReceipt({ saved, close }: { saved: () => Promise<void>; close: () => void }) {
  const parts = useQuery({ queryKey: ['/parts'], queryFn: () => api<Part[]>('/parts') });
  const [partId, setPartId] = useState(''), [codes, setCodes] = useState(''), [length, setLength] = useState(''), [width, setWidth] = useState('');
  const [location, setLocation] = useState(''), [reference, setReference] = useState(''), [notes, setNotes] = useState(''), [pending, setPending] = useState(false), [error, setError] = useState<unknown>(null);
  const part = parts.data?.find(p => p.id === partId);
  return <Modal title="استلام خامات" close={() => { if (!pending) close(); }}><form className="form-stack" onSubmit={async e => {
    e.preventDefault(); setPending(true); setError(null);
    try { await api(`${root}/receipts`, 'POST', { partId, codes: codes.split(/\r?\n/).map(s => s.trim()).filter(Boolean), lengthMm: Number(length), widthMm: part?.shape === 'RECTANGULAR' ? Number(width) : null, location, reference, notes }); await saved(); } catch (e) { setError(e); } finally { setPending(false); }
  }}><ErrorBox error={error} />{parts.error && <QueryError error={parts.error} retry={() => void parts.refetch()} />}
    <label>صنف الخامة<select aria-label="صنف الخامة" value={partId} onChange={e => { const p = parts.data?.find(p => p.id === e.target.value); setPartId(e.target.value); setLength(String(p?.lengthMm ?? '')); setWidth(String(p?.widthMm ?? '')); }} required disabled={parts.isPending || parts.isError}><option value="">اختر الخامة</option>{parts.data?.filter(p => p.active && p.kind === 'MEASURED_STOCK').map(p => <option key={p.id} value={p.id}>{p.code} · {p.name}</option>)}</select></label>
    <label>أكواد الوحدات · كود لكل سطر<textarea aria-label="أكواد وحدات الخامة" dir="ltr" rows={4} value={codes} onChange={e => setCodes(e.target.value)} required maxLength={13000} /></label>
    <div className="form-grid"><label>الطول الفعلي · مم<input aria-label="الطول الفعلي" type="number" min="0.001" max="999999.999" step="0.001" required value={length} onChange={e => setLength(e.target.value)} /></label>
      {part?.shape === 'RECTANGULAR' && <label>العرض الفعلي · مم<input aria-label="العرض الفعلي" type="number" min="0.001" max="999999.999" step="0.001" required readOnly={part.stockUnit === 'LENGTH'} value={width} onChange={e => setWidth(e.target.value)} /></label>}
      {part?.shape === 'ROUND' && <div className="raw-profile">القطر: <span dir="ltr">{part.diameterMm} مم</span></div>}</div>
    <label>الموقع بالمخزن<input value={location} onChange={e => setLocation(e.target.value)} maxLength={120} /></label><label>مرجع الاستلام<input value={reference} onChange={e => setReference(e.target.value)} maxLength={120} /></label><label>ملاحظات<textarea value={notes} onChange={e => setNotes(e.target.value)} maxLength={1000} rows={2} /></label>
    <div className="form-actions"><button className="primary" disabled={pending || parts.isPending || parts.isError}><PackagePlus size={17} />تأكيد استلام الخامات</button><button type="button" onClick={close} disabled={pending}>إلغاء</button></div>
  </form></Modal>;
}

function RawAction({ action, preparations, queryError, retry, preferred, saved, close }: { action: { kind: 'issue' | 'return' | 'cut'; unit: Unit }; preparations?: Preparation[]; queryError: unknown; retry: () => void; preferred: string | null; saved: () => Promise<void>; close: () => void }) {
  const { kind, unit } = action;
  const [preparationId, setPreparationId] = useState(preferred ?? ''), [serialText, setSerialText] = useState(''), [columns, setColumns] = useState(1), [rotated, setRotated] = useState(false);
  const [returnFirst, setReturnFirst] = useState(true), [returnSecond, setReturnSecond] = useState(true), [notes, setNotes] = useState('');
  const [pending, setPending] = useState(false), [error, setError] = useState<unknown>(null), [preview, setPreview] = useState<{ plan: Plan; payload: unknown } | null>(null);
  const preparation = preparations?.find(p => p.id === (kind === 'cut' ? unit.preparationId : preparationId));
  const title = kind === 'issue' ? 'صرف خامة للتجهيز' : kind === 'return' ? 'إرجاع خامة دون قص' : 'تسجيل القص';
  const reset = () => { setPreview(null); setError(null); };
  const submit = async () => {
    setPending(true); setError(null);
    try {
      if (kind === 'cut') {
        if (preview) { await api(`${root}/units/${unit.id}/cut`, 'POST', preview.payload); await saved(); }
        else {
          const payload = { version: unit.version, preparationVersion: preparation!.version, serials: serialText.split(/\r?\n/).map(s => s.trim()).filter(Boolean), columns, rotated, returnFirst, returnSecond, notes };
          setPreview({ plan: await api<Plan>(`${root}/units/${unit.id}/cut-preview`, 'POST', payload), payload });
        }
      } else { await api(`${root}/units/${unit.id}/${kind}`, 'POST', { version: unit.version, preparationId, preparationVersion: preparation?.version, notes }); await saved(); }
    } catch (e) { setError(e); setPreview(null); } finally { setPending(false); }
  };
  return <Modal title={title} close={() => { if (!pending) close(); }}><form className="form-stack" onSubmit={e => { e.preventDefault(); void submit(); }}><ErrorBox error={error} />
    <div className="raw-source"><strong dir="ltr">{unit.code}</strong><span>{unit.partName}</span><span className="dimensions" dir="ltr">{size(unit.lengthMm, unit.widthMm, unit.diameterMm)}</span></div>
    {kind !== 'return' && (queryError ? <QueryError error={queryError} retry={retry} /> : !preparations ? <Busy /> : kind === 'issue' ? <label>طلب التجهيز<select aria-label="طلب التجهيز" required value={preparationId} disabled={pending} onChange={e => setPreparationId(e.target.value)}><option value="">اختر طلب التجهيز</option>{preparations.filter(p => p.status === 'IN_PROGRESS' && p.missing > 0).map(p => <option key={p.id} value={p.id}>{p.orderCode} · {p.positionCode} · {p.partName} · {p.missing} قطعة</option>)}</select></label> : preparation ? <div className="raw-context"><strong>{preparation.partName}</strong><span>{preparation.orderCode} · {preparation.positionCode} · المتبقي {preparation.missing} قطعة</span></div> : <div className="error" role="alert">طلب التجهيز غير موجود. حدّث البيانات.</div>)}
    {kind === 'cut' && <><label>الأرقام المسلسلة الناتجة · رقم لكل سطر<textarea aria-label="الأرقام المسلسلة الناتجة" dir="ltr" value={serialText} required rows={4} maxLength={13000} disabled={pending} onChange={e => { setSerialText(e.target.value); reset(); }} /></label>
      {unit.stockUnit === 'AREA' && <><label>عدد الأعمدة<input aria-label="عدد الأعمدة" type="number" min={1} max={200} step={1} value={columns} required disabled={pending} onChange={e => { setColumns(Number(e.target.value)); reset(); }} /></label><label className="check-line"><input type="checkbox" checked={rotated} disabled={pending} onChange={e => { setRotated(e.target.checked); reset(); }} />تدوير القطع 90 درجة</label></>}
      <fieldset><legend>البواقي الصالحة</legend><label className="check-line"><input aria-label="إرجاع باقي الطول" type="checkbox" checked={returnFirst} disabled={pending} onChange={e => { setReturnFirst(e.target.checked); reset(); }} />إرجاع باقي الطول للمخزن</label>
        {unit.stockUnit === 'AREA' && <label className="check-line"><input aria-label="إرجاع باقي العرض" type="checkbox" checked={returnSecond} disabled={pending} onChange={e => { setReturnSecond(e.target.checked); reset(); }} />إرجاع باقي العرض للمخزن</label>}</fieldset></>}
    <label>{kind === 'return' ? 'سبب إرجاع الخامة' : 'ملاحظات / سبب الهالك'}<textarea aria-label={kind === 'return' ? 'سبب إرجاع الخامة' : 'ملاحظات الخامة'} value={notes} disabled={pending} required={kind === 'return' || (kind === 'cut' && (!returnFirst || (unit.stockUnit === 'AREA' && !returnSecond)))} maxLength={1000} rows={2} onChange={e => { setNotes(e.target.value); reset(); }} /></label>
    {preview && <section className="cut-preview" aria-label="معاينة القص"><h3>نتيجة القص</h3><dl><div><dt>القطع الناتجة</dt><dd>{preview.plan.quantity} · {preview.plan.rows} صفوف × {preview.plan.columns} أعمدة</dd></div><div><dt>مقاس القطعة</dt><dd dir="ltr">{size(preview.plan.outputLengthMm, preview.plan.outputWidthMm)}</dd></div><div><dt>في القطع</dt><dd>{measured(preview.plan.outputAmount, unit.stockUnit)}</dd></div><div><dt>مرتجع للمخزن</dt><dd>{measured(preview.plan.returnedAmount, unit.stockUnit)}</dd></div><div><dt>هالك فعلي</dt><dd>{measured(preview.plan.scrapAmount, unit.stockUnit)}</dd></div></dl>
      {preview.plan.remainders.map(r => <div className="cut-remainder" key={r.index}><span dir="ltr">{size(r.lengthMm, r.widthMm)}</span><span className={`badge ${r.returned ? 'active' : 'inactive'}`}>{r.returned ? 'مرتجع للمخزن' : 'هالك'}</span></div>)}
    </section>}
    <div className="form-actions"><button className="primary" disabled={pending || (kind !== 'return' && (!preparation || !!queryError))}>{kind === 'cut' ? preview ? <><Scissors size={17} />تأكيد القص والاستلام</> : <><Eye size={17} />معاينة القص</> : <><Check size={17} />{kind === 'issue' ? 'تأكيد صرف الخامة' : 'تأكيد إرجاع الخامة'}</>}</button><button type="button" disabled={pending} onClick={close}>رجوع</button></div>
  </form></Modal>;
}
