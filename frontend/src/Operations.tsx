import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowRight, Check, ClipboardCheck, Play, Plus, RefreshCw, RotateCcw, Truck, Trash2, X } from 'lucide-react';
import { api } from './api';
import { can, useAuth } from './access';
import { Busy, Denied, ErrorBox, IconButton, Modal, QueryError } from './ui';
import { StockTable, invalidateStock } from './Warehouse';
import type { Template } from './Templates';

type Summary = { id: string; code: string; title: string; machineSerial: string; machineName: string; status: string; version: number; configurationVersion: number; requiredCount: number; installedCount: number; blockingDefects: number; reworkRequired: boolean; deliveryAt: string; assemblyCompletedAt: string | null; testedAt: string | null; completedAt: string | null; deliveredAt: string | null; deliveryNotes: string | null; latestTrialId: string | null };
type Inspection = { id: string; label: string; criterion: string; result: string; notes: string };
type Trial = { id: string; configurationVersion: number; status: string; version: number; startedBy: string; startedAt: string; startNotes: string; finishedBy: string | null; finishedAt: string | null; resultNotes: string | null; resolvedBy: string | null; resolvedAt: string | null; resolutionNotes: string | null; checks: Inspection[]; configuration: { installationId: string; positionCode: string; occurrence: number; serialNumber: string; partCode: string; partName: string }[] };
type Detail = { order: Summary; trials: Trial[]; events: { id: string; action: string; actorName: string; notes: string; createdAt: string }[] };
type Action = 'start' | 'finish' | 'abort' | 'resolve' | 'deliver';
const orderStates: Record<string, string> = { OPEN: 'مفتوح', IN_PROGRESS: 'قيد التجميع', AWAITING_TESTING: 'بانتظار تجربة التشغيل', TESTING: 'قيد تجربة التشغيل', REWORK: 'معالجة بعد التجربة', COMPLETED: 'مكتمل', CANCELLED: 'ملغي' };
const trialStates: Record<string, string> = { RUNNING: 'جارية', PASSED: 'ناجحة', FAILED: 'فاشلة', INVALIDATED: 'أُبطلت لتغير التركيب', ABORTED: 'أُوقفت' };
const resultStates: Record<string, string> = { PENDING: 'لم يُفحص', PASS: 'اجتاز', FAIL: 'لم يجتز' };
const eventStates: Record<string, string> = { STARTED: 'بدء التجربة', PASSED: 'نجاح التجربة وإكمال الطلب', FAILED: 'فشل التجربة', INVALIDATED: 'إبطال التجربة لتغير التركيب', ABORTED: 'إيقاف التجربة', RESOLVED: 'تسجيل المعالجة', DELIVERED: 'التسليم الفعلي' };
const actionNames: Record<Action, string> = { start: 'بدء تجربة جديدة', finish: 'تسجيل نتيجة التجربة', abort: 'إيقاف التجربة', resolve: 'تسجيل المعالجة وإعادة التجربة', deliver: 'تسجيل التسليم الفعلي' };
const format = new Intl.DateTimeFormat('ar-EG', { dateStyle: 'medium', timeStyle: 'short', timeZone: 'Africa/Cairo' });
const date = (value: string | null) => value ? format.format(new Date(value)) : '—';
function Badge({ value, trial = false }: { value: string; trial?: boolean }) { return <span className={`badge ${['PASSED', 'COMPLETED'].includes(value) ? 'active' : ['FAILED', 'INVALIDATED', 'ABORTED', 'CANCELLED', 'REWORK'].includes(value) ? 'inactive' : 'draft'}`}>{(trial ? trialStates : orderStates)[value] ?? value}</span>; }

