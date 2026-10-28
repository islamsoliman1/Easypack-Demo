import { FactoryDashboardStats } from './FactoryDashboard';
import { ResourceVisual } from './CatalogMedia';
import { FileButton } from './Files';
import { MachineChatButton, useChatUnread } from './MachineChat';
import { formatDisplay } from './i18n';
import { searchable } from './i18n';
import { translateUi, translateDisplay, useLanguage, dateFormatter, getLocale, getDirection } from './i18n';
import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { useQuery, localizedKey } from './localizedQuery';
import { ArrowLeft, ArrowRight, Check, ClipboardList, Factory, ListTree, MapPin, Plus, RefreshCw, Search, Wrench } from 'lucide-react';
import { can, useAuth } from './access';
import { api } from './api';
import type { Machine } from './catalog';
import { MachineForm } from './CatalogPages';
import { OrderForm, orderStatusLabels, orderWritePermissions, type Order } from './OrdersPage';
import { assemblyPercent, isOverdue, isWorking, machineOverviews, type MachineOverview } from './machineDashboard';
import { Busy, Denied, IconButton, Modal, QueryError } from './ui';

const dateFormat = dateFormatter({ dateStyle: 'medium', timeStyle: 'short', timeZone: 'Africa/Cairo' });
const clockFormat = dateFormatter({ timeStyle: 'short', timeZone: 'Africa/Cairo' });
const refreshInterval = 15000;

function useMachineOverview() {
  const { user } = useAuth();
  const client = useQueryClient();
  const canReadMachines = can(user, 'machines.view');
  const canReadOrders = can(user, 'orders.view') || can(user, 'orders.view-assigned');
  const machines = useQuery({
    queryKey: ['/machines'], queryFn: () => api<Machine[]>('/machines'), enabled: canReadMachines,
    refetchInterval: refreshInterval, staleTime: 0, refetchOnMount: 'always', refetchOnWindowFocus: 'always',
  });
  const orders = useQuery({
    queryKey: ['/manufacturing-orders'], queryFn: () => api<Order[]>('/manufacturing-orders'), enabled: canReadMachines && canReadOrders,
    refetchInterval: refreshInterval, staleTime: 0, refetchOnMount: 'always', refetchOnWindowFocus: 'always',
  });
  const unread = useChatUnread(canReadMachines);
  const refresh = () => Promise.all([client.invalidateQueries({ queryKey: ['factory-dashboard'] }), unread.refetch(), machines.refetch(), ...(canReadOrders ? [orders.refetch()] : [])]);
  const updatedAt = canReadOrders ? Math.min(machines.dataUpdatedAt, orders.dataUpdatedAt) : machines.dataUpdatedAt;
  return {
    user, unread, canReadMachines, canReadOrders, refresh, updatedAt,
    fetching: machines.isFetching || (canReadOrders && orders.isFetching),
    pending: machines.isPending || (canReadOrders && orders.isPending),
    error: machines.error ?? (canReadOrders ? orders.error : null),
    overviews: machineOverviews(machines.data ?? [], canReadOrders ? orders.data ?? [] : []),
  };
}

function OrderStatus({ order }: { order: Order | undefined }) {
  useLanguage();
  if (!order) return <span className="badge dashboard-neutral">{translateUi("بدون طلب")}</span>;
  return <span className={`badge ${translateDisplay(order.status === 'COMPLETED' ? 'active' : ['REWORK', 'CANCELLED'].includes(order.status) ? 'inactive' : 'draft')}`}>
    {formatDisplay(order.deliveredAt ? 'تم التسليم' : orderStatusLabels[order.status])}
  </span>;
}

