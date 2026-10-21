import { formatDisplay } from './i18n';
import { translateUi, translateDisplay, useLanguage, dateFormatter, getLocale, getDirection } from './i18n';
import { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { useQuery, localizedKey } from './localizedQuery';
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
const date = dateFormatter({ dateStyle: 'short', timeStyle: 'short', timeZone: 'Africa/Cairo' });
const number = { format: (value: number) => new Intl.NumberFormat(getLocale(), { maximumFractionDigits: 12, useGrouping: false }).format(value) };
function measured(value: number, unit: string) { return `${translateDisplay(number.format(value / (unit === 'AREA' ? 1000000 : 1000)))} ${translateDisplay(unit === 'AREA' ? 'م²' : 'م')}`; }
function size(length: number, width: number | null, diameter?: number | null) { return translateUi("{{v0}}{{v1}} مم", {v0: number.format(length), v1: width != null ? ` × ${translateDisplay(number.format(width))}` : diameter != null ? ` × Ø ${translateDisplay(number.format(diameter))}` : ''}); }
function State({ value }: { value: string }) {
  useLanguage(); return <span className={`badge ${translateDisplay(value === 'AVAILABLE' ? 'active' : value === 'SCRAPPED' ? 'inactive' : 'draft')}`}>{formatDisplay(statusNames[value])}</span>; }

export function RawStockPage() {
  useLanguage();
  const { user } = useAuth(); const client = useQueryClient(); const [params] = useSearchParams();
  const allowed = can(user, 'stock.view'), operations = allowed && can(user, 'orders.view');
  const [tab, setTab] = useState(params.has('preparation') ? 'units' : 'balances'), [status, setStatus] = useState('all');
  const [receiving, setReceiving] = useState(false), [action, setAction] = useState<{ kind: 'issue' | 'return' | 'cut'; unit: Unit } | null>(null), [notice, setNotice] = useState('');
  const balances = useQuery({ queryKey: ['warehouse', 'raw', 'balances'], queryFn: () => api<Balance[]>(`${translateDisplay(root)}/balances`), enabled: allowed && tab === 'balances' });
  const units = useQuery({ queryKey: ['warehouse', 'raw', 'units'], queryFn: () => api<Unit[]>(`${translateDisplay(root)}/units`), enabled: allowed && tab === 'units' });
  const movements = useQuery({ queryKey: ['warehouse', 'raw', 'movements'], queryFn: () => api<Movement[]>(`${translateDisplay(root)}/movements`), enabled: allowed && tab === 'movements' });
  const cuts = useQuery({ queryKey: ['warehouse', 'raw', 'cuts'], queryFn: () => api<Cut[]>(`${translateDisplay(root)}/cuts`), enabled: allowed && tab === 'cuts' });
  const preparations = useQuery({ queryKey: ['warehouse', 'preparations'], queryFn: () => api<Preparation[]>('/warehouse/preparations'), enabled: operations && (can(user, 'raw.issue') || can(user, 'raw.cut')) });
  if (!allowed) return <Denied />;
  const query = tab === 'balances' ? balances : tab === 'units' ? units : tab === 'movements' ? movements : cuts;
  const saved = async (message: string) => { setAction(null); setReceiving(false); setNotice(message); await invalidateStock(client); };
  return <div className="raw-stock"><div className="page-heading"><div><div className="eyebrow">{translateUi("المخزن الرئيسي")}</div><h1>{translateUi("الخامات والقص")}</h1></div><div className="bom-actions"><IconButton label={translateUi("تحديث الخامات")} disabled={query.isFetching} onClick={() => { void query.refetch(); if (operations) void preparations.refetch(); }}><RefreshCw size={18} /></IconButton>
    {formatDisplay(can(user, 'raw.receive') && can(user, 'catalog.view') && <button className="primary" onClick={() => setReceiving(true)}><PackagePlus size={18} />{translateUi("استلام خامات")}</button>)}</div></div>
    {formatDisplay(notice && <div className="notice" role="status"><Check size={17} />{formatDisplay(notice)}</div>)}
    {formatDisplay(params.has('preparation') && operations && <div className="raw-context"><Link className="inline-link" to="/preparations">{translateUi("نواقص وتجهيز القطع")}</Link><span>{formatDisplay(preparations.data?.find(p => p.id === params.get('preparation'))?.partName)}</span></div>)}
    <div className="view-tabs" role="tablist" aria-label={translateUi("عرض الخامات")}>{formatDisplay([['balances', 'أرصدة الخامات'], ['units', 'الخامات والبواقي'], ['movements', 'حركات الخامات'], ['cuts', 'سجل القص']].map(([value, label]) => <button key={value} role="tab" aria-selected={tab === value} onClick={() => setTab(value)}>{formatDisplay(label)}</button>))}</div>
    {formatDisplay(query.isPending ? <Busy /> : query.error ? <QueryError error={query.error} retry={() => void query.refetch()} /> : tab === 'balances' ? <StockTable key={tab} rows={balances.data ?? []} searchText={r => `${translateDisplay(r.partCode)} ${translateDisplay(r.partName)}`} columns={[
      { label: 'الخامة', cell: r => <><strong>{formatDisplay(r.partName)}</strong><span className="record-code" dir="ltr">{formatDisplay(r.partCode)}</span></> }, { label: 'وحدة الرصيد', cell: r => r.stockUnit === 'AREA' ? 'متر مربع' : 'متر طولي' },
      { label: 'متاح بالمخزن', cell: r => measured(r.available, r.stockUnit) }, { label: 'مصروف للتصنيع', cell: r => measured(r.issued, r.stockUnit) }, { label: 'هالك فعلي', cell: r => measured(r.scrap, r.stockUnit) },
    ]} /> : tab === 'units' ? <StockTable key={tab} rows={(units.data ?? []).filter(u => status === 'all' || u.status === status)} searchText={r => `${translateDisplay(r.code)} ${translateDisplay(r.partCode)} ${translateDisplay(r.partName)} ${translateDisplay(r.parentCode ?? '')} ${translateDisplay(r.orderCode ?? '')} ${translateDisplay(r.location)}`} filters={<select aria-label={translateUi("حالة الخامة")} value={status} onChange={e => setStatus(e.target.value)}><option value="all">{translateUi("كل الحالات")}</option>{formatDisplay(Object.entries(statusNames).map(([v, label]) => <option key={v} value={v}>{formatDisplay(label)}</option>))}</select>} columns={[
      { label: 'كود الوحدة', cell: r => <><strong dir="ltr">{formatDisplay(r.code)}</strong>{formatDisplay(r.parentCode && <span className="record-code" dir="ltr">{formatDisplay(r.parentCode)}</span>)}</> }, { label: 'الخامة', cell: r => <><strong>{formatDisplay(r.partName)}</strong><span className="record-code" dir="ltr">{formatDisplay(r.partCode)}</span></> },
      { label: 'المقاسات', cell: r => <span className="dimensions" dir="ltr">{formatDisplay(size(r.lengthMm, r.widthMm, r.diameterMm))}</span> }, { label: 'الكمية', cell: r => measured(r.stockUnit === 'AREA' ? r.lengthMm * r.widthMm! : r.lengthMm, r.stockUnit) },
      { label: 'الموقع', cell: r => r.location || '—' }, { label: 'الحالة', cell: r => <State value={r.status} /> }, { label: 'الطلب / الموضع', cell: r => r.orderCode ? `${translateDisplay(r.orderCode)} · ${translateDisplay(r.positionCode)}` : '—' },
      { label: 'الإجراءات', cell: r => <div className="row-actions">{formatDisplay(operations && can(user, 'raw.issue') && r.status === 'AVAILABLE' && <IconButton label={formatDisplay(translateUi("صرف خامة {{v0}}", {v0: r.code}))} onClick={() => setAction({ kind: 'issue', unit: r })}><Send size={17} /></IconButton>)}
        {formatDisplay(operations && can(user, 'raw.cut') && r.status === 'ISSUED' && <IconButton label={formatDisplay(translateUi("قص {{v0}}", {v0: r.code}))} onClick={() => setAction({ kind: 'cut', unit: r })}><Scissors size={17} /></IconButton>)}
        {formatDisplay(operations && can(user, 'raw.issue') && r.status === 'ISSUED' && <IconButton label={formatDisplay(translateUi("إرجاع خامة {{v0}}", {v0: r.code}))} onClick={() => setAction({ kind: 'return', unit: r })}><RotateCcw size={17} /></IconButton>)}</div> },
    ]} /> : tab === 'movements' ? <StockTable key={tab} rows={movements.data ?? []} searchText={r => `${translateDisplay(r.code)} ${translateDisplay(r.partCode)} ${translateDisplay(r.orderCode ?? '')} ${translateDisplay(r.reference)} ${translateDisplay(r.actorName)}`} columns={[
      { label: 'التاريخ · القاهرة', cell: r => date.format(new Date(r.createdAt)) }, { label: 'الخامة', cell: r => <><strong dir="ltr">{formatDisplay(r.code)}</strong><span className="record-code" dir="ltr">{formatDisplay(r.partCode)}</span></> },
      { label: 'الحركة', cell: r => movementNames[r.action] }, { label: 'الكمية', cell: r => measured(r.amount, r.stockUnit) }, { label: 'الطلب / الموضع', cell: r => r.orderCode ? `${translateDisplay(r.orderCode)} · ${translateDisplay(r.positionCode)}` : '—' },
      { label: 'المنفذ', cell: r => r.actorName }, { label: 'المرجع', cell: r => <span className="cell-notes">{formatDisplay(r.reference || '—')}</span> }, { label: 'ملاحظات', cell: r => <span className="cell-notes">{formatDisplay(r.notes || '—')}</span> },
    ]} /> : <StockTable key={tab} rows={cuts.data ?? []} searchText={r => `${translateDisplay(r.sourceCode)} ${translateDisplay(r.orderCode)} ${translateDisplay(r.serials.join(' '))}`} columns={[
      { label: 'التاريخ · القاهرة', cell: r => date.format(new Date(r.createdAt)) }, { label: 'الخامة الأصلية', cell: r => <span dir="ltr">{formatDisplay(r.sourceCode)}</span> }, { label: 'الطلب / الموضع', cell: r => `${translateDisplay(r.orderCode)} · ${translateDisplay(r.positionCode)}` },
      { label: 'القطع الناتجة', cell: r => <>{formatDisplay(r.quantity)}<span className="cell-notes" dir="ltr">{formatDisplay(r.serials.join(', '))}</span></> }, { label: 'التخطيط', cell: r => <><span className="dimensions" dir="ltr">{formatDisplay(size(r.outputLengthMm, r.outputWidthMm))}</span><div className="muted">{formatDisplay(r.columns)} {translateUi("أعمدة")}{' '}{formatDisplay(r.rotated ? ' · مدوّر' : '')}</div></> },
      { label: 'المستهلك في القطع', cell: r => measured(r.outputAmount, r.stockUnit) }, { label: 'الباقي المرتجع', cell: r => measured(r.returnedAmount, r.stockUnit) }, { label: 'الهالك', cell: r => measured(r.scrapAmount, r.stockUnit) }, { label: 'المنفذ / ملاحظات', cell: r => <>{formatDisplay(r.actorName)}<span className="cell-notes">{formatDisplay(r.notes)}</span></> },
    ]} />)}
    {formatDisplay(receiving && <RawReceipt saved={() => saved('تم استلام الخامات')} close={() => setReceiving(false)} />)}
    {formatDisplay(action && <RawAction key={`${translateDisplay(action.kind)}-${translateDisplay(action.unit.id)}`} action={action} preparations={preparations.data} queryError={preparations.error} retry={() => void preparations.refetch()} preferred={params.get('preparation')} saved={() => saved('تم حفظ حركة الخامة')} close={() => setAction(null)} />)}
  </div>;
}

function RawReceipt({ saved, close }: { saved: () => Promise<void>; close: () => void }) {
  useLanguage();
  const parts = useQuery({ queryKey: ['/parts'], queryFn: () => api<Part[]>('/parts') });
  const [partId, setPartId] = useState(''), [codes, setCodes] = useState(''), [length, setLength] = useState(''), [width, setWidth] = useState('');
  const [location, setLocation] = useState(''), [reference, setReference] = useState(''), [notes, setNotes] = useState(''), [pending, setPending] = useState(false), [error, setError] = useState<unknown>(null);
  const part = parts.data?.find(p => p.id === partId);
  return <Modal title={translateUi("استلام خامات")} close={() => { if (!pending) close(); }}><form className="form-stack" onSubmit={async e => {
    e.preventDefault(); setPending(true); setError(null);
    try { await api(`${translateDisplay(root)}/receipts`, 'POST', { partId, codes: codes.split(/\r?\n/).map(s => s.trim()).filter(Boolean), lengthMm: Number(length), widthMm: part?.shape === 'RECTANGULAR' ? Number(width) : null, location, reference, notes }); await saved(); } catch (e) { setError(e); } finally { setPending(false); }
  }}><ErrorBox error={error} />{formatDisplay(parts.error && <QueryError error={parts.error} retry={() => void parts.refetch()} />)}
    <label>{translateUi("صنف الخامة")}<select aria-label={translateUi("صنف الخامة")} value={partId} onChange={e => { const p = parts.data?.find(p => p.id === e.target.value); setPartId(e.target.value); setLength(String(p?.lengthMm ?? '')); setWidth(String(p?.widthMm ?? '')); }} required disabled={parts.isPending || parts.isError}><option value="">{translateUi("اختر الخامة")}</option>{formatDisplay(parts.data?.filter(p => p.active && p.kind === 'MEASURED_STOCK').map(p => <option key={p.id} value={p.id}>{formatDisplay(p.code)} · {formatDisplay(p.name)}</option>))}</select></label>
    <label>{translateUi("أكواد الوحدات · كود لكل سطر")}<textarea aria-label={translateUi("أكواد وحدات الخامة")} dir="ltr" rows={4} value={codes} onChange={e => setCodes(e.target.value)} required maxLength={13000} /></label>
    <div className="form-grid"><label>{translateUi("الطول الفعلي · مم")}<input aria-label={translateUi("الطول الفعلي")} type="number" min="0.001" max="999999.999" step="0.001" required value={length} onChange={e => setLength(e.target.value)} /></label>
      {formatDisplay(part?.shape === 'RECTANGULAR' && <label>{translateUi("العرض الفعلي · مم")}<input aria-label={translateUi("العرض الفعلي")} type="number" min="0.001" max="999999.999" step="0.001" required readOnly={part.stockUnit === 'LENGTH'} value={width} onChange={e => setWidth(e.target.value)} /></label>)}
      {formatDisplay(part?.shape === 'ROUND' && <div className="raw-profile">{translateUi("القطر:")}<span dir="ltr">{formatDisplay(part.diameterMm)} {translateUi("مم")}</span></div>)}</div>
    <label>{translateUi("الموقع بالمخزن")}<input value={location} onChange={e => setLocation(e.target.value)} maxLength={120} /></label><label>{translateUi("مرجع الاستلام")}<input value={reference} onChange={e => setReference(e.target.value)} maxLength={120} /></label><label>{translateUi("ملاحظات")}<textarea value={notes} onChange={e => setNotes(e.target.value)} maxLength={1000} rows={2} /></label>
    <div className="form-actions"><button className="primary" disabled={pending || parts.isPending || parts.isError}><PackagePlus size={17} />{translateUi("تأكيد استلام الخامات")}</button><button type="button" onClick={close} disabled={pending}>{translateUi("إلغاء")}</button></div>
  </form></Modal>;
}

function RawAction({ action, preparations, queryError, retry, preferred, saved, close }: { action: { kind: 'issue' | 'return' | 'cut'; unit: Unit }; preparations?: Preparation[]; queryError: unknown; retry: () => void; preferred: string | null; saved: () => Promise<void>; close: () => void }) {
  useLanguage();
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
        if (preview) { await api(`${translateDisplay(root)}/units/${translateDisplay(unit.id)}/cut`, 'POST', preview.payload); await saved(); }
        else {
          const payload = { version: unit.version, preparationVersion: preparation!.version, serials: serialText.split(/\r?\n/).map(s => s.trim()).filter(Boolean), columns, rotated, returnFirst, returnSecond, notes };
          setPreview({ plan: await api<Plan>(`${translateDisplay(root)}/units/${translateDisplay(unit.id)}/cut-preview`, 'POST', payload), payload });
        }
      } else { await api(`${translateDisplay(root)}/units/${translateDisplay(unit.id)}/${translateDisplay(kind)}`, 'POST', { version: unit.version, preparationId, preparationVersion: preparation?.version, notes }); await saved(); }
    } catch (e) { setError(e); setPreview(null); } finally { setPending(false); }
  };
  return <Modal title={formatDisplay(title)} close={() => { if (!pending) close(); }}><form className="form-stack" onSubmit={e => { e.preventDefault(); void submit(); }}><ErrorBox error={error} />
    <div className="raw-source"><strong dir="ltr">{formatDisplay(unit.code)}</strong><span>{formatDisplay(unit.partName)}</span><span className="dimensions" dir="ltr">{formatDisplay(size(unit.lengthMm, unit.widthMm, unit.diameterMm))}</span></div>
    {formatDisplay(kind !== 'return' && (queryError ? <QueryError error={queryError} retry={retry} /> : !preparations ? <Busy /> : kind === 'issue' ? <label>{translateUi("طلب التجهيز")}<select aria-label={translateUi("طلب التجهيز")} required value={preparationId} disabled={pending} onChange={e => setPreparationId(e.target.value)}><option value="">{translateUi("اختر طلب التجهيز")}</option>{formatDisplay(preparations.filter(p => p.status === 'IN_PROGRESS' && p.missing > 0).map(p => <option key={p.id} value={p.id}>{formatDisplay(p.orderCode)} · {formatDisplay(p.positionCode)} · {formatDisplay(p.partName)} · {formatDisplay(p.missing)} {translateUi("قطعة")}</option>))}</select></label> : preparation ? <div className="raw-context"><strong>{formatDisplay(preparation.partName)}</strong><span>{formatDisplay(preparation.orderCode)} · {formatDisplay(preparation.positionCode)} {translateUi("· المتبقي")}{' '}{formatDisplay(preparation.missing)} {translateUi("قطعة")}</span></div> : <div className="error" role="alert">{translateUi("طلب التجهيز غير موجود. حدّث البيانات.")}</div>))}
    {formatDisplay(kind === 'cut' && <><label>{translateUi("الأرقام المسلسلة الناتجة · رقم لكل سطر")}<textarea aria-label={translateUi("الأرقام المسلسلة الناتجة")} dir="ltr" value={serialText} required rows={4} maxLength={13000} disabled={pending} onChange={e => { setSerialText(e.target.value); reset(); }} /></label>
      {formatDisplay(unit.stockUnit === 'AREA' && <><label>{translateUi("عدد الأعمدة")}<input aria-label={translateUi("عدد الأعمدة")} type="number" min={1} max={200} step={1} value={columns} required disabled={pending} onChange={e => { setColumns(Number(e.target.value)); reset(); }} /></label><label className="check-line"><input type="checkbox" checked={rotated} disabled={pending} onChange={e => { setRotated(e.target.checked); reset(); }} />{translateUi("تدوير القطع 90 درجة")}</label></>)}
      <fieldset><legend>{translateUi("البواقي الصالحة")}</legend><label className="check-line"><input aria-label={translateUi("إرجاع باقي الطول")} type="checkbox" checked={returnFirst} disabled={pending} onChange={e => { setReturnFirst(e.target.checked); reset(); }} />{translateUi("إرجاع باقي الطول للمخزن")}</label>
        {formatDisplay(unit.stockUnit === 'AREA' && <label className="check-line"><input aria-label={translateUi("إرجاع باقي العرض")} type="checkbox" checked={returnSecond} disabled={pending} onChange={e => { setReturnSecond(e.target.checked); reset(); }} />{translateUi("إرجاع باقي العرض للمخزن")}</label>)}</fieldset></>)}
    <label>{formatDisplay(kind === 'return' ? 'سبب إرجاع الخامة' : 'ملاحظات / سبب الهالك')}<textarea aria-label={formatDisplay(kind === 'return' ? 'سبب إرجاع الخامة' : 'ملاحظات الخامة')} value={notes} disabled={pending} required={kind === 'return' || (kind === 'cut' && (!returnFirst || (unit.stockUnit === 'AREA' && !returnSecond)))} maxLength={1000} rows={2} onChange={e => { setNotes(e.target.value); reset(); }} /></label>
    {formatDisplay(preview && <section className="cut-preview" aria-label={translateUi("معاينة القص")}><h3>{translateUi("نتيجة القص")}</h3><dl><div><dt>{translateUi("القطع الناتجة")}</dt><dd>{formatDisplay(preview.plan.quantity)} · {formatDisplay(preview.plan.rows)} {translateUi("صفوف ×")}{' '}{formatDisplay(preview.plan.columns)} {translateUi("أعمدة")}</dd></div><div><dt>{translateUi("مقاس القطعة")}</dt><dd dir="ltr">{formatDisplay(size(preview.plan.outputLengthMm, preview.plan.outputWidthMm))}</dd></div><div><dt>{translateUi("في القطع")}</dt><dd>{formatDisplay(measured(preview.plan.outputAmount, unit.stockUnit))}</dd></div><div><dt>{translateUi("مرتجع للمخزن")}</dt><dd>{formatDisplay(measured(preview.plan.returnedAmount, unit.stockUnit))}</dd></div><div><dt>{translateUi("هالك فعلي")}</dt><dd>{formatDisplay(measured(preview.plan.scrapAmount, unit.stockUnit))}</dd></div></dl>
      {formatDisplay(preview.plan.remainders.map(r => <div className="cut-remainder" key={r.index}><span dir="ltr">{formatDisplay(size(r.lengthMm, r.widthMm))}</span><span className={`badge ${translateDisplay(r.returned ? 'active' : 'inactive')}`}>{formatDisplay(r.returned ? 'مرتجع للمخزن' : 'هالك')}</span></div>))}
    </section>)}
    <div className="form-actions"><button className="primary" disabled={pending || (kind !== 'return' && (!preparation || !!queryError))}>{formatDisplay(kind === 'cut' ? preview ? <><Scissors size={17} />{translateUi("تأكيد القص والاستلام")}</> : <><Eye size={17} />{translateUi("معاينة القص")}</> : <><Check size={17} />{formatDisplay(kind === 'issue' ? 'تأكيد صرف الخامة' : 'تأكيد إرجاع الخامة')}</>)}</button><button type="button" disabled={pending} onClick={close}>{translateUi("رجوع")}</button></div>
  </form></Modal>;
}
