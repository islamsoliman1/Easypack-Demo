import { useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowRight, Check, Eye, Play, Pause, Square, RefreshCw, UserRoundPlus, Wrench, Unplug, CircleAlert } from 'lucide-react';
import { api } from './api';
import { can, useAuth } from './access';
import { Busy, Denied, ErrorBox, IconButton, Modal, QueryError } from './ui';
import { StockTable, invalidateStock } from './Warehouse';
import { FileButton } from './Files';
import { minutes, type WorkTime } from './Shifts';

type Task = { id: string; requirementId: string; orderId: string; orderCode: string; orderTitle: string; orderStatus: string; machineId: string; machineSerial: string; machineName: string; deliveryAt: string; positionCode: string; occurrence: number; partCode: string; partName: string; expectedMinutes: number; requirementNotes: string; workerId: string | null; workerName: string | null; status: string; version: number; startedAt: string | null; pieceId: string | null; serialNumber: string | null; pieceStatus: string | null; installationId: string | null; installedAt: string | null };
type Person = { id: string; name: string; email: string };
type Event = { id: string; action: string; workerName: string | null; actorName: string; serialNumber: string | null; notes: string; createdAt: string };
type Detail = { task: Task & { partId: string }; events: Event[]; workTime: WorkTime };
type ActionKind = 'assign' | 'start' | 'install' | 'uninstall' | 'reject' | 'pause' | 'resume' | 'stop-time';
type Installation = { id: string; taskId: string; orderId: string; orderCode: string; positionCode: string; occurrence: number; serialNumber: string; partCode: string; partName: string; workerName: string; startedAt: string; installedAt: string; installNotes: string; removedBy: string | null; removedAt: string | null; removalReason: string | null };
type MachineAssembly = { machineId: string; serialNumber: string; name: string; configurationVersion: number; installations: Installation[] };
const root = '/assembly/tasks';
const date = new Intl.DateTimeFormat('ar-EG', { dateStyle: 'short', timeStyle: 'short', timeZone: 'Africa/Cairo' });
const states: Record<string, string> = { PENDING: 'لم يبدأ', IN_PROGRESS: 'جارٍ التركيب', INSTALLED: 'مركب' };
const orderStates: Record<string, string> = { OPEN: 'مفتوح', IN_PROGRESS: 'قيد التجميع', AWAITING_TESTING: 'بانتظار تجربة التشغيل', TESTING: 'قيد تجربة التشغيل', REWORK: 'معالجة بعد التجربة', COMPLETED: 'مكتمل', CANCELLED: 'ملغي' };
const events: Record<string, string> = { ASSIGNED: 'تكليف', UNASSIGNED: 'إلغاء التكليف', STARTED: 'بدء التركيب', PIECE_ISSUED: 'صرف القطعة', PIECE_RETURNED: 'إرجاع القطعة', INSTALLED: 'تركيب', UNINSTALLED: 'فك', REJECTED: 'رفض قطعة معيبة' };
const slot = (t: { positionCode: string; occurrence: number }) => `${t.positionCode} / ${t.occurrence}`;
const running = (t: Task) => (t as Task & { workRunning: boolean }).workRunning;
function State({ value }: { value: string }) { return <span className={`badge ${value === 'INSTALLED' ? 'active' : 'draft'}`}>{states[value]}</span>; }