function AssemblyProgress({ order, serial, visible }: { order: Order | undefined; serial: string; visible: boolean }) {
  useLanguage();
  if (!visible) return <p className="muted">{translateUi("عرض تقدم التجميع يحتاج صلاحية عرض الطلبات.")}</p>;
  const percentage = assemblyPercent(order);
  return <div className="dashboard-progress">
    <div><span>{translateUi("اكتمال التجميع")}</span><strong dir="ltr">{formatDisplay(percentage)}%</strong></div>
    <progress aria-label={formatDisplay(translateUi("اكتمال تجميع {{v0}}", {v0: serial}))} value={percentage} max={100} />
    <small>{formatDisplay(order && !['DRAFT', 'CANCELLED'].includes(order.status)
      ? translateUi("{{v0}} من {{v1}} قطعة مركبة", {v0: order.installedCount, v1: order.requiredCount})
      : order?.status === 'DRAFT' ? translateUi("{{v0}} قطعة مطلوبة · لم يُفتح الطلب للعمل", {v0: order.requiredCount})
      : order?.status === 'CANCELLED' ? 'الطلب ملغي' : 'بانتظار طلب تصنيع')}</small>
  </div>;
}

function MachineCard({ overview, canReadOrders, now, unread }: { overview: MachineOverview; canReadOrders: boolean; now: number; unread?: number }) {
  useLanguage();
  const { machine, currentOrder } = overview;
  const overdue = isOverdue(currentOrder, now);
  return <article className={`machine-card${translateDisplay(!machine.active ? ' machine-card-archived' : '')}`}>
    <div className="machine-card-top"><span className="machine-card-icon"><Factory size={23} /></span><div className="machine-card-badges">
      {formatDisplay(!machine.active && <span className="badge inactive">{translateUi("مؤرشفة")}</span>)}
      {formatDisplay(canReadOrders && <OrderStatus order={currentOrder} />)}
    </div></div>
    <ResourceVisual kind="MACHINE" id={machine.id} name={machine.name} />
    <div className="machine-card-title"><span className="record-code" dir="ltr">{formatDisplay(machine.serialNumber)}</span><h3>{formatDisplay(machine.name)}</h3><p>{formatDisplay(machine.modelName)}</p></div>
    <div className="machine-card-location"><MapPin size={15} /><span>{formatDisplay(machine.location || 'لم يُحدد الموقع')}</span></div>
    <AssemblyProgress order={currentOrder} serial={machine.serialNumber} visible={canReadOrders} />
    <div className="machine-card-order">{formatDisplay(currentOrder ? <><div><ClipboardList size={15} /><strong>{formatDisplay(currentOrder.title)}</strong></div><span className="record-code" dir="ltr">{formatDisplay(currentOrder.code)}</span>
      <span className="muted">{translateUi("المسؤول:")}{' '}{formatDisplay(currentOrder.responsibleName)}</span><time dateTime={currentOrder.deliveryAt}>{translateUi("التسليم:")}{' '}{formatDisplay(dateFormat.format(new Date(currentOrder.deliveryAt)))}</time>
      {formatDisplay(overdue && <span className="field-error">{translateUi("متأخر عن موعد التسليم")}</span>)}</>
      : <p className="muted">{formatDisplay(canReadOrders ? 'لا توجد طلبات ضمن نطاق العرض' : 'يمكنك عرض بيانات الماكينة')}</p>)}</div>
    <div className="factory-machine-actions"><MachineChatButton id={machine.id} name={machine.name} unread={unread} /><FileButton kind="MACHINE" id={machine.id} name={machine.name} /></div>
    <Link to={`/machines/${machine.id}`} className="machine-card-footer"><span>{translateUi("عرض الماكينة والطلبات")}</span><ArrowLeft size={17} /></Link>
  </article>;
}

