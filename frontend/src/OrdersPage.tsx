import { formatDisplay } from './i18n';
import { SourceTextPreview } from './SourceTextPreview';
import { searchable } from './i18n';
import { translateUi, translateDisplay, useLanguage, dateFormatter, getLocale, getDirection } from './i18n';
import { useState } from 'react';
import { Files } from './Files';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { useQuery, localizedKey } from './localizedQuery';
import { useForm } from 'react-hook-form';
import { ArrowRight, Check, ChevronLeft, ChevronRight, ClipboardList, Pencil, Plus, RefreshCw, Search, Send, X } from 'lucide-react';
import { api } from './api';
import { can, useAuth } from './access';
import { Busy, Denied, ErrorBox, IconButton, Modal, QueryError } from './ui';
import { codePattern, dimensions, type Bom, type BomItem, type Machine } from './catalog';
import { OrderWarehouse } from './Warehouse';

export type Order = {
  id: string; code: string; title: string; machineId: string; bomId: string; responsibleId: string; responsibleName: string;
  deliveryAt: string; notes: string; status: 'DRAFT' | 'OPEN' | 'IN_PROGRESS' | 'AWAITING_TESTING' | 'TESTING' | 'REWORK' | 'COMPLETED' | 'CANCELLED'; machineSerial: string; machineName: string;
  modelName: string; bomRevision: number; version: number; createdAt: string; openedAt: string | null;
  cancelledAt: string | null; cancellationReason: string | null; items: BomItem[]; requiredCount: number; installedCount: number; assemblyCompletedAt: string | null; configurationVersion: number;
  testedAt: string | null; completedAt: string | null; deliveredAt: string | null; deliveryNotes: string | null;
};
type Person = { id: string; name: string; email: string };
const endpoint = '/manufacturing-orders';
export const orderStatusLabels = { DRAFT: 'مسودة', OPEN: 'مفتوح', IN_PROGRESS: 'قيد التجميع', AWAITING_TESTING: 'بانتظار تجربة التشغيل', TESTING: 'قيد تجربة التشغيل', REWORK: 'معالجة بعد التجربة', COMPLETED: 'مكتمل', CANCELLED: 'ملغي' };
const labels = orderStatusLabels;
export const orderWritePermissions = ['orders.view', 'orders.manage', 'catalog.view', 'machines.view', 'boms.view'];
const writePermissions = orderWritePermissions;
const dateFormat = dateFormatter({ dateStyle: 'medium', timeStyle: 'short', timeZone: 'Africa/Cairo' });
const inputZone = Intl.DateTimeFormat().resolvedOptions().timeZone;
function localInput(value: string) {
  const date = new Date(value);
  return new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
}
function Status({ value }: { value: Order['status'] }) {
  useLanguage(); return <span className={`badge ${translateDisplay(['DRAFT', 'IN_PROGRESS', 'TESTING'].includes(value) ? 'draft' : ['CANCELLED', 'REWORK'].includes(value) ? 'inactive' : 'active')}`}>{formatDisplay(labels[value])}</span>; }