export function TasksPage() {
  const { user } = useAuth(); const client = useQueryClient(); const [params, setParams] = useSearchParams();
  const all = can(user, 'tasks.view') && can(user, 'orders.view'), allowed = all || can(user, 'tasks.view-assigned');
  const order = params.get('order'), assign = all && can(user, 'tasks.assign');
  const query = useQuery({ queryKey: ['assembly', 'tasks', order], queryFn: () => api<Task[]>(`${root}${order ? `?orderId=${encodeURIComponent(order)}` : ''}`), enabled: allowed });
  const workers = useQuery({ queryKey: ['assembly', 'workers'], queryFn: () => api<Person[]>('/assembly/workers'), enabled: assign });
  const [status, setStatus] = useState('all'), [workerFilter, setWorkerFilter] = useState('all');
  const detail = params.get('task');
  const setDetail = (id: string | null) => { const next = new URLSearchParams(params); if (id) next.set('task', id); else next.delete('task'); setParams(next); };
  const [action, setAction] = useState<{ kind: ActionKind; task: Task } | null>(null), [notice, setNotice] = useState('');
  if (!allowed) return <Denied />;
  return <div className="assembly-tasks">{order && all && <Link className="inline-link" to={`/orders/${order}`}><ArrowRight size={17} />طلب التصنيع</Link>}
    <div className="page-heading"><div><div className="eyebrow">التجميع</div><h1>{all ? 'مهام التجميع' : 'مهامي'}</h1></div><IconButton label="تحديث المهام" disabled={query.isFetching} onClick={() => { void query.refetch(); if (assign) void workers.refetch(); }}><RefreshCw size={18} /></IconButton></div>
    {notice && <div className="notice" role="status"><Check size={17} />{notice}</div>}
    {query.isPending ? <Busy /> : query.error ? <QueryError error={query.error} retry={() => void query.refetch()} /> : <StockTable rows={(query.data ?? []).filter(t => (status === 'all' || t.status === status) && (workerFilter === 'all' || (workerFilter === 'mine' ? t.workerId === user.id : t.workerId === null)))} searchText={t => `${t.orderCode} ${t.orderTitle} ${t.machineSerial} ${t.partCode} ${t.partName} ${slot(t)} ${t.workerName ?? ''} ${t.serialNumber ?? ''}`} filters={<>
      <select aria-label="حالة المهمة" value={status} onChange={e => setStatus(e.target.value)}><option value="all">كل الحالات</option>{Object.entries(states).map(([v, name]) => <option key={v} value={v}>{name}</option>)}</select>
      {all && <select aria-label="تكليف المهام" value={workerFilter} onChange={e => setWorkerFilter(e.target.value)}><option value="all">كل العمال</option><option value="unassigned">بدون تكليف</option><option value="mine">المسند لي</option></select>}</>} columns={[
        { label: 'الطلب / الماكينة', cell: t => <>{all ? <Link className="inline-link" to={`/orders/${t.orderId}`}><strong>{t.orderTitle}</strong></Link> : <strong>{t.orderTitle}</strong>}<span className="record-code" dir="ltr">{t.orderCode} · {t.machineSerial}</span><span className="badge draft">{orderStates[t.orderStatus]}</span></> },
        { label: 'الموضع / النسخة', cell: t => <strong dir="ltr">{slot(t)}</strong> }, { label: 'القطعة', cell: t => <><strong>{t.partName}</strong><span className="record-code" dir="ltr">{t.partCode}</span></> },
        { label: 'العامل', cell: t => t.workerName || 'بدون تكليف' }, { label: 'Serial Number', cell: t => <>{t.serialNumber ? <strong dir="ltr">{t.serialNumber}</strong> : 'لم تُصرف قطعة'}{t.pieceStatus === 'ISSUED' && <span className="badge draft">مصروف للعامل</span>}{t.pieceStatus === 'REJECTED' && <span className="badge inactive">مرفوض في عهدة العامل</span>}</> },
        { label: 'التركيب / التسليم', cell: t => <>{t.expectedMinutes} دقيقة<span className="record-code">{date.format(new Date(t.deliveryAt))}</span></> }, { label: 'الحالة', cell: t => <State value={t.status} /> },
        { label: 'الإجراءات', cell: t => <div className="row-actions"><IconButton label={`تفاصيل ${slot(t)}`} onClick={() => setDetail(t.id)}><Eye size={17} /></IconButton>
          {['OPEN', 'IN_PROGRESS', 'AWAITING_TESTING', 'TESTING', 'REWORK'].includes(t.orderStatus) && <>{assign && t.status !== 'INSTALLED' && !t.pieceId && <IconButton label={`تكليف ${slot(t)}`} onClick={() => setAction({ kind: 'assign', task: t })}><UserRoundPlus size={17} /></IconButton>}
            {t.workerId === user.id && can(user, 'tasks.work') && t.pieceStatus === 'ISSUED' && t.status === 'PENDING' && <IconButton label={`بدء ${slot(t)}`} onClick={() => setAction({ kind: 'start', task: t })}><Play size={17} /></IconButton>}
            {t.workerId === user.id && can(user, 'tasks.work') && t.pieceStatus === 'ISSUED' && t.status === 'IN_PROGRESS' && <IconButton label={`تركيب ${slot(t)}`} onClick={() => setAction({ kind: 'install', task: t })}><Wrench size={17} /></IconButton>}
            {t.workerId === user.id && can(user, 'tasks.work') && t.pieceStatus === 'ISSUED' && t.status === 'IN_PROGRESS' && <IconButton label={`${running(t) ? 'إيقاف الوقت مؤقتًا' : 'استئناف الوقت'} ${slot(t)}`} onClick={() => setAction({ kind: running(t) ? 'pause' : 'resume', task: t })}>{running(t) ? <Pause size={17} /> : <Play size={17} />}</IconButton>}
            {all && can(user, 'time.manage') && t.status === 'IN_PROGRESS' && running(t) && <IconButton label={`إيقاف وقت العامل ${slot(t)}`} onClick={() => setAction({ kind: 'stop-time', task: t })}><Square size={17} /></IconButton>}
            {t.workerId === user.id && can(user, 'tasks.reject') && t.pieceStatus === 'ISSUED' && <IconButton label={`رفض ${slot(t)}`} onClick={() => setAction({ kind: 'reject', task: t })}><CircleAlert size={17} /></IconButton>}
            {can(user, 'tasks.uninstall') && t.status === 'INSTALLED' && (all || t.workerId === user.id) && <IconButton label={`فك ${slot(t)}`} onClick={() => setAction({ kind: 'uninstall', task: t })}><Unplug size={17} /></IconButton>}</>}
        </div> },
      ]} />}
    {detail && <TaskDetail id={detail} close={() => setDetail(null)} />}
    {action && <TaskAction key={`${action.kind}-${action.task.id}`} action={action} workers={workers.data} workersError={workers.error} retry={() => void workers.refetch()} close={() => setAction(null)} saved={async () => { setAction(null); setNotice('تم حفظ إجراء التجميع'); await invalidateStock(client); }} />}
  </div>;
}
function TaskAction({ action, workers, workersError, retry, close, saved }: { action: { kind: ActionKind; task: Task }; workers?: Person[]; workersError: unknown; retry: () => void; close: () => void; saved: () => Promise<void> }) {
  const { kind, task } = action; const [worker, setWorker] = useState(task.workerId ?? ''), [notes, setNotes] = useState(''), [pending, setPending] = useState(false), [error, setError] = useState<unknown>(null);
  const names = { assign: 'تكليف عامل', start: 'بدء التركيب', install: 'تأكيد التركيب', uninstall: 'فك القطعة', reject: 'رفض القطعة', pause: 'إيقاف الوقت مؤقتًا', resume: 'استئناف الوقت', 'stop-time': 'إيقاف وقت العامل' };
  const noteLabel = kind === 'reject' ? 'سبب الرفض' : kind === 'uninstall' ? 'سبب الفك' : 'ملاحظات التجميع';
  return <Modal title={`${names[kind]} · ${slot(task)}`} close={() => { if (!pending) close(); }}><form className="form-stack" onSubmit={async e => {
    e.preventDefault(); setPending(true); setError(null);
    try { await api(`${root}/${task.id}/${kind}`, 'POST', { version: task.version, workerId: worker || null, notes, reason: notes }); await saved(); } catch (e) { setError(e); } finally { setPending(false); }
  }}><ErrorBox error={error} /><div className="raw-source"><strong>{task.partName}</strong><span dir="ltr">{task.serialNumber ?? task.partCode}</span><span>{task.machineName}</span></div>
    {kind === 'assign' && <>{workersError && <QueryError error={workersError} retry={retry} />}<label>العامل المكلف<select aria-label="العامل المكلف" value={worker} onChange={e => setWorker(e.target.value)} disabled={pending || !workers}><option value="">بدون تكليف</option>{workers?.map(w => <option key={w.id} value={w.id}>{w.name} · {w.email}</option>)}</select></label></>}
    <label>{noteLabel}<textarea aria-label={noteLabel} value={notes} onChange={e => setNotes(e.target.value)} maxLength={1000} rows={3} required={['reject', 'uninstall', 'pause', 'resume', 'stop-time'].includes(kind) || (kind === 'assign' && !!task.workerId && worker !== task.workerId)} disabled={pending} /></label>
    <div className="form-actions"><button className="primary" disabled={pending || (kind === 'assign' && (!workers || !!workersError))}><Check size={17} />{names[kind]}</button><button type="button" onClick={close} disabled={pending}>رجوع</button></div>
  </form></Modal>;
}
function TaskDetail({ id, close }: { id: string; close: () => void }) {
  const query = useQuery({ queryKey: ['assembly', 'task', id], queryFn: () => api<Detail>(`${root}/${id}`), refetchInterval: 15000 }); const t = query.data?.task;
  return <Modal title={t ? `تفاصيل ${slot(t)}` : 'تفاصيل المهمة'} close={close}><div className="modal-body">{query.isPending ? <Busy /> : query.error ? <QueryError error={query.error} retry={() => void query.refetch()} /> : t && <>
    <dl className="order-meta"><div><dt>الطلب</dt><dd>{t.orderTitle}</dd></div><div><dt>الماكينة</dt><dd>{t.machineName}<span className="record-code" dir="ltr">{t.machineSerial}</span></dd></div><div><dt>القطعة</dt><dd>{t.partName}</dd></div><div><dt>العامل</dt><dd>{t.workerName || 'بدون تكليف'}</dd></div><div><dt>الحالة</dt><dd><State value={t.status} /></dd></div><div><dt>Serial Number</dt><dd dir="ltr">{t.serialNumber || '—'}</dd></div><div><dt>الوقت المتوقع</dt><dd>{t.expectedMinutes} دقيقة</dd></div>
      {t.startedAt && <div><dt>بداية الدورة الحالية</dt><dd>{date.format(new Date(t.startedAt))}</dd></div>}{t.installedAt && <div><dt>وقت التركيب</dt><dd>{date.format(new Date(t.installedAt))}</dd></div>}</dl>
    {t.requirementNotes && <p className="order-notes">{t.requirementNotes}</p>}<div className="bom-actions"><FileButton kind="ORDER" id={t.orderId} name="الطلب" /><FileButton kind="MACHINE" id={t.machineId} name="الماكينة" /><FileButton kind="PART" id={t.partId} name="القطعة" /></div>
    <h3 className="stock-subheading">وقت العمل المسجل</h3><dl className="order-meta"><div><dt>داخل الوردية</dt><dd>{minutes(query.data!.workTime.total.grossSeconds)}</dd></div><div><dt>الصافي</dt><dd>{minutes(query.data!.workTime.total.netSeconds)}</dd></div></dl><ol className="task-history">{query.data!.workTime.sessions.map(s => <li key={s.id}><div><strong>{s.workerName} · {s.running ? 'وقت جارٍ' : 'متوقف'}</strong><span>{minutes(s.grossSeconds)}</span></div><time>{date.format(new Date(s.startedAt))} · {s.endedAt ? date.format(new Date(s.endedAt)) : s.running ? 'جارٍ' : 'انتهت الوردية'}</time><p>{({ SHIFT_ENDED: 'انتهاء الوردية', OUTSIDE_SHIFT: 'خارج الوردية دون احتساب وقت', INSTALLED: 'تم التركيب', PIECE_REJECTED: 'رفض القطعة', PIECE_RETURNED: 'إرجاع القطعة', ASSIGNMENT_CHANGED: 'تغيير التكليف' } as Record<string, string>)[s.reason ?? ''] ?? s.reason}</p></li>)}</ol>
    <h3 className="stock-subheading">سجل المهمة</h3><ol className="task-history">{query.data!.events.map(e => <li key={e.id}><div><strong>{events[e.action] ?? ({ PAUSED: 'إيقاف مؤقت', RESUMED: 'استئناف', TIME_STOPPED: 'إيقاف وقت بواسطة المدير' } as Record<string, string>)[e.action]}</strong><time>{date.format(new Date(e.createdAt))}</time></div><span>{e.actorName}{e.workerName && ` · ${e.workerName}`}</span>{e.serialNumber && <span className="record-code" dir="ltr">{e.serialNumber}</span>}{e.notes && <p>{e.notes}</p>}</li>)}</ol>
  </>}</div></Modal>;
}
export function MachineAssemblyPage() {
  const { id } = useParams(); const { user } = useAuth(); const allowed = ['machines.view', 'tasks.view', 'orders.view'].every(p => can(user, p));
  const query = useQuery({ queryKey: ['assembly', 'machine', id], queryFn: () => api<MachineAssembly>(`/machines/${id}/assembly`), enabled: allowed });
  const [history, setHistory] = useState(false);
  if (!allowed) return <Denied />;
  return <div className="machine-assembly"><Link className="inline-link" to="/machines"><ArrowRight size={17} />الماكينات</Link><div className="page-heading"><div><div className="eyebrow" dir="ltr">{query.data?.serialNumber}</div><h1>{query.data?.name ?? 'تركيب الماكينة'}</h1><span className="muted">نسخة التركيب: {query.data?.configurationVersion ?? '—'}</span></div><IconButton label="تحديث تركيب الماكينة" disabled={query.isFetching} onClick={() => void query.refetch()}><RefreshCw size={18} /></IconButton></div>
    {query.isPending ? <Busy /> : query.error ? <QueryError error={query.error} retry={() => void query.refetch()} /> : <StockTable rows={(query.data?.installations ?? []).filter(i => history || !i.removedAt)} searchText={i => `${i.serialNumber} ${i.partCode} ${i.partName} ${slot(i)} ${i.orderCode} ${i.workerName}`} filters={<label className="check-line"><input type="checkbox" checked={history} onChange={e => setHistory(e.target.checked)} />إظهار سجل الفك أيضًا</label>} columns={[
      { label: 'الموضع / النسخة', cell: i => <strong dir="ltr">{slot(i)}</strong> }, { label: 'القطعة / Serial', cell: i => <><strong>{i.partName}</strong><span className="record-code" dir="ltr">{i.partCode}</span><span className="record-code" dir="ltr">{i.serialNumber}</span></> }, { label: 'الطلب', cell: i => <Link className="inline-link" to={`/orders/${i.orderId}`}>{i.orderCode}</Link> },
      { label: 'العامل', cell: i => i.workerName }, { label: 'التركيب · القاهرة', cell: i => <>{date.format(new Date(i.installedAt))}<span className="cell-notes">{i.installNotes}</span></> }, { label: 'الحالة', cell: i => <span className={`badge ${i.removedAt ? 'draft' : 'active'}`}>{i.removedAt ? 'تم فكه' : 'مركب حاليًا'}</span> },
      { label: 'الفك', cell: i => i.removedAt ? <>{date.format(new Date(i.removedAt))}<span className="cell-notes">{i.removedBy} · {i.removalReason}</span></> : <Link className="inline-link" to={`/tasks?order=${i.orderId}`}><Wrench size={17} />مهام التجميع</Link> },
    ]} />}</div>;
}