export function DashboardPage() {
  useLanguage();
  const data = useMachineOverview();
  const navigate = useNavigate(); const client = useQueryClient();
  const [search, setSearch] = useState(''), [filter, setFilter] = useState('all');
  const [creating, setCreating] = useState<'machine' | 'order' | null>(null);
  if (!data.canReadMachines) return <Denied />;
  const { user, overviews, canReadOrders } = data;
  const now = Date.now();
  const canCreateMachine = ['machines.view', 'machines.manage', 'catalog.view'].every(permission => can(user, permission));
  const canCreateOrder = orderWritePermissions.every(permission => can(user, permission));
  const visible = overviews.filter(({ machine, currentOrder, orders }) => {
    const text = [machine.name, machine.serialNumber, machine.modelName, machine.modelCode, machine.location, ...orders.flatMap(order => [order.code, order.title, order.responsibleName])].join(' ').toLocaleLowerCase();
    return searchable(text).toLocaleLowerCase().includes(search.trim().toLocaleLowerCase()) && (filter === 'all'
      || (filter === 'working' && isWorking(currentOrder))
      || (filter === 'waiting' && (!currentOrder || ['DRAFT', 'CANCELLED'].includes(currentOrder.status)))
      || (filter === 'completed' && currentOrder?.status === 'COMPLETED')
      || (filter === 'rework' && currentOrder?.status === 'REWORK')
      || (filter === 'late' && isOverdue(currentOrder, now))
      || (filter === 'archived' && !machine.active));
  });
  return <div className="machine-dashboard">
    <div className="page-heading"><div><div className="eyebrow">{translateUi("نظرة على المصنع")}</div><h1>{translateUi("لوحة متابعة الماكينات")}</h1><p className="muted dashboard-intro">{translateUi("كل الماكينات وطلبات التصنيع ونسب التجميع في مكان واحد.")}</p></div>
      <div className="bom-actions">{formatDisplay(canCreateMachine && <button onClick={() => setCreating('machine')}><Plus size={17} />{translateUi("ماكينة جديدة")}</button>)}
        {formatDisplay(canCreateOrder && <button className="primary" onClick={() => setCreating('order')}><Plus size={17} />{translateUi("طلب جديد")}</button>)}</div></div>
    {formatDisplay(!can(user, 'orders.view') && <p className="notice" role="note">{formatDisplay(canReadOrders ? 'تظهر الماكينات كلها، والطلبات المسندة لك فقط. نسب التجميع حسب الطلبات المتاحة لك.' : 'تظهر بيانات الماكينات فقط حسب صلاحياتك.')}</p>)}
    {formatDisplay(data.pending ? <Busy /> : data.error ? <QueryError error={data.error} retry={() => void data.refresh()} /> : <>
      <FactoryDashboardStats />
      {data.unread.error && <QueryError error={data.unread.error} retry={() => void data.unread.refetch()} />}
      <div className="dashboard-section-heading"><div><h2>{translateUi("الماكينات")}<span className="count">{formatDisplay(visible.length)}</span></h2><p className="muted">{translateUi("اضغط على الماكينة لعرض تفاصيلها. النسبة تخص تجميع أحدث طلب غير ملغي.")}</p></div>
        <div className="dashboard-updated"><span className="dashboard-live-dot" aria-hidden="true" /><span>{translateUi("تحديث تلقائي كل 15 ثانية")}{' '}{formatDisplay(data.updatedAt > 0 && <> {translateUi("· آخر تحديث")}<time dateTime={new Date(data.updatedAt).toISOString()}>{formatDisplay(clockFormat.format(new Date(data.updatedAt)))}</time></>)}</span></div></div>
      <div className="toolbar"><label className="search"><Search size={18} /><input aria-label={translateUi("بحث الماكينات والطلبات")} placeholder={translateUi("بحث بالماكينة أو الموديل أو الطلب")} value={search} onChange={event => setSearch(event.target.value)} /></label>
        <select aria-label={translateUi("تصفية الماكينات")} value={filter} onChange={event => setFilter(event.target.value)}><option value="all">{translateUi("كل الماكينات")}</option>
          {formatDisplay(canReadOrders && <><option value="working">{translateUi("قيد التنفيذ")}</option><option value="waiting">{translateUi("بانتظار العمل")}</option><option value="completed">{translateUi("مكتملة")}</option><option value="rework">{translateUi("تحتاج معالجة")}</option><option value="late">{translateUi("تسليم متأخر")}</option></>)}
          <option value="archived">{translateUi("مؤرشفة")}</option></select>
        <IconButton label={translateUi("تحديث لوحة الماكينات")} disabled={data.fetching} onClick={() => void data.refresh()}><RefreshCw size={18} className={data.fetching ? 'spin' : ''} /></IconButton></div>
      {formatDisplay(visible.length > 0 ? <div className="machine-grid">{formatDisplay(visible.map(overview => <MachineCard key={overview.machine.id} overview={overview} canReadOrders={canReadOrders} now={now} unread={data.unread.data?.find(item => item.machineId === overview.machine.id)?.count} />))}</div>
        : <div className="dashboard-empty"><Factory size={36} /><h2>{formatDisplay(overviews.length ? 'لا توجد ماكينات تطابق البحث' : 'أضف أول ماكينة للمصنع')}</h2><p className="muted">{formatDisplay(overviews.length ? 'جرّب تغيير البحث أو التصفية.' : 'تظهر الماكينات هنا فور إضافتها، وتُحدّث نسبة التجميع مع تقدم العمل.')}</p>
          {formatDisplay(overviews.length > 0 ? <button onClick={() => { setSearch(''); setFilter('all'); }}>{translateUi("مسح البحث والتصفية")}</button> : canCreateMachine && <button className="primary" onClick={() => setCreating('machine')}><Plus size={17} />{translateUi("ماكينة جديدة")}</button>)}</div>)}
    </>)}
    {formatDisplay(creating === 'machine' && <Modal title={translateUi("ماكينة جديدة")} close={() => setCreating(null)}><MachineForm cancel={() => setCreating(null)} saved={async () => {
      setCreating(null); await client.invalidateQueries({ queryKey: ['/machines'] });
    }} /></Modal>)}
    {formatDisplay(creating === 'order' && <OrderForm cancel={() => setCreating(null)} saved={order => { setCreating(null); navigate(`/orders/${translateDisplay(order.id)}`); }} />)}
  </div>;
}