function totals(items: BomItem[]) {
  return translateUi("{{v0}} موضع · {{v1}} قطعة · {{v2}} دقيقة", {v0: items.length, v1: items.reduce((n, i) => n + i.quantity, 0), v2: Math.round(items.reduce((n, i) => n + i.quantity * i.expectedInstallMinutes, 0) * 100) / 100});
}
function Requirements({ items }: { items: BomItem[] }) {
  useLanguage();
  return <div className="table-wrap" data-testid="order-requirements"><table className="bom-table"><thead><tr><th>{translateUi("الموضع")}</th><th>{translateUi("القطعة")}</th><th>{translateUi("المقاسات")}</th><th>{translateUi("الكمية")}</th><th>{translateUi("دقيقة / قطعة")}</th><th>{translateUi("ملاحظات")}</th></tr></thead><tbody>
    {formatDisplay(items.map(i => <tr key={i.id}><td><strong dir="ltr">{formatDisplay(i.positionCode)}</strong></td><td><strong>{formatDisplay(i.partName)}</strong><span className="record-code" dir="ltr">{formatDisplay(i.partCode)}</span></td>
      <td><span className="dimensions" dir={i.shape === 'STANDARD' ? getDirection() : 'ltr'}>{formatDisplay(dimensions(i))}</span></td><td>{formatDisplay(i.quantity)}</td><td>{formatDisplay(i.expectedInstallMinutes)}</td><td><span className="cell-notes">{formatDisplay(i.notes || '—')}</span></td></tr>))}
  </tbody></table></div>;
}
export function OrdersPage() {
  useLanguage();
  const { user } = useAuth(); const navigate = useNavigate();
  const allowed = can(user, 'orders.view') || can(user, 'orders.view-assigned');
  const manage = writePermissions.every(p => can(user, p));
  const query = useQuery({ queryKey: [endpoint], queryFn: () => api<Order[]>(endpoint), enabled: allowed });
  const [search, setSearch] = useState(''), [status, setStatus] = useState('all'), [page, setPage] = useState(0), [creating, setCreating] = useState(false);
  if (!allowed) return <Denied />;
  const rows = (query.data ?? []).filter(o => searchable(`${translateDisplay(o.code)} ${translateDisplay(o.title)} ${translateDisplay(o.machineSerial)} ${translateDisplay(o.machineName)} ${translateDisplay(o.responsibleName)}`).toLowerCase().includes(search.toLowerCase()) && (status === 'all' || o.status === status));
  const pages = Math.max(1, Math.ceil(rows.length / 10)), current = Math.min(page, pages - 1);
  return <><div className="page-heading"><div><div className="eyebrow">{formatDisplay(can(user, 'orders.view') ? 'التصنيع' : 'الطلبات المسندة لي')}</div><h1>{translateUi("طلبات التصنيع")}<span className="count">{formatDisplay(query.data?.length ?? 0)}</span></h1></div>
    {formatDisplay(manage && <button className="primary" onClick={() => setCreating(true)}><Plus size={18} />{translateUi("طلب جديد")}</button>)}</div>
    <div className="toolbar"><label className="search"><Search size={18} /><input aria-label={translateUi("بحث طلبات التصنيع")} placeholder={translateUi("بحث بالطلب أو الماكينة أو المسؤول")} value={search} onChange={e => { setSearch(e.target.value); setPage(0); }} /></label>
      <select aria-label={translateUi("حالة الطلب")} value={status} onChange={e => { setStatus(e.target.value); setPage(0); }}><option value="all">{translateUi("كل الحالات")}</option>{formatDisplay(Object.entries(labels).map(([v, label]) => <option key={v} value={v}>{formatDisplay(label)}</option>))}</select>
      <IconButton label={translateUi("تحديث الطلبات")} disabled={query.isFetching} onClick={() => void query.refetch()}><RefreshCw size={18} /></IconButton></div>
    {formatDisplay(query.isPending ? <Busy /> : query.error ? <QueryError error={query.error} retry={() => void query.refetch()} /> : <><div className="table-wrap"><table><thead><tr><th>{translateUi("الطلب")}</th><th>{translateUi("الماكينة")}</th><th>{translateUi("المسؤول")}</th><th>{translateUi("التسليم · القاهرة")}</th><th>{translateUi("الحالة")}</th></tr></thead><tbody>
      {formatDisplay(rows.slice(current * 10, current * 10 + 10).map(o => <tr key={o.id}><td><Link className="inline-link" to={`/orders/${translateDisplay(o.id)}`}><ClipboardList size={17} /><strong>{formatDisplay(o.title)}</strong></Link><span className="record-code" dir="ltr">{formatDisplay(o.code)}</span></td>
        <td><strong>{formatDisplay(o.machineName)}</strong><span className="record-code" dir="ltr">{formatDisplay(o.machineSerial)}</span></td><td>{formatDisplay(o.responsibleName)}</td><td><time dateTime={o.deliveryAt}>{formatDisplay(dateFormat.format(new Date(o.deliveryAt)))}</time>
          {formatDisplay(o.status !== 'CANCELLED' && !o.deliveredAt && new Date(o.deliveryAt).getTime() < Date.now() && <div className="field-error">{translateUi("متأخر")}</div>)}</td><td><Status value={o.status} />{formatDisplay(o.status === 'COMPLETED' && <span className="record-code">{formatDisplay(o.deliveredAt ? 'تم التسليم' : 'بانتظار التسليم الفعلي')}</span>)}</td></tr>))}
    </tbody></table>{formatDisplay(!rows.length && <div className="state">{translateUi("لا توجد طلبات")}</div>)}</div>
      <div className="pagination"><span>{formatDisplay(rows.length)} {translateUi("طلب")}</span><div><IconButton label={translateUi("الصفحة السابقة")} disabled={!current} onClick={() => setPage(current - 1)}><ChevronRight size={18} /></IconButton><span>{formatDisplay(current + 1)} / {formatDisplay(pages)}</span><IconButton label={translateUi("الصفحة التالية")} disabled={current === pages - 1} onClick={() => setPage(current + 1)}><ChevronLeft size={18} /></IconButton></div></div></>)}
    {formatDisplay(creating && <OrderForm saved={o => navigate(`/orders/${translateDisplay(o.id)}`)} cancel={() => setCreating(false)} />)}
  </>;
}
export function OrderPage() {
  useLanguage();
  const { id } = useParams();
  return <OrderDetail key={id} id={id!} />;
}
function OrderDetail({ id }: { id: string }) {
  useLanguage();
  const { user } = useAuth(); const client = useQueryClient();
  const allowed = can(user, 'orders.view') || can(user, 'orders.view-assigned');
  const query = useQuery({ queryKey: [endpoint, id], queryFn: () => api<Order>(`${translateDisplay(endpoint)}/${translateDisplay(id)}`), enabled: allowed });
  const [editing, setEditing] = useState(false), [action, setAction] = useState<{ kind: 'release' | 'cancel'; version: number } | null>(null);
  const [reason, setReason] = useState(''), [pending, setPending] = useState(false), [error, setError] = useState<unknown>(null), [notice, setNotice] = useState('');
  if (!allowed) return <Denied />;
  if (query.isPending) return <Busy />;
  if (query.error) return <QueryError error={query.error} retry={() => void query.refetch()} />;
  const order = query.data!, draft = order.status === 'DRAFT', global = can(user, 'orders.view');
  const saved = async (result: Order) => {
    client.setQueryData(localizedKey([endpoint, id]), result); setEditing(false); setAction(null); setNotice('تم حفظ الطلب');
    await Promise.all([client.invalidateQueries({ queryKey: [endpoint] }), client.invalidateQueries({ queryKey: ['warehouse'] })]);
  };
  return <><Link className="inline-link" to="/orders"><ArrowRight size={17} />{translateUi("طلبات التصنيع")}</Link>
    <div className="page-heading order-heading"><div><div className="eyebrow" dir="ltr">{formatDisplay(order.code)}</div><h1>{formatDisplay(order.title)}</h1></div><div className="bom-actions">
      <IconButton label={translateUi("تحديث الطلب")} disabled={query.isFetching || pending} onClick={() => void query.refetch()}><RefreshCw size={18} /></IconButton>
      {formatDisplay(draft && writePermissions.every(p => can(user, p)) && <button onClick={() => setEditing(true)}><Pencil size={17} />{translateUi("تعديل المسودة")}</button>)}
      {formatDisplay(draft && global && can(user, 'orders.release') && <button className="primary" onClick={() => { setError(null); setAction({ kind: 'release', version: order.version }); }}><Send size={17} />{translateUi("فتح الطلب للعمل")}</button>)}
      {formatDisplay(!['CANCELLED', 'TESTING', 'COMPLETED'].includes(order.status) && global && can(user, 'orders.cancel') && <button onClick={() => { setError(null); setReason(''); setAction({ kind: 'cancel', version: order.version }); }}><X size={17} />{translateUi("إلغاء الطلب")}</button>)}
    </div></div>
    {formatDisplay(notice && <div className="notice" role="status"><Check size={17} />{formatDisplay(notice)}</div>)}
    <dl className="order-meta"><div><dt>{translateUi("الحالة")}</dt><dd><Status value={order.status} /></dd></div><div><dt>{translateUi("الماكينة")}</dt><dd>{formatDisplay(order.machineName)}<span className="record-code" dir="ltr">{formatDisplay(order.machineSerial)}</span></dd></div>
      <div><dt>{translateUi("الموديل")}</dt><dd>{formatDisplay(order.modelName)}</dd></div><div><dt>{translateUi("المسؤول")}</dt><dd>{formatDisplay(order.responsibleName)}</dd></div>
      <div><dt>{translateUi("موعد التسليم · القاهرة")}</dt><dd><time dateTime={order.deliveryAt}>{formatDisplay(dateFormat.format(new Date(order.deliveryAt)))}</time></dd></div><div><dt>{translateUi("إصدار قطع الماكينة")}</dt><dd>{formatDisplay(order.bomRevision)}</dd></div>
      {formatDisplay(order.openedAt && <div><dt>{translateUi("فتح الطلب · القاهرة")}</dt><dd>{formatDisplay(dateFormat.format(new Date(order.openedAt)))}</dd></div>)}
      {formatDisplay(order.cancelledAt && <div><dt>{translateUi("الإلغاء · القاهرة")}</dt><dd>{formatDisplay(dateFormat.format(new Date(order.cancelledAt)))}</dd></div>)}
      {formatDisplay(order.completedAt && <div><dt>{translateUi("نجاح التشغيل واكتمال الطلب")}</dt><dd>{formatDisplay(dateFormat.format(new Date(order.completedAt)))}</dd></div>)}
      {formatDisplay(order.deliveredAt && <div><dt>{translateUi("التسليم الفعلي")}</dt><dd>{formatDisplay(dateFormat.format(new Date(order.deliveredAt)))}</dd></div>)}
    </dl>
    {formatDisplay(order.notes && <p className="order-notes">{formatDisplay(order.notes)}</p>)}{formatDisplay(order.cancellationReason && <p className="error">{translateUi("سبب الإلغاء:")}{' '}{formatDisplay(order.cancellationReason)}</p>)}
    {formatDisplay(order.status !== 'DRAFT' && <section className="assembly-progress"><div><h2>{translateUi("تقدم التجميع")}</h2><span>{formatDisplay(order.installedCount)} / {formatDisplay(order.requiredCount)} {translateUi("قطعة مركبة")}</span></div><progress aria-label={translateUi("تقدم التجميع")} value={order.installedCount} max={order.requiredCount || 1} />
      <span>{translateUi("نسخة تركيب الماكينة:")}{' '}{formatDisplay(order.configurationVersion)}</span>{formatDisplay(order.assemblyCompletedAt && <time dateTime={order.assemblyCompletedAt}>{formatDisplay(dateFormat.format(new Date(order.assemblyCompletedAt)))}</time>)}
      {formatDisplay(global && can(user, 'tasks.view') && <Link className="inline-link" to={`/tasks?order=${translateDisplay(id)}`}><ClipboardList size={17} />{translateUi("مهام التجميع")}</Link>)}
      {formatDisplay(can(user, 'operations.view') && <Link className="inline-link" to={`/operations/${translateDisplay(id)}`}><Check size={17} />{translateUi("تجارب التشغيل والتسليم")}</Link>)}</section>)}
    <div className="bom-summary"><h2>{translateUi("احتياجات الطلب")}</h2><span className="muted">{formatDisplay(totals(order.items))}</span></div><Requirements items={order.items} />
    <OrderWarehouse orderId={id} responsibleName={order.responsibleName} />
    <Files kind="ORDER" id={id} />
    {formatDisplay(editing && <OrderForm original={order} saved={saved} cancel={() => setEditing(false)} />)}
    {formatDisplay(action && <Modal title={formatDisplay(action.kind === 'release' ? 'فتح الطلب للعمل' : 'إلغاء الطلب')} close={() => { if (!pending) setAction(null); }}><form className="form-stack" onSubmit={async e => {
      e.preventDefault(); setPending(true); setError(null);
      try { await saved(await api<Order>(`${translateDisplay(endpoint)}/${translateDisplay(id)}/${translateDisplay(action.kind)}`, 'POST', { version: action.version, reason })); } catch (e) { setError(e); } finally { setPending(false); }
    }}><ErrorBox error={error} />
      {formatDisplay(action.kind === 'release' ? <p>{translateUi("سيتم تثبيت بيانات الطلب واحتياجاته وحجز القطع المتاحة. يتم الصرف لاحقًا من تجهيز المخزن.")}</p>
        : <label>{translateUi("سبب الإلغاء")}<textarea aria-label={translateUi("سبب الإلغاء")} value={reason} onChange={e => setReason(e.target.value)} maxLength={1000} required rows={3} /></label>)}
      <div className="form-actions"><button className="primary" disabled={pending || (action.kind === 'cancel' && !reason.trim())}><Check size={17} />{formatDisplay(action.kind === 'release' ? 'تأكيد فتح الطلب' : 'تأكيد إلغاء الطلب')}</button><button type="button" disabled={pending} onClick={() => setAction(null)}>{translateUi("رجوع")}</button></div>
    </form></Modal>)}
  </>;
}
export function OrderForm({ original, initialMachineId, saved, cancel }: { original?: Order; initialMachineId?: string; saved: (order: Order) => void | Promise<void>; cancel: () => void }) {
  useLanguage();
  const [error, setError] = useState<unknown>(null); const client = useQueryClient();
  const machines = useQuery({ queryKey: ['/machines'], queryFn: () => api<Machine[]>('/machines') });
  const people = useQuery({ queryKey: [endpoint, 'responsible-people'], queryFn: () => api<Person[]>(`${translateDisplay(endpoint)}/responsible-people`) });
  const form = useForm({ defaultValues: { code: original?.code ?? '', title: original?.title ?? '', machineId: original?.machineId ?? initialMachineId ?? '', bomId: original?.bomId ?? '',
    responsibleId: original?.responsibleId ?? '', delivery: original ? localInput(original.deliveryAt) : '', notes: original?.notes ?? '' } });
  const [version] = useState(original?.version ?? 0); const values = form.watch();
  const boms = useQuery({ queryKey: ['machine-boms', values.machineId], queryFn: () => api<Bom[]>(`/machines/${translateDisplay(values.machineId)}/boms`), enabled: !!values.machineId });
  const selected = boms.data?.find(b => b.id === values.bomId && b.status === 'PUBLISHED');
  return <Modal title={formatDisplay(original ? 'تعديل المسودة' : 'طلب جديد')} close={() => { if (!form.formState.isSubmitting) cancel(); }}><form className="form-stack" onSubmit={form.handleSubmit(async v => {
    setError(null);
    try {
      const result = await api<Order>(`${translateDisplay(endpoint)}${translateDisplay(original ? `/${translateDisplay(original.id)}` : '')}`, original ? 'PUT' : 'POST', { ...v, deliveryAt: new Date(v.delivery).toISOString(), version });
      await client.invalidateQueries({ queryKey: [endpoint] }); await saved(result);
    } catch (e) { setError(e); }
  })}><SourceTextPreview values={form.watch()} /><ErrorBox error={error} />
    <div className="form-grid"><label>{translateUi("كود الطلب")}<input {...form.register('code')} pattern={codePattern} maxLength={64} required readOnly={!!original} dir="ltr" /></label>
      <label>{translateUi("اسم الطلب")}<input {...form.register('title')} maxLength={120} required /></label></div>
    {formatDisplay(machines.error && <QueryError error={machines.error} retry={() => void machines.refetch()} />)}
    <label>{translateUi("الماكينة")}<select aria-label={translateUi("الماكينة")} {...form.register('machineId', { onChange: () => form.setValue('bomId', '') })} value={values.machineId} required disabled={machines.isPending || machines.isError}>
      <option value="">{translateUi("اختر الماكينة")}</option>{formatDisplay(machines.data?.filter(m => m.active || m.id === original?.machineId).map(m => <option key={m.id} value={m.id}>{formatDisplay(m.serialNumber)} · {formatDisplay(m.name)}{formatDisplay(!m.active && ' (مؤرشفة)')}</option>))}</select></label>
    {formatDisplay(boms.error && <QueryError error={boms.error} retry={() => void boms.refetch()} />)}
    <label>{translateUi("إصدار قطع الماكينة")}<select aria-label={translateUi("إصدار قطع الماكينة")} {...form.register('bomId')} value={values.bomId} required disabled={!values.machineId || boms.isPending || boms.isError}>
      <option value="">{translateUi("اختر إصدارًا منشورًا")}</option>{formatDisplay(boms.data?.filter(b => b.status === 'PUBLISHED').map(b => <option key={b.id} value={b.id}>{translateUi("الإصدار")}{' '}{formatDisplay(b.revision)} · {formatDisplay(b.items.length)} {translateUi("موضع")}</option>))}</select></label>
    {formatDisplay(values.machineId && boms.isSuccess && !boms.data.some(b => b.status === 'PUBLISHED') && <div className="field-error">{translateUi("لا يوجد إصدار منشور لقطع هذه الماكينة.")}</div>)}
    {formatDisplay(selected && <div className="muted" role="status">{formatDisplay(totals(selected.items))}</div>)}
    {formatDisplay(people.error && <QueryError error={people.error} retry={() => void people.refetch()} />)}
    <label>{translateUi("المسؤول")}<select aria-label={translateUi("المسؤول")} {...form.register('responsibleId')} value={values.responsibleId} required disabled={people.isPending || people.isError}>
      <option value="">{translateUi("اختر المسؤول")}</option>{formatDisplay(original && people.isSuccess && !people.data.some(p => p.id === original.responsibleId) && <option value={original.responsibleId}>{formatDisplay(original.responsibleName)} {translateUi("(غير متاح)")}</option>)}
      {formatDisplay(people.data?.map(p => <option key={p.id} value={p.id}>{formatDisplay(p.name)} · {formatDisplay(p.email)}</option>))}</select></label>
    <label>{translateUi("موعد التسليم ·")}{' '}{formatDisplay(inputZone)}<input aria-label={translateUi("موعد التسليم")} type="datetime-local" {...form.register('delivery')} required min={localInput(new Date().toISOString())} dir="ltr" /></label>
    <label>{translateUi("ملاحظات")}<textarea {...form.register('notes')} maxLength={1000} rows={3} /></label>
    <div className="form-actions"><button className="primary" disabled={form.formState.isSubmitting || !selected || machines.isPending || machines.isError || people.isPending || people.isError}><Check size={17} />{translateUi("حفظ المسودة")}</button><button type="button" disabled={form.formState.isSubmitting} onClick={cancel}>{translateUi("إلغاء")}</button></div>
  </form></Modal>;
}
