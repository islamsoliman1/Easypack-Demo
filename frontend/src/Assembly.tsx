import { formatDisplay } from './i18n';
import { translateUi, translateDisplay, useLanguage, dateFormatter, getLocale, getDirection } from './i18n';
import { useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { useQuery, localizedKey } from './localizedQuery';
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
const date = dateFormatter({ dateStyle: 'short', timeStyle: 'short', timeZone: 'Africa/Cairo' });
const states: Record<string, string> = { PENDING: 'لم يبدأ', IN_PROGRESS: 'جارٍ التركيب', INSTALLED: 'مركب' };
const orderStates: Record<string, string> = { OPEN: 'مفتوح', IN_PROGRESS: 'قيد التجميع', AWAITING_TESTING: 'بانتظار تجربة التشغيل', TESTING: 'قيد تجربة التشغيل', REWORK: 'معالجة بعد التجربة', COMPLETED: 'مكتمل', CANCELLED: 'ملغي' };
const events: Record<string, string> = { ASSIGNED: 'تكليف', UNASSIGNED: 'إلغاء التكليف', STARTED: 'بدء التركيب', PIECE_ISSUED: 'صرف القطعة', PIECE_RETURNED: 'إرجاع القطعة', INSTALLED: 'تركيب', UNINSTALLED: 'فك', REJECTED: 'رفض قطعة معيبة' };
const slot = (t: { positionCode: string; occurrence: number }) => `${translateDisplay(t.positionCode)} / ${translateDisplay(t.occurrence)}`;
const running = (t: Task) => (t as Task & { workRunning: boolean }).workRunning;
function State({ value }: { value: string }) {
  useLanguage(); return <span className={`badge ${translateDisplay(value === 'INSTALLED' ? 'active' : 'draft')}`}>{formatDisplay(states[value])}</span>; }

export function TasksPage() {
  useLanguage();
  const { user } = useAuth(); const client = useQueryClient(); const [params, setParams] = useSearchParams();
  const all = can(user, 'tasks.view') && can(user, 'orders.view'), allowed = all || can(user, 'tasks.view-assigned');
  const order = params.get('order'), assign = all && can(user, 'tasks.assign');
  const query = useQuery({ queryKey: ['assembly', 'tasks', order], queryFn: () => api<Task[]>(`${translateDisplay(root)}${translateDisplay(order ? `?orderId=${translateDisplay(encodeURIComponent(order))}` : '')}`), enabled: allowed });
  const workers = useQuery({ queryKey: ['assembly', 'workers'], queryFn: () => api<Person[]>('/assembly/workers'), enabled: assign });
  const [status, setStatus] = useState('all'), [workerFilter, setWorkerFilter] = useState('all');
  const detail = params.get('task');
  const setDetail = (id: string | null) => { const next = new URLSearchParams(params); if (id) next.set('task', id); else next.delete('task'); setParams(next); };
  const [action, setAction] = useState<{ kind: ActionKind; task: Task } | null>(null), [notice, setNotice] = useState('');
  if (!allowed) return <Denied />;
  return <div className="assembly-tasks">{formatDisplay(order && all && <Link className="inline-link" to={`/orders/${translateDisplay(order)}`}><ArrowRight size={17} />{translateUi("طلب التصنيع")}</Link>)}
    <div className="page-heading"><div><div className="eyebrow">{translateUi("التجميع")}</div><h1>{formatDisplay(all ? 'مهام التجميع' : 'مهامي')}</h1></div><IconButton label={translateUi("تحديث المهام")} disabled={query.isFetching} onClick={() => { void query.refetch(); if (assign) void workers.refetch(); }}><RefreshCw size={18} /></IconButton></div>
    {formatDisplay(notice && <div className="notice" role="status"><Check size={17} />{formatDisplay(notice)}</div>)}
    {formatDisplay(query.isPending ? <Busy /> : query.error ? <QueryError error={query.error} retry={() => void query.refetch()} /> : <StockTable rows={(query.data ?? []).filter(t => (status === 'all' || t.status === status) && (workerFilter === 'all' || (workerFilter === 'mine' ? t.workerId === user.id : t.workerId === null)))} searchText={t => `${translateDisplay(t.orderCode)} ${translateDisplay(t.orderTitle)} ${translateDisplay(t.machineSerial)} ${translateDisplay(t.partCode)} ${translateDisplay(t.partName)} ${translateDisplay(slot(t))} ${translateDisplay(t.workerName ?? '')} ${translateDisplay(t.serialNumber ?? '')}`} filters={<>
      <select aria-label={translateUi("حالة المهمة")} value={status} onChange={e => setStatus(e.target.value)}><option value="all">{translateUi("كل الحالات")}</option>{formatDisplay(Object.entries(states).map(([v, name]) => <option key={v} value={v}>{formatDisplay(name)}</option>))}</select>
      {formatDisplay(all && <select aria-label={translateUi("تكليف المهام")} value={workerFilter} onChange={e => setWorkerFilter(e.target.value)}><option value="all">{translateUi("كل العمال")}</option><option value="unassigned">{translateUi("بدون تكليف")}</option><option value="mine">{translateUi("المسند لي")}</option></select>)}</>} columns={[
        { label: 'الطلب / الماكينة', cell: t => <>{formatDisplay(all ? <Link className="inline-link" to={`/orders/${translateDisplay(t.orderId)}`}><strong>{formatDisplay(t.orderTitle)}</strong></Link> : <strong>{formatDisplay(t.orderTitle)}</strong>)}<span className="record-code" dir="ltr">{formatDisplay(t.orderCode)} · {formatDisplay(t.machineSerial)}</span><span className="badge draft">{formatDisplay(orderStates[t.orderStatus])}</span></> },
        { label: 'الموضع / النسخة', cell: t => <strong dir="ltr">{formatDisplay(slot(t))}</strong> }, { label: 'القطعة', cell: t => <><strong>{formatDisplay(t.partName)}</strong><span className="record-code" dir="ltr">{formatDisplay(t.partCode)}</span></> },
        { label: 'العامل', cell: t => t.workerName || 'بدون تكليف' }, { label: 'Serial Number', cell: t => <>{formatDisplay(t.serialNumber ? <strong dir="ltr">{formatDisplay(t.serialNumber)}</strong> : 'لم تُصرف قطعة')}{formatDisplay(t.pieceStatus === 'ISSUED' && <span className="badge draft">{translateUi("مصروف للعامل")}</span>)}{formatDisplay(t.pieceStatus === 'REJECTED' && <span className="badge inactive">{translateUi("مرفوض في عهدة العامل")}</span>)}</> },
        { label: 'التركيب / التسليم', cell: t => <>{formatDisplay(t.expectedMinutes)} {translateUi("دقيقة")}<span className="record-code">{formatDisplay(date.format(new Date(t.deliveryAt)))}</span></> }, { label: 'الحالة', cell: t => <State value={t.status} /> },
        { label: 'الإجراءات', cell: t => <div className="row-actions"><IconButton label={formatDisplay(translateUi("تفاصيل {{v0}}", {v0: slot(t)}))} onClick={() => setDetail(t.id)}><Eye size={17} /></IconButton>
          {formatDisplay(['OPEN', 'IN_PROGRESS', 'AWAITING_TESTING', 'TESTING', 'REWORK'].includes(t.orderStatus) && <>{formatDisplay(assign && t.status !== 'INSTALLED' && !t.pieceId && <IconButton label={formatDisplay(translateUi("تكليف {{v0}}", {v0: slot(t)}))} onClick={() => setAction({ kind: 'assign', task: t })}><UserRoundPlus size={17} /></IconButton>)}
            {formatDisplay(t.workerId === user.id && can(user, 'tasks.work') && t.pieceStatus === 'ISSUED' && t.status === 'PENDING' && <IconButton label={formatDisplay(translateUi("بدء {{v0}}", {v0: slot(t)}))} onClick={() => setAction({ kind: 'start', task: t })}><Play size={17} /></IconButton>)}
            {formatDisplay(t.workerId === user.id && can(user, 'tasks.work') && t.pieceStatus === 'ISSUED' && t.status === 'IN_PROGRESS' && <IconButton label={formatDisplay(translateUi("تركيب {{v0}}", {v0: slot(t)}))} onClick={() => setAction({ kind: 'install', task: t })}><Wrench size={17} /></IconButton>)}
            {formatDisplay(t.workerId === user.id && can(user, 'tasks.work') && t.pieceStatus === 'ISSUED' && t.status === 'IN_PROGRESS' && <IconButton label={formatDisplay(`${translateDisplay(running(t) ? 'إيقاف الوقت مؤقتًا' : 'استئناف الوقت')} ${translateDisplay(slot(t))}`)} onClick={() => setAction({ kind: running(t) ? 'pause' : 'resume', task: t })}>{formatDisplay(running(t) ? <Pause size={17} /> : <Play size={17} />)}</IconButton>)}
            {formatDisplay(all && can(user, 'time.manage') && t.status === 'IN_PROGRESS' && running(t) && <IconButton label={formatDisplay(translateUi("إيقاف وقت العامل {{v0}}", {v0: slot(t)}))} onClick={() => setAction({ kind: 'stop-time', task: t })}><Square size={17} /></IconButton>)}
            {formatDisplay(t.workerId === user.id && can(user, 'tasks.reject') && t.pieceStatus === 'ISSUED' && <IconButton label={formatDisplay(translateUi("رفض {{v0}}", {v0: slot(t)}))} onClick={() => setAction({ kind: 'reject', task: t })}><CircleAlert size={17} /></IconButton>)}
            {formatDisplay(can(user, 'tasks.uninstall') && t.status === 'INSTALLED' && (all || t.workerId === user.id) && <IconButton label={formatDisplay(translateUi("فك {{v0}}", {v0: slot(t)}))} onClick={() => setAction({ kind: 'uninstall', task: t })}><Unplug size={17} /></IconButton>)}</>)}
        </div> },
      ]} />)}
    {formatDisplay(detail && <TaskDetail id={detail} close={() => setDetail(null)} />)}
    {formatDisplay(action && <TaskAction key={`${translateDisplay(action.kind)}-${translateDisplay(action.task.id)}`} action={action} workers={workers.data} workersError={workers.error} retry={() => void workers.refetch()} close={() => setAction(null)} saved={async () => { setAction(null); setNotice('تم حفظ إجراء التجميع'); await invalidateStock(client); }} />)}
  </div>;
}
function TaskAction({ action, workers, workersError, retry, close, saved }: { action: { kind: ActionKind; task: Task }; workers?: Person[]; workersError: unknown; retry: () => void; close: () => void; saved: () => Promise<void> }) {
  useLanguage();
  const { kind, task } = action; const [worker, setWorker] = useState(task.workerId ?? ''), [notes, setNotes] = useState(''), [pending, setPending] = useState(false), [error, setError] = useState<unknown>(null);
  const names = { assign: 'تكليف عامل', start: 'بدء التركيب', install: 'تأكيد التركيب', uninstall: 'فك القطعة', reject: 'رفض القطعة', pause: 'إيقاف الوقت مؤقتًا', resume: 'استئناف الوقت', 'stop-time': 'إيقاف وقت العامل' };
  const noteLabel = kind === 'reject' ? 'سبب الرفض' : kind === 'uninstall' ? 'سبب الفك' : 'ملاحظات التجميع';
  return <Modal title={formatDisplay(`${translateDisplay(names[kind])} · ${translateDisplay(slot(task))}`)} close={() => { if (!pending) close(); }}><form className="form-stack" onSubmit={async e => {
    e.preventDefault(); setPending(true); setError(null);
    try { await api(`${translateDisplay(root)}/${translateDisplay(task.id)}/${translateDisplay(kind)}`, 'POST', { version: task.version, workerId: worker || null, notes, reason: notes }); await saved(); } catch (e) { setError(e); } finally { setPending(false); }
  }}><ErrorBox error={error} /><div className="raw-source"><strong>{formatDisplay(task.partName)}</strong><span dir="ltr">{formatDisplay(task.serialNumber ?? task.partCode)}</span><span>{formatDisplay(task.machineName)}</span></div>
    {formatDisplay(kind === 'assign' && <>{formatDisplay(workersError && <QueryError error={workersError} retry={retry} />)}<label>{translateUi("العامل المكلف")}<select aria-label={translateUi("العامل المكلف")} value={worker} onChange={e => setWorker(e.target.value)} disabled={pending || !workers}><option value="">{translateUi("بدون تكليف")}</option>{formatDisplay(workers?.map(w => <option key={w.id} value={w.id}>{formatDisplay(w.name)} · {formatDisplay(w.email)}</option>))}</select></label></>)}
    <label>{formatDisplay(noteLabel)}<textarea aria-label={formatDisplay(noteLabel)} value={notes} onChange={e => setNotes(e.target.value)} maxLength={1000} rows={3} required={['reject', 'uninstall', 'pause', 'resume', 'stop-time'].includes(kind) || (kind === 'assign' && !!task.workerId && worker !== task.workerId)} disabled={pending} /></label>
    <div className="form-actions"><button className="primary" disabled={pending || (kind === 'assign' && (!workers || !!workersError))}><Check size={17} />{formatDisplay(names[kind])}</button><button type="button" onClick={close} disabled={pending}>{translateUi("رجوع")}</button></div>
  </form></Modal>;
}
function TaskDetail({ id, close }: { id: string; close: () => void }) {
  useLanguage();
  const query = useQuery({ queryKey: ['assembly', 'task', id], queryFn: () => api<Detail>(`${translateDisplay(root)}/${translateDisplay(id)}`), refetchInterval: 15000 }); const t = query.data?.task;
  return <Modal title={formatDisplay(t ? translateUi("تفاصيل {{v0}}", {v0: slot(t)}) : 'تفاصيل المهمة')} close={close}><div className="modal-body">{formatDisplay(query.isPending ? <Busy /> : query.error ? <QueryError error={query.error} retry={() => void query.refetch()} /> : t && <>
    <dl className="order-meta"><div><dt>{translateUi("الطلب")}</dt><dd>{formatDisplay(t.orderTitle)}</dd></div><div><dt>{translateUi("الماكينة")}</dt><dd>{formatDisplay(t.machineName)}<span className="record-code" dir="ltr">{formatDisplay(t.machineSerial)}</span></dd></div><div><dt>{translateUi("القطعة")}</dt><dd>{formatDisplay(t.partName)}</dd></div><div><dt>{translateUi("العامل")}</dt><dd>{formatDisplay(t.workerName || 'بدون تكليف')}</dd></div><div><dt>{translateUi("الحالة")}</dt><dd><State value={t.status} /></dd></div><div><dt>Serial Number</dt><dd dir="ltr">{formatDisplay(t.serialNumber || '—')}</dd></div><div><dt>{translateUi("الوقت المتوقع")}</dt><dd>{formatDisplay(t.expectedMinutes)} {translateUi("دقيقة")}</dd></div>
      {formatDisplay(t.startedAt && <div><dt>{translateUi("بداية الدورة الحالية")}</dt><dd>{formatDisplay(date.format(new Date(t.startedAt)))}</dd></div>)}{formatDisplay(t.installedAt && <div><dt>{translateUi("وقت التركيب")}</dt><dd>{formatDisplay(date.format(new Date(t.installedAt)))}</dd></div>)}</dl>
    {formatDisplay(t.requirementNotes && <p className="order-notes">{formatDisplay(t.requirementNotes)}</p>)}<div className="bom-actions"><FileButton kind="ORDER" id={t.orderId} name={translateUi("الطلب")} /><FileButton kind="MACHINE" id={t.machineId} name={translateUi("الماكينة")} /><FileButton kind="PART" id={t.partId} name={translateUi("القطعة")} /></div>
    <h3 className="stock-subheading">{translateUi("وقت العمل المسجل")}</h3><dl className="order-meta"><div><dt>{translateUi("داخل الوردية")}</dt><dd>{formatDisplay(minutes(query.data!.workTime.total.grossSeconds))}</dd></div><div><dt>{translateUi("الصافي")}</dt><dd>{formatDisplay(minutes(query.data!.workTime.total.netSeconds))}</dd></div></dl><ol className="task-history">{formatDisplay(query.data!.workTime.sessions.map(s => <li key={s.id}><div><strong>{formatDisplay(s.workerName)} · {formatDisplay(s.running ? 'وقت جارٍ' : 'متوقف')}</strong><span>{formatDisplay(minutes(s.grossSeconds))}</span></div><time>{formatDisplay(date.format(new Date(s.startedAt)))} · {formatDisplay(s.endedAt ? date.format(new Date(s.endedAt)) : s.running ? 'جارٍ' : 'انتهت الوردية')}</time><p>{formatDisplay(({ SHIFT_ENDED: 'انتهاء الوردية', OUTSIDE_SHIFT: 'خارج الوردية دون احتساب وقت', INSTALLED: 'تم التركيب', PIECE_REJECTED: 'رفض القطعة', PIECE_RETURNED: 'إرجاع القطعة', ASSIGNMENT_CHANGED: 'تغيير التكليف' } as Record<string, string>)[s.reason ?? ''] ?? s.reason)}</p></li>))}</ol>
    <h3 className="stock-subheading">{translateUi("سجل المهمة")}</h3><ol className="task-history">{formatDisplay(query.data!.events.map(e => <li key={e.id}><div><strong>{formatDisplay(events[e.action] ?? ({ PAUSED: 'إيقاف مؤقت', RESUMED: 'استئناف', TIME_STOPPED: 'إيقاف وقت بواسطة المدير' } as Record<string, string>)[e.action])}</strong><time>{formatDisplay(date.format(new Date(e.createdAt)))}</time></div><span>{formatDisplay(e.actorName)}{formatDisplay(e.workerName && ` · ${translateDisplay(e.workerName)}`)}</span>{formatDisplay(e.serialNumber && <span className="record-code" dir="ltr">{formatDisplay(e.serialNumber)}</span>)}{formatDisplay(e.notes && <p>{formatDisplay(e.notes)}</p>)}</li>))}</ol>
  </>)}</div></Modal>;
}
export function MachineAssemblyPage() {
  useLanguage();
  const { id } = useParams(); const { user } = useAuth(); const allowed = ['machines.view', 'tasks.view', 'orders.view'].every(p => can(user, p));
  const query = useQuery({ queryKey: ['assembly', 'machine', id], queryFn: () => api<MachineAssembly>(`/machines/${translateDisplay(id)}/assembly`), enabled: allowed });
  const [history, setHistory] = useState(false);
  if (!allowed) return <Denied />;
  return <div className="machine-assembly"><Link className="inline-link" to="/machines"><ArrowRight size={17} />{translateUi("الماكينات")}</Link><div className="page-heading"><div><div className="eyebrow" dir="ltr">{formatDisplay(query.data?.serialNumber)}</div><h1>{formatDisplay(query.data?.name ?? 'تركيب الماكينة')}</h1><span className="muted">{translateUi("نسخة التركيب:")}{' '}{formatDisplay(query.data?.configurationVersion ?? '—')}</span></div><IconButton label={translateUi("تحديث تركيب الماكينة")} disabled={query.isFetching} onClick={() => void query.refetch()}><RefreshCw size={18} /></IconButton></div>
    {formatDisplay(query.isPending ? <Busy /> : query.error ? <QueryError error={query.error} retry={() => void query.refetch()} /> : <StockTable rows={(query.data?.installations ?? []).filter(i => history || !i.removedAt)} searchText={i => `${translateDisplay(i.serialNumber)} ${translateDisplay(i.partCode)} ${translateDisplay(i.partName)} ${translateDisplay(slot(i))} ${translateDisplay(i.orderCode)} ${translateDisplay(i.workerName)}`} filters={<label className="check-line"><input type="checkbox" checked={history} onChange={e => setHistory(e.target.checked)} />{translateUi("إظهار سجل الفك أيضًا")}</label>} columns={[
      { label: 'الموضع / النسخة', cell: i => <strong dir="ltr">{formatDisplay(slot(i))}</strong> }, { label: 'القطعة / Serial', cell: i => <><strong>{formatDisplay(i.partName)}</strong><span className="record-code" dir="ltr">{formatDisplay(i.partCode)}</span><span className="record-code" dir="ltr">{formatDisplay(i.serialNumber)}</span></> }, { label: 'الطلب', cell: i => <Link className="inline-link" to={`/orders/${translateDisplay(i.orderId)}`}>{formatDisplay(i.orderCode)}</Link> },
      { label: 'العامل', cell: i => i.workerName }, { label: 'التركيب · القاهرة', cell: i => <>{formatDisplay(date.format(new Date(i.installedAt)))}<span className="cell-notes">{formatDisplay(i.installNotes)}</span></> }, { label: 'الحالة', cell: i => <span className={`badge ${translateDisplay(i.removedAt ? 'draft' : 'active')}`}>{formatDisplay(i.removedAt ? 'تم فكه' : 'مركب حاليًا')}</span> },
      { label: 'الفك', cell: i => i.removedAt ? <>{formatDisplay(date.format(new Date(i.removedAt)))}<span className="cell-notes">{formatDisplay(i.removedBy)} · {formatDisplay(i.removalReason)}</span></> : <Link className="inline-link" to={`/tasks?order=${translateDisplay(i.orderId)}`}><Wrench size={17} />{translateUi("مهام التجميع")}</Link> },
    ]} />)}</div>;
}