export function MachinePage() {
  useLanguage();
  const { id } = useParams(); const data = useMachineOverview();
  const navigate = useNavigate();
  const [creatingOrder, setCreatingOrder] = useState(false);
  if (!data.canReadMachines) return <Denied />;
  if (data.pending) return <Busy />;
  if (data.error) return <QueryError error={data.error} retry={() => void data.refresh()} />;
  const overview = data.overviews.find(item => item.machine.id === id);
  if (!overview) return <div className="dashboard-empty"><h1>{translateUi("الماكينة غير موجودة")}</h1><Link className="inline-link" to="/">{translateUi("العودة للوحة الماكينات")}</Link></div>;
  const { machine, currentOrder, orders } = overview;
  const canCreateOrder = machine.active && orderWritePermissions.every(permission => can(data.user, permission));
  return <div className="machine-detail">
    <Link className="inline-link" to="/"><ArrowRight size={17} />{translateUi("لوحة متابعة الماكينات")}</Link>
    <div className="page-heading order-heading"><div><div className="eyebrow" dir="ltr">{formatDisplay(machine.serialNumber)}</div><h1>{formatDisplay(machine.name)}</h1></div><div className="bom-actions">
      <IconButton label={translateUi("تحديث الماكينة")} disabled={data.fetching} onClick={() => void data.refresh()}><RefreshCw size={18} /></IconButton>
      {formatDisplay(canCreateOrder && <button className="primary" onClick={() => setCreatingOrder(true)}><Plus size={17} />{translateUi("طلب جديد لهذه الماكينة")}</button>)}</div></div>
    <ResourceVisual kind="MACHINE" id={machine.id} name={machine.name} />
    <div className="factory-machine-actions"><MachineChatButton id={machine.id} name={machine.name} unread={data.unread.data?.find(item => item.machineId === machine.id)?.count} /><FileButton kind="MACHINE" id={machine.id} name={machine.name} /></div>
    <dl className="order-meta"><div><dt>{translateUi("الموديل")}</dt><dd>{formatDisplay(machine.modelName)}<span className="record-code" dir="ltr">{formatDisplay(machine.modelCode)}</span></dd></div><div><dt>{translateUi("الموقع")}</dt><dd>{formatDisplay(machine.location || 'لم يُحدد')}</dd></div>
      <div><dt>{translateUi("حالة الماكينة")}</dt><dd><span className={`badge ${translateDisplay(machine.active ? 'active' : 'inactive')}`}>{formatDisplay(machine.active ? 'نشطة' : 'مؤرشفة')}</span></dd></div>
      {formatDisplay(data.canReadOrders && <div><dt>{translateUi("أحدث طلب غير ملغي")}</dt><dd><OrderStatus order={currentOrder} />{formatDisplay(currentOrder && <span className="record-code" dir="ltr">{formatDisplay(currentOrder.code)}</span>)}</dd></div>)}</dl>
    {formatDisplay(machine.notes && <p className="order-notes">{formatDisplay(machine.notes)}</p>)}
    <section className="machine-detail-progress"><AssemblyProgress order={currentOrder} serial={machine.serialNumber} visible={data.canReadOrders} />
      <p className="muted">{translateUi("النسبة محسوبة من القطع المركبة في أحدث طلب غير ملغي. اكتمال التجميع يتبعه فحص التشغيل والتسليم.")}</p>
      <div className="bom-actions">{formatDisplay(currentOrder && <Link className="inline-link" to={`/orders/${translateDisplay(currentOrder.id)}`}><ClipboardList size={17} />{translateUi("فتح طلب التصنيع")}</Link>)}
        {formatDisplay(can(data.user, 'catalog.view') && can(data.user, 'boms.view') && <Link className="inline-link" to={`/boms?machine=${translateDisplay(machine.id)}`}><ListTree size={17} />{translateUi("قطع الماكينة")}</Link>)}
        {formatDisplay(can(data.user, 'tasks.view') && can(data.user, 'orders.view') && <Link className="inline-link" to={`/machines/${translateDisplay(machine.id)}/assembly`}><Wrench size={17} />{translateUi("التركيب وسجل الفك")}</Link>)}</div></section>
    <div className="dashboard-section-heading"><h2>{translateUi("طلبات الماكينة")}<span className="count">{formatDisplay(orders.length)}</span></h2></div>
    {formatDisplay(!can(data.user, 'orders.view') && <p className="muted">{formatDisplay(data.canReadOrders ? 'تظهر الطلبات المسندة لك فقط.' : 'عرض الطلبات يحتاج صلاحية عرض الطلبات.')}</p>)}
    {formatDisplay(data.canReadOrders && (orders.length ? <div className="machine-order-list">{formatDisplay(orders.map(order => <Link key={order.id} className="machine-order-row" to={`/orders/${translateDisplay(order.id)}`}>
      <div><strong>{formatDisplay(order.title)}</strong><span className="record-code" dir="ltr">{formatDisplay(order.code)}</span><span className="muted">{formatDisplay(order.responsibleName)}</span></div>
      <div><OrderStatus order={order} /><span className="record-code">{translateUi("اكتمال التجميع:")}<bdi>{formatDisplay(assemblyPercent(order))}%</bdi></span></div>
      <div className="muted">{translateUi("التسليم · القاهرة")}<time dateTime={order.deliveryAt}>{formatDisplay(dateFormat.format(new Date(order.deliveryAt)))}</time>{formatDisplay(isOverdue(order, Date.now()) && <span className="field-error">{translateUi("متأخر عن موعد التسليم")}</span>)}</div><ArrowLeft size={18} />
    </Link>))}</div> : <div className="dashboard-empty"><ClipboardList size={30} /><p>{translateUi("لا توجد طلبات تصنيع ضمن نطاق العرض لهذه الماكينة.")}</p></div>))}
    {formatDisplay(creatingOrder && <OrderForm key={machine.id} initialMachineId={machine.id} cancel={() => setCreatingOrder(false)} saved={order => { setCreatingOrder(false); navigate(`/orders/${translateDisplay(order.id)}`); }} />)}
  </div>;
}