export function OperationsPage() {
  const { user } = useAuth(); const [state, setState] = useState('queue');
  const query = useQuery({ queryKey: ['operations', 'list'], queryFn: () => api<Summary[]>('/operations/orders'), enabled: can(user, 'operations.view') });
  if (!can(user, 'operations.view')) return <Denied />;
  return <><div className="page-heading"><div><div className="eyebrow">قسم التشغيل</div><h1>تجارب التشغيل</h1></div><IconButton label="تحديث التشغيل" disabled={query.isFetching} onClick={() => void query.refetch()}><RefreshCw size={18} /></IconButton></div>
    <Link className="inline-link" to="/operations/templates"><ClipboardCheck size={17} />قوالب فحص التشغيل</Link>
    {query.isPending ? <Busy /> : query.error ? <QueryError error={query.error} retry={() => void query.refetch()} /> : <StockTable rows={(query.data ?? []).filter(o => state === 'all' || (state === 'queue' ? ['AWAITING_TESTING', 'TESTING', 'REWORK'].includes(o.status) : o.status === state))} searchText={o => `${o.code} ${o.title} ${o.machineName} ${o.machineSerial}`} placeholder="بحث بالطلب أو الماكينة" filters={<select aria-label="حالة التشغيل" value={state} onChange={e => setState(e.target.value)}><option value="queue">قائمة انتظار التشغيل</option><option value="all">كل الطلبات</option>{Object.entries(orderStates).map(([v, label]) => <option key={v} value={v}>{label}</option>)}</select>} columns={[
      { label: 'الطلب', cell: o => <><Link className="inline-link" to={`/operations/${o.id}`}><ClipboardCheck size={17} /><strong>{o.title}</strong></Link><span className="record-code" dir="ltr">{o.code}</span></> },
      { label: 'الماكينة', cell: o => <><strong>{o.machineName}</strong><span className="record-code" dir="ltr">{o.machineSerial}</span></> },
      { label: 'التجميع / العيوب', cell: o => <>{o.installedCount} / {o.requiredCount}<span className="record-code">بلاغات مفتوحة: {o.blockingDefects}</span></> },
      { label: 'الحالة', cell: o => <><Badge value={o.status} />{o.status === 'COMPLETED' && <span className="record-code">{o.deliveredAt ? 'تم التسليم' : 'بانتظار التسليم الفعلي'}</span>}</> },
      { label: 'موعد التسليم · القاهرة', cell: o => <>{date(o.deliveryAt)}{o.status !== 'CANCELLED' && !o.deliveredAt && new Date(o.deliveryAt).getTime() < Date.now() && <div className="field-error">متأخر</div>}</> },
    ]} />}
  </>;
}
export function OperationsOrderPage() { const { id } = useParams(); return <OperationsDetail key={id} id={id!} />; }
function OperationsDetail({ id }: { id: string }) {
  const { user } = useAuth(); const client = useQueryClient();
  const query = useQuery({ queryKey: ['operations', id], queryFn: () => api<Detail>(`/operations/orders/${id}`), enabled: can(user, 'operations.view') });
  const [action, setAction] = useState<{ kind: Action; data: Detail } | null>(null), [selected, setSelected] = useState(''), [notice, setNotice] = useState('');
  if (!can(user, 'operations.view')) return <Denied />;
  if (query.isPending) return <Busy />;
  if (query.error) return <QueryError error={query.error} retry={() => void query.refetch()} />;
  const d = query.data!, o = d.order, latest = d.trials[0], trial = d.trials.find(t => t.id === selected) ?? latest;
  const ready = o.requiredCount > 0 && o.installedCount === o.requiredCount && !o.blockingDefects;
  const open = (kind: Action) => { setNotice(''); setAction({ kind, data: d }); };
  return <><Link className="inline-link" to="/operations"><ArrowRight size={17} />تجارب التشغيل</Link><div className="page-heading order-heading"><div><div className="eyebrow" dir="ltr">{o.code}</div><h1>{o.title}</h1></div><div className="bom-actions">
    <IconButton label="تحديث تجربة التشغيل" disabled={query.isFetching} onClick={() => void query.refetch()}><RefreshCw size={18} /></IconButton>
    {o.status === 'AWAITING_TESTING' && ready && can(user, 'operations.start') && <button className="primary" onClick={() => open('start')}><Play size={17} />{actionNames.start}</button>}
    {o.status === 'TESTING' && latest?.status === 'RUNNING' && can(user, 'operations.finish') && <><button className="primary" onClick={() => open('finish')}><ClipboardCheck size={17} />{actionNames.finish}</button><button onClick={() => open('abort')}><X size={17} />{actionNames.abort}</button></>}
    {o.status === 'REWORK' && ready && latest && !latest.resolvedAt && ['FAILED', 'INVALIDATED', 'ABORTED'].includes(latest.status) && can(user, 'operations.resolve') && <button onClick={() => open('resolve')}><RotateCcw size={17} />{actionNames.resolve}</button>}
    {o.status === 'COMPLETED' && !o.deliveredAt && can(user, 'operations.deliver') && <button className="primary" onClick={() => open('deliver')}><Truck size={17} />{actionNames.deliver}</button>}
  </div></div>{notice && <div className="notice" role="status"><Check size={17} />{notice}</div>}
    <dl className="order-meta"><div><dt>الحالة</dt><dd><Badge value={o.status} /></dd></div><div><dt>الماكينة</dt><dd>{o.machineName}<span className="record-code" dir="ltr">{o.machineSerial}</span></dd></div><div><dt>التركيب الحالي</dt><dd>نسخة {o.configurationVersion} · {o.installedCount} / {o.requiredCount} قطعة</dd></div><div><dt>بلاغات عيوب مفتوحة</dt><dd>{o.blockingDefects}</dd></div><div><dt>موعد التسليم · القاهرة</dt><dd>{date(o.deliveryAt)}</dd></div><div><dt>آخر نتيجة تشغيل</dt><dd>{date(o.testedAt)}</dd></div><div><dt>اكتمال الطلب</dt><dd>{date(o.completedAt)}</dd></div><div><dt>التسليم الفعلي</dt><dd>{date(o.deliveredAt)}</dd></div></dl>
    {o.deliveryNotes && <p className="order-notes">{o.deliveryNotes}</p>}
    {can(user, 'orders.view') && <Link className="inline-link" to={`/orders/${id}`}><ClipboardCheck size={17} />طلب التصنيع والتجميع</Link>}
    <div className="bom-summary"><h2>سجل التجارب <span className="count">{d.trials.length}</span></h2>{trial && <select aria-label="التجربة المعروضة" value={trial.id} onChange={e => setSelected(e.target.value)}>{d.trials.map((t, i) => <option key={t.id} value={t.id}>تجربة {d.trials.length - i} · {trialStates[t.status]} · {date(t.startedAt)}</option>)}</select>}</div>
    {!trial ? <div className="state">لم تبدأ تجربة لهذا الطلب</div> : <section className="operations-trial">
      <dl className="order-meta"><div><dt>نتيجة التجربة</dt><dd><Badge value={trial.status} trial /></dd></div><div><dt>نسخة التركيب المختبرة</dt><dd>{trial.configurationVersion}</dd></div><div><dt>بدأها</dt><dd>{trial.startedBy}<span className="record-code">{date(trial.startedAt)}</span></dd></div><div><dt>أغلقها</dt><dd>{trial.finishedBy ?? '—'}<span className="record-code">{date(trial.finishedAt)}</span></dd></div></dl>
      {trial.startNotes && <p className="order-notes">{trial.startNotes}</p>}{trial.resultNotes && <p className="order-notes">{trial.resultNotes === 'Machine configuration changed during the trial' ? 'تغير تركيب الماكينة أثناء التجربة' : trial.resultNotes}</p>}
      {trial.resolvedAt && <div className="notice"><Check size={17} /><div><strong>تمت المعالجة · {trial.resolvedBy} · {date(trial.resolvedAt)}</strong><p>{trial.resolutionNotes}</p></div></div>}
      <div className="table-wrap"><table><thead><tr><th>بند الفحص</th><th>معيار النجاح</th><th>النتيجة</th><th>الملاحظات</th></tr></thead><tbody>{trial.checks.map(c => <tr key={c.id}><td>{c.label}</td><td className="cell-notes">{c.criterion}</td><td>{resultStates[c.result]}</td><td className="cell-notes">{c.notes || '—'}</td></tr>)}</tbody></table></div>
      <div className="bom-summary"><h2>القطع عند بدء التجربة</h2><span className="muted">{trial.configuration.length} قطعة</span></div><div className="table-wrap"><table><thead><tr><th>الموضع</th><th>القطعة</th><th>Serial Number</th></tr></thead><tbody>{trial.configuration.map(p => <tr key={p.installationId}><td dir="ltr">{p.positionCode} / {p.occurrence}</td><td>{p.partName}<span className="record-code" dir="ltr">{p.partCode}</span></td><td dir="ltr">{p.serialNumber}</td></tr>)}</tbody></table></div>
    </section>}
    <div className="bom-summary"><h2>سجل إجراءات التشغيل</h2></div><ol className="task-history">{d.events.map(e => <li key={e.id}><div><strong>{eventStates[e.action]}</strong><time>{date(e.createdAt)}</time></div><span>{e.actorName}</span><p>{e.action === 'INVALIDATED' ? 'تغير تركيب الماكينة أثناء التجربة' : e.notes}</p></li>)}</ol>
    {action && <OperationsAction kind={action.kind} data={action.data} close={() => setAction(null)} saved={async result => { client.setQueryData(['operations', id], result); setAction(null); setSelected(''); setNotice('تم حفظ إجراء التشغيل'); await invalidateStock(client); }} />}
  </>;
}
function OperationsAction({ kind, data, close, saved }: { kind: Action; data: Detail; close: () => void; saved: (d: Detail) => Promise<void> }) {
  const templates = useQuery({ queryKey: ['operations', 'templates'], queryFn: () => api<Template[]>('/operations/templates'), enabled: kind === 'start' });
  const [checks, setChecks] = useState(() => data.trials[0]?.checks.map(c => ({ label: c.label, criterion: c.criterion })) ?? [{ label: '', criterion: '' }]);
  const [results, setResults] = useState(() => data.trials[0]?.checks.map(c => ({ id: c.id, result: '', notes: '' })) ?? []);
  const [notes, setNotes] = useState(''), [pending, setPending] = useState(false), [error, setError] = useState<unknown>(null);
  const noteLabel = kind === 'resolve' ? 'تفاصيل المعالجة' : kind === 'abort' ? 'سبب إيقاف التجربة' : kind === 'deliver' ? 'بيانات التسليم والمستلم' : kind === 'finish' ? 'خلاصة التجربة' : 'ملاحظات بدء التجربة';
  const failed = results.some(r => r.result === 'FAIL');
  const valid = (kind === 'start' ? checks.length > 0 && checks.every(c => c.label.trim() && c.criterion.trim()) : !!notes.trim()) && (kind !== 'finish' || results.every(r => r.result && (r.result !== 'FAIL' || r.notes.trim())));
  return <Modal title={actionNames[kind]} close={() => { if (!pending) close(); }}><form className="form-stack" onSubmit={async e => {
    e.preventDefault(); if (!valid || pending) return; setPending(true); setError(null);
    const o = data.order, t = data.trials[0];
    const endpoint = ['start', 'deliver'].includes(kind) ? `/operations/orders/${o.id}/${kind}` : `/operations/trials/${t.id}/${kind}`;
    const body = kind === 'start' ? { orderVersion: o.version, configurationVersion: o.configurationVersion, checks, notes } : kind === 'finish' ? { version: t.version, outcome: failed ? 'FAILED' : 'PASSED', checks: results, notes } : kind === 'resolve' ? { version: t.version, orderVersion: o.version, notes } : { version: kind === 'deliver' ? o.version : t.version, notes };
    try { await saved(await api<Detail>(endpoint, 'POST', body)); } catch (e) { setError(e); } finally { setPending(false); }
  }}><ErrorBox error={error} /><fieldset disabled={pending} className="operations-form">
    {kind === 'start' && <><ErrorBox error={templates.error} /><label>قالب الفحص<select aria-label="قالب الفحص" defaultValue="" onChange={e => { const t = templates.data?.find(v => v.id === e.target.value); if (t) setChecks(t.checks.map(c => ({ ...c }))); }}><option value="">فحص مخصص / آخر تجربة</option>{templates.data?.filter(t => t.active).map(t => <option key={t.id} value={t.id}>{t.name}</option>)}</select></label></>}
    {kind === 'start' && <><div className="bom-summary"><h3>بنود الفحص ومعايير النجاح</h3><IconButton label="إضافة بند فحص" disabled={checks.length >= 30} onClick={() => setChecks([...checks, { label: '', criterion: '' }])}><Plus size={18} /></IconButton></div>{checks.map((c, i) => <div className="inspection-row" key={i}><label>بند الفحص {i + 1}<input aria-label={`بند الفحص ${i + 1}`} maxLength={200} required value={c.label} onChange={e => setChecks(checks.map((v, n) => n === i ? { ...v, label: e.target.value } : v))} /></label><label>معيار النجاح {i + 1}<textarea aria-label={`معيار النجاح ${i + 1}`} maxLength={500} required rows={2} value={c.criterion} onChange={e => setChecks(checks.map((v, n) => n === i ? { ...v, criterion: e.target.value } : v))} /></label><IconButton label={`حذف بند ${i + 1}`} disabled={checks.length === 1} onClick={() => setChecks(checks.filter((_, n) => n !== i))}><Trash2 size={17} /></IconButton></div>)}</>}
    {kind === 'finish' && <>{data.trials[0].checks.map((c, i) => <div className="inspection-result" key={c.id}><h3>{c.label}</h3><p className="cell-notes">{c.criterion}</p><label>نتيجة {c.label}<select aria-label={`نتيجة ${c.label}`} required value={results[i].result} onChange={e => setResults(results.map((v, n) => n === i ? { ...v, result: e.target.value } : v))}><option value="">لم يُفحص</option><option value="PASS">اجتاز</option><option value="FAIL">لم يجتز</option></select></label><label>ملاحظات {c.label}<textarea aria-label={`ملاحظات ${c.label}`} rows={2} required={results[i].result === 'FAIL'} maxLength={1000} value={results[i].notes} onChange={e => setResults(results.map((v, n) => n === i ? { ...v, notes: e.target.value } : v))} /></label></div>)}<strong>النتيجة: {results.some(r => !r.result) ? 'فحص غير مكتمل' : failed ? 'فاشلة' : 'ناجحة'}</strong></>}
    <label>{noteLabel}<textarea aria-label={noteLabel} value={notes} onChange={e => setNotes(e.target.value)} rows={3} maxLength={1000} required={kind !== 'start'} /></label>
  </fieldset><div className="form-actions"><button className="primary" disabled={pending || !valid}><Check size={17} />{actionNames[kind]}</button><button type="button" disabled={pending} onClick={close}>رجوع</button></div></form></Modal>;
}
