import { useState } from 'react';
import { Files } from './Files';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { ArrowRight, Check, ChevronLeft, ChevronRight, ClipboardList, Pencil, Plus, RefreshCw, Search, Send, X } from 'lucide-react';
import { api } from './api';
import { can, useAuth } from './access';
import { Busy, Denied, ErrorBox, IconButton, Modal, QueryError } from './ui';
import { codePattern, dimensions, type Bom, type BomItem, type Machine } from './catalog';
import { OrderWarehouse } from './Warehouse';

type Order = {
  id: string; code: string; title: string; machineId: string; bomId: string; responsibleId: string; responsibleName: string;
  deliveryAt: string; notes: string; status: 'DRAFT' | 'OPEN' | 'IN_PROGRESS' | 'AWAITING_TESTING' | 'TESTING' | 'REWORK' | 'COMPLETED' | 'CANCELLED'; machineSerial: string; machineName: string;
  modelName: string; bomRevision: number; version: number; createdAt: string; openedAt: string | null;
  cancelledAt: string | null; cancellationReason: string | null; items: BomItem[]; requiredCount: number; installedCount: number; assemblyCompletedAt: string | null; configurationVersion: number;
  testedAt: string | null; completedAt: string | null; deliveredAt: string | null; deliveryNotes: string | null;
};
type Person = { id: string; name: string; email: string };
const endpoint = '/manufacturing-orders';
const labels = { DRAFT: 'مسودة', OPEN: 'مفتوح', IN_PROGRESS: 'قيد التجميع', AWAITING_TESTING: 'بانتظار تجربة التشغيل', TESTING: 'قيد تجربة التشغيل', REWORK: 'معالجة بعد التجربة', COMPLETED: 'مكتمل', CANCELLED: 'ملغي' };
const writePermissions = ['orders.view', 'orders.manage', 'catalog.view', 'machines.view', 'boms.view'];
const dateFormat = new Intl.DateTimeFormat('ar-EG', { dateStyle: 'medium', timeStyle: 'short', timeZone: 'Africa/Cairo' });
const inputZone = Intl.DateTimeFormat().resolvedOptions().timeZone;
function localInput(value: string) {
  const date = new Date(value);
  return new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
}
function Status({ value }: { value: Order['status'] }) { return <span className={`badge ${['DRAFT', 'IN_PROGRESS', 'TESTING'].includes(value) ? 'draft' : ['CANCELLED', 'REWORK'].includes(value) ? 'inactive' : 'active'}`}>{labels[value]}</span>; }
function totals(items: BomItem[]) {
  return `${items.length} موضع · ${items.reduce((n, i) => n + i.quantity, 0)} قطعة · ${Math.round(items.reduce((n, i) => n + i.quantity * i.expectedInstallMinutes, 0) * 100) / 100} دقيقة`;
}
function Requirements({ items }: { items: BomItem[] }) {
  return <div className="table-wrap" data-testid="order-requirements"><table className="bom-table"><thead><tr><th>الموضع</th><th>القطعة</th><th>المقاسات</th><th>الكمية</th><th>دقيقة / قطعة</th><th>ملاحظات</th></tr></thead><tbody>
    {items.map(i => <tr key={i.id}><td><strong dir="ltr">{i.positionCode}</strong></td><td><strong>{i.partName}</strong><span className="record-code" dir="ltr">{i.partCode}</span></td>
      <td><span className="dimensions" dir={i.shape === 'STANDARD' ? 'rtl' : 'ltr'}>{dimensions(i)}</span></td><td>{i.quantity}</td><td>{i.expectedInstallMinutes}</td><td><span className="cell-notes">{i.notes || '—'}</span></td></tr>)}
  </tbody></table></div>;
}
export function OrdersPage() {
  const { user } = useAuth(); const navigate = useNavigate();
  const allowed = can(user, 'orders.view') || can(user, 'orders.view-assigned');
  const manage = writePermissions.every(p => can(user, p));
  const query = useQuery({ queryKey: [endpoint], queryFn: () => api<Order[]>(endpoint), enabled: allowed });
  const [search, setSearch] = useState(''), [status, setStatus] = useState('all'), [page, setPage] = useState(0), [creating, setCreating] = useState(false);
  if (!allowed) return <Denied />;
  const rows = (query.data ?? []).filter(o => `${o.code} ${o.title} ${o.machineSerial} ${o.machineName} ${o.responsibleName}`.toLowerCase().includes(search.toLowerCase()) && (status === 'all' || o.status === status));
  const pages = Math.max(1, Math.ceil(rows.length / 10)), current = Math.min(page, pages - 1);
  return <><div className="page-heading"><div><div className="eyebrow">{can(user, 'orders.view') ? 'التصنيع' : 'الطلبات المسندة لي'}</div><h1>طلبات التصنيع <span className="count">{query.data?.length ?? 0}</span></h1></div>
    {manage && <button className="primary" onClick={() => setCreating(true)}><Plus size={18} />طلب جديد</button>}</div>
    <div className="toolbar"><label className="search"><Search size={18} /><input aria-label="بحث طلبات التصنيع" placeholder="بحث بالطلب أو الماكينة أو المسؤول" value={search} onChange={e => { setSearch(e.target.value); setPage(0); }} /></label>
      <select aria-label="حالة الطلب" value={status} onChange={e => { setStatus(e.target.value); setPage(0); }}><option value="all">كل الحالات</option>{Object.entries(labels).map(([v, label]) => <option key={v} value={v}>{label}</option>)}</select>
      <IconButton label="تحديث الطلبات" disabled={query.isFetching} onClick={() => void query.refetch()}><RefreshCw size={18} /></IconButton></div>
    {query.isPending ? <Busy /> : query.error ? <QueryError error={query.error} retry={() => void query.refetch()} /> : <><div className="table-wrap"><table><thead><tr><th>الطلب</th><th>الماكينة</th><th>المسؤول</th><th>التسليم · القاهرة</th><th>الحالة</th></tr></thead><tbody>
      {rows.slice(current * 10, current * 10 + 10).map(o => <tr key={o.id}><td><Link className="inline-link" to={`/orders/${o.id}`}><ClipboardList size={17} /><strong>{o.title}</strong></Link><span className="record-code" dir="ltr">{o.code}</span></td>
        <td><strong>{o.machineName}</strong><span className="record-code" dir="ltr">{o.machineSerial}</span></td><td>{o.responsibleName}</td><td><time dateTime={o.deliveryAt}>{dateFormat.format(new Date(o.deliveryAt))}</time>
          {o.status !== 'CANCELLED' && !o.deliveredAt && new Date(o.deliveryAt).getTime() < Date.now() && <div className="field-error">متأخر</div>}</td><td><Status value={o.status} />{o.status === 'COMPLETED' && <span className="record-code">{o.deliveredAt ? 'تم التسليم' : 'بانتظار التسليم الفعلي'}</span>}</td></tr>)}
    </tbody></table>{!rows.length && <div className="state">لا توجد طلبات</div>}</div>
      <div className="pagination"><span>{rows.length} طلب</span><div><IconButton label="الصفحة السابقة" disabled={!current} onClick={() => setPage(current - 1)}><ChevronRight size={18} /></IconButton><span>{current + 1} / {pages}</span><IconButton label="الصفحة التالية" disabled={current === pages - 1} onClick={() => setPage(current + 1)}><ChevronLeft size={18} /></IconButton></div></div></>}
    {creating && <OrderForm saved={o => navigate(`/orders/${o.id}`)} cancel={() => setCreating(false)} />}
  </>;
}
export function OrderPage() {
  const { id } = useParams();
  return <OrderDetail key={id} id={id!} />;
}
function OrderDetail({ id }: { id: string }) {
  const { user } = useAuth(); const client = useQueryClient();
  const allowed = can(user, 'orders.view') || can(user, 'orders.view-assigned');
  const query = useQuery({ queryKey: [endpoint, id], queryFn: () => api<Order>(`${endpoint}/${id}`), enabled: allowed });
  const [editing, setEditing] = useState(false), [action, setAction] = useState<{ kind: 'release' | 'cancel'; version: number } | null>(null);
  const [reason, setReason] = useState(''), [pending, setPending] = useState(false), [error, setError] = useState<unknown>(null), [notice, setNotice] = useState('');
  if (!allowed) return <Denied />;
  if (query.isPending) return <Busy />;
  if (query.error) return <QueryError error={query.error} retry={() => void query.refetch()} />;
  const order = query.data!, draft = order.status === 'DRAFT', global = can(user, 'orders.view');
  const saved = async (result: Order) => {
    client.setQueryData([endpoint, id], result); setEditing(false); setAction(null); setNotice('تم حفظ الطلب');
    await Promise.all([client.invalidateQueries({ queryKey: [endpoint] }), client.invalidateQueries({ queryKey: ['warehouse'] })]);
  };
  return <><Link className="inline-link" to="/orders"><ArrowRight size={17} />طلبات التصنيع</Link>
    <div className="page-heading order-heading"><div><div className="eyebrow" dir="ltr">{order.code}</div><h1>{order.title}</h1></div><div className="bom-actions">
      <IconButton label="تحديث الطلب" disabled={query.isFetching || pending} onClick={() => void query.refetch()}><RefreshCw size={18} /></IconButton>
      {draft && writePermissions.every(p => can(user, p)) && <button onClick={() => setEditing(true)}><Pencil size={17} />تعديل المسودة</button>}
      {draft && global && can(user, 'orders.release') && <button className="primary" onClick={() => { setError(null); setAction({ kind: 'release', version: order.version }); }}><Send size={17} />فتح الطلب للعمل</button>}
      {!['CANCELLED', 'TESTING', 'COMPLETED'].includes(order.status) && global && can(user, 'orders.cancel') && <button onClick={() => { setError(null); setReason(''); setAction({ kind: 'cancel', version: order.version }); }}><X size={17} />إلغاء الطلب</button>}
    </div></div>
    {notice && <div className="notice" role="status"><Check size={17} />{notice}</div>}
    <dl className="order-meta"><div><dt>الحالة</dt><dd><Status value={order.status} /></dd></div><div><dt>الماكينة</dt><dd>{order.machineName}<span className="record-code" dir="ltr">{order.machineSerial}</span></dd></div>
      <div><dt>الموديل</dt><dd>{order.modelName}</dd></div><div><dt>المسؤول</dt><dd>{order.responsibleName}</dd></div>
      <div><dt>موعد التسليم · القاهرة</dt><dd><time dateTime={order.deliveryAt}>{dateFormat.format(new Date(order.deliveryAt))}</time></dd></div><div><dt>إصدار قطع الماكينة</dt><dd>{order.bomRevision}</dd></div>
      {order.openedAt && <div><dt>فتح الطلب · القاهرة</dt><dd>{dateFormat.format(new Date(order.openedAt))}</dd></div>}
      {order.cancelledAt && <div><dt>الإلغاء · القاهرة</dt><dd>{dateFormat.format(new Date(order.cancelledAt))}</dd></div>}
      {order.completedAt && <div><dt>نجاح التشغيل واكتمال الطلب</dt><dd>{dateFormat.format(new Date(order.completedAt))}</dd></div>}
      {order.deliveredAt && <div><dt>التسليم الفعلي</dt><dd>{dateFormat.format(new Date(order.deliveredAt))}</dd></div>}
    </dl>
    {order.notes && <p className="order-notes">{order.notes}</p>}{order.cancellationReason && <p className="error">سبب الإلغاء: {order.cancellationReason}</p>}
    {order.status !== 'DRAFT' && <section className="assembly-progress"><div><h2>تقدم التجميع</h2><span>{order.installedCount} / {order.requiredCount} قطعة مركبة</span></div><progress aria-label="تقدم التجميع" value={order.installedCount} max={order.requiredCount || 1} />
      <span>نسخة تركيب الماكينة: {order.configurationVersion}</span>{order.assemblyCompletedAt && <time dateTime={order.assemblyCompletedAt}>{dateFormat.format(new Date(order.assemblyCompletedAt))}</time>}
      {global && can(user, 'tasks.view') && <Link className="inline-link" to={`/tasks?order=${id}`}><ClipboardList size={17} />مهام التجميع</Link>}
      {can(user, 'operations.view') && <Link className="inline-link" to={`/operations/${id}`}><Check size={17} />تجارب التشغيل والتسليم</Link>}</section>}
    <div className="bom-summary"><h2>احتياجات الطلب</h2><span className="muted">{totals(order.items)}</span></div><Requirements items={order.items} />
    <OrderWarehouse orderId={id} responsibleName={order.responsibleName} />
    <Files kind="ORDER" id={id} />
    {editing && <OrderForm original={order} saved={saved} cancel={() => setEditing(false)} />}
    {action && <Modal title={action.kind === 'release' ? 'فتح الطلب للعمل' : 'إلغاء الطلب'} close={() => { if (!pending) setAction(null); }}><form className="form-stack" onSubmit={async e => {
      e.preventDefault(); setPending(true); setError(null);
      try { await saved(await api<Order>(`${endpoint}/${id}/${action.kind}`, 'POST', { version: action.version, reason })); } catch (e) { setError(e); } finally { setPending(false); }
    }}><ErrorBox error={error} />
      {action.kind === 'release' ? <p>سيتم تثبيت بيانات الطلب واحتياجاته وحجز القطع المتاحة. يتم الصرف لاحقًا من تجهيز المخزن.</p>
        : <label>سبب الإلغاء<textarea aria-label="سبب الإلغاء" value={reason} onChange={e => setReason(e.target.value)} maxLength={1000} required rows={3} /></label>}
      <div className="form-actions"><button className="primary" disabled={pending || (action.kind === 'cancel' && !reason.trim())}><Check size={17} />{action.kind === 'release' ? 'تأكيد فتح الطلب' : 'تأكيد إلغاء الطلب'}</button><button type="button" disabled={pending} onClick={() => setAction(null)}>رجوع</button></div>
    </form></Modal>}
  </>;
}
function OrderForm({ original, saved, cancel }: { original?: Order; saved: (order: Order) => void | Promise<void>; cancel: () => void }) {
  const [error, setError] = useState<unknown>(null); const client = useQueryClient();
  const machines = useQuery({ queryKey: ['/machines'], queryFn: () => api<Machine[]>('/machines') });
  const people = useQuery({ queryKey: [endpoint, 'responsible-people'], queryFn: () => api<Person[]>(`${endpoint}/responsible-people`) });
  const form = useForm({ defaultValues: { code: original?.code ?? '', title: original?.title ?? '', machineId: original?.machineId ?? '', bomId: original?.bomId ?? '',
    responsibleId: original?.responsibleId ?? '', delivery: original ? localInput(original.deliveryAt) : '', notes: original?.notes ?? '' } });
  const [version] = useState(original?.version ?? 0); const values = form.watch();
  const boms = useQuery({ queryKey: ['machine-boms', values.machineId], queryFn: () => api<Bom[]>(`/machines/${values.machineId}/boms`), enabled: !!values.machineId });
  const selected = boms.data?.find(b => b.id === values.bomId && b.status === 'PUBLISHED');
  return <Modal title={original ? 'تعديل المسودة' : 'طلب جديد'} close={() => { if (!form.formState.isSubmitting) cancel(); }}><form className="form-stack" onSubmit={form.handleSubmit(async v => {
    setError(null);
    try {
      const result = await api<Order>(`${endpoint}${original ? `/${original.id}` : ''}`, original ? 'PUT' : 'POST', { ...v, deliveryAt: new Date(v.delivery).toISOString(), version });
      await client.invalidateQueries({ queryKey: [endpoint] }); await saved(result);
    } catch (e) { setError(e); }
  })}><ErrorBox error={error} />
    <div className="form-grid"><label>كود الطلب<input {...form.register('code')} pattern={codePattern} maxLength={64} required readOnly={!!original} dir="ltr" /></label>
      <label>اسم الطلب<input {...form.register('title')} maxLength={120} required /></label></div>
    {machines.error && <QueryError error={machines.error} retry={() => void machines.refetch()} />}
    <label>الماكينة<select aria-label="الماكينة" {...form.register('machineId')} required disabled={machines.isPending || machines.isError} onChange={e => { form.setValue('machineId', e.target.value); form.setValue('bomId', ''); }}>
      <option value="">اختر الماكينة</option>{machines.data?.filter(m => m.active || m.id === original?.machineId).map(m => <option key={m.id} value={m.id}>{m.serialNumber} · {m.name}{!m.active && ' (مؤرشفة)'}</option>)}</select></label>
    {boms.error && <QueryError error={boms.error} retry={() => void boms.refetch()} />}
    <label>إصدار قطع الماكينة<select aria-label="إصدار قطع الماكينة" {...form.register('bomId')} required disabled={!values.machineId || boms.isPending || boms.isError}>
      <option value="">اختر إصدارًا منشورًا</option>{boms.data?.filter(b => b.status === 'PUBLISHED').map(b => <option key={b.id} value={b.id}>الإصدار {b.revision} · {b.items.length} موضع</option>)}</select></label>
    {values.machineId && boms.isSuccess && !boms.data.some(b => b.status === 'PUBLISHED') && <div className="field-error">لا يوجد إصدار منشور لقطع هذه الماكينة.</div>}
    {selected && <div className="muted" role="status">{totals(selected.items)}</div>}
    {people.error && <QueryError error={people.error} retry={() => void people.refetch()} />}
    <label>المسؤول<select aria-label="المسؤول" {...form.register('responsibleId')} required disabled={people.isPending || people.isError}>
      <option value="">اختر المسؤول</option>{original && people.isSuccess && !people.data.some(p => p.id === original.responsibleId) && <option value={original.responsibleId}>{original.responsibleName} (غير متاح)</option>}
      {people.data?.map(p => <option key={p.id} value={p.id}>{p.name} · {p.email}</option>)}</select></label>
    <label>موعد التسليم · {inputZone}<input aria-label="موعد التسليم" type="datetime-local" {...form.register('delivery')} required min={localInput(new Date().toISOString())} dir="ltr" /></label>
    <label>ملاحظات<textarea {...form.register('notes')} maxLength={1000} rows={3} /></label>
    <div className="form-actions"><button className="primary" disabled={form.formState.isSubmitting || !selected || machines.isPending || machines.isError || people.isPending || people.isError}><Check size={17} />حفظ المسودة</button><button type="button" disabled={form.formState.isSubmitting} onClick={cancel}>إلغاء</button></div>
  </form></Modal>;
}
