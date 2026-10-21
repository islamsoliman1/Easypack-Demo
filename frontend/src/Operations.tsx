import { formatDisplay } from './i18n';
import { SourceTextPreview } from './SourceTextPreview';
import { translateUi, translateDisplay, useLanguage, dateFormatter, getLocale, getDirection } from './i18n';
import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { useQuery, localizedKey } from './localizedQuery';
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
const format = dateFormatter({ dateStyle: 'medium', timeStyle: 'short', timeZone: 'Africa/Cairo' });
const date = (value: string | null) => value ? format.format(new Date(value)) : '—';
function Badge({ value, trial = false }: { value: string; trial?: boolean }) {
  useLanguage(); return <span className={`badge ${translateDisplay(['PASSED', 'COMPLETED'].includes(value) ? 'active' : ['FAILED', 'INVALIDATED', 'ABORTED', 'CANCELLED', 'REWORK'].includes(value) ? 'inactive' : 'draft')}`}>{formatDisplay((trial ? trialStates : orderStates)[value] ?? value)}</span>; }

export function OperationsPage() {
  useLanguage();
  const { user } = useAuth(); const [state, setState] = useState('queue');
  const query = useQuery({ queryKey: ['operations', 'list'], queryFn: () => api<Summary[]>('/operations/orders'), enabled: can(user, 'operations.view') });
  if (!can(user, 'operations.view')) return <Denied />;
  return <><div className="page-heading"><div><div className="eyebrow">{translateUi("قسم التشغيل")}</div><h1>{translateUi("تجارب التشغيل")}</h1></div><IconButton label={translateUi("تحديث التشغيل")} disabled={query.isFetching} onClick={() => void query.refetch()}><RefreshCw size={18} /></IconButton></div>
    <Link className="inline-link" to="/operations/templates"><ClipboardCheck size={17} />{translateUi("قوالب فحص التشغيل")}</Link>
    {formatDisplay(query.isPending ? <Busy /> : query.error ? <QueryError error={query.error} retry={() => void query.refetch()} /> : <StockTable rows={(query.data ?? []).filter(o => state === 'all' || (state === 'queue' ? ['AWAITING_TESTING', 'TESTING', 'REWORK'].includes(o.status) : o.status === state))} searchText={o => `${translateDisplay(o.code)} ${translateDisplay(o.title)} ${translateDisplay(o.machineName)} ${translateDisplay(o.machineSerial)}`} placeholder={translateUi("بحث بالطلب أو الماكينة")} filters={<select aria-label={translateUi("حالة التشغيل")} value={state} onChange={e => setState(e.target.value)}><option value="queue">{translateUi("قائمة انتظار التشغيل")}</option><option value="all">{translateUi("كل الطلبات")}</option>{formatDisplay(Object.entries(orderStates).map(([v, label]) => <option key={v} value={v}>{formatDisplay(label)}</option>))}</select>} columns={[
      { label: 'الطلب', cell: o => <><Link className="inline-link" to={`/operations/${translateDisplay(o.id)}`}><ClipboardCheck size={17} /><strong>{formatDisplay(o.title)}</strong></Link><span className="record-code" dir="ltr">{formatDisplay(o.code)}</span></> },
      { label: 'الماكينة', cell: o => <><strong>{formatDisplay(o.machineName)}</strong><span className="record-code" dir="ltr">{formatDisplay(o.machineSerial)}</span></> },
      { label: 'التجميع / العيوب', cell: o => <>{formatDisplay(o.installedCount)} / {formatDisplay(o.requiredCount)}<span className="record-code">{translateUi("بلاغات مفتوحة:")}{' '}{formatDisplay(o.blockingDefects)}</span></> },
      { label: 'الحالة', cell: o => <><Badge value={o.status} />{formatDisplay(o.status === 'COMPLETED' && <span className="record-code">{formatDisplay(o.deliveredAt ? 'تم التسليم' : 'بانتظار التسليم الفعلي')}</span>)}</> },
      { label: 'موعد التسليم · القاهرة', cell: o => <>{formatDisplay(date(o.deliveryAt))}{formatDisplay(o.status !== 'CANCELLED' && !o.deliveredAt && new Date(o.deliveryAt).getTime() < Date.now() && <div className="field-error">{translateUi("متأخر")}</div>)}</> },
    ]} />)}
  </>;
}
export function OperationsOrderPage() {
  useLanguage(); const { id } = useParams(); return <OperationsDetail key={id} id={id!} />; }
function OperationsDetail({ id }: { id: string }) {
  useLanguage();
  const { user } = useAuth(); const client = useQueryClient();
  const query = useQuery({ queryKey: ['operations', id], queryFn: () => api<Detail>(`/operations/orders/${translateDisplay(id)}`), enabled: can(user, 'operations.view') });
  const [action, setAction] = useState<{ kind: Action; data: Detail } | null>(null), [selected, setSelected] = useState(''), [notice, setNotice] = useState('');
  if (!can(user, 'operations.view')) return <Denied />;
  if (query.isPending) return <Busy />;
  if (query.error) return <QueryError error={query.error} retry={() => void query.refetch()} />;
  const d = query.data!, o = d.order, latest = d.trials[0], trial = d.trials.find(t => t.id === selected) ?? latest;
  const ready = o.requiredCount > 0 && o.installedCount === o.requiredCount && !o.blockingDefects;
  const open = (kind: Action) => { setNotice(''); setAction({ kind, data: d }); };
  return <><Link className="inline-link" to="/operations"><ArrowRight size={17} />{translateUi("تجارب التشغيل")}</Link><div className="page-heading order-heading"><div><div className="eyebrow" dir="ltr">{formatDisplay(o.code)}</div><h1>{formatDisplay(o.title)}</h1></div><div className="bom-actions">
    <IconButton label={translateUi("تحديث تجربة التشغيل")} disabled={query.isFetching} onClick={() => void query.refetch()}><RefreshCw size={18} /></IconButton>
    {formatDisplay(o.status === 'AWAITING_TESTING' && ready && can(user, 'operations.start') && <button className="primary" onClick={() => open('start')}><Play size={17} />{formatDisplay(actionNames.start)}</button>)}
    {formatDisplay(o.status === 'TESTING' && latest?.status === 'RUNNING' && can(user, 'operations.finish') && <><button className="primary" onClick={() => open('finish')}><ClipboardCheck size={17} />{formatDisplay(actionNames.finish)}</button><button onClick={() => open('abort')}><X size={17} />{formatDisplay(actionNames.abort)}</button></>)}
    {formatDisplay(o.status === 'REWORK' && ready && latest && !latest.resolvedAt && ['FAILED', 'INVALIDATED', 'ABORTED'].includes(latest.status) && can(user, 'operations.resolve') && <button onClick={() => open('resolve')}><RotateCcw size={17} />{formatDisplay(actionNames.resolve)}</button>)}
    {formatDisplay(o.status === 'COMPLETED' && !o.deliveredAt && can(user, 'operations.deliver') && <button className="primary" onClick={() => open('deliver')}><Truck size={17} />{formatDisplay(actionNames.deliver)}</button>)}
  </div></div>{formatDisplay(notice && <div className="notice" role="status"><Check size={17} />{formatDisplay(notice)}</div>)}
    <dl className="order-meta"><div><dt>{translateUi("الحالة")}</dt><dd><Badge value={o.status} /></dd></div><div><dt>{translateUi("الماكينة")}</dt><dd>{formatDisplay(o.machineName)}<span className="record-code" dir="ltr">{formatDisplay(o.machineSerial)}</span></dd></div><div><dt>{translateUi("التركيب الحالي")}</dt><dd>{translateUi("نسخة")}{' '}{formatDisplay(o.configurationVersion)} · {formatDisplay(o.installedCount)} / {formatDisplay(o.requiredCount)} {translateUi("قطعة")}</dd></div><div><dt>{translateUi("بلاغات عيوب مفتوحة")}</dt><dd>{formatDisplay(o.blockingDefects)}</dd></div><div><dt>{translateUi("موعد التسليم · القاهرة")}</dt><dd>{formatDisplay(date(o.deliveryAt))}</dd></div><div><dt>{translateUi("آخر نتيجة تشغيل")}</dt><dd>{formatDisplay(date(o.testedAt))}</dd></div><div><dt>{translateUi("اكتمال الطلب")}</dt><dd>{formatDisplay(date(o.completedAt))}</dd></div><div><dt>{translateUi("التسليم الفعلي")}</dt><dd>{formatDisplay(date(o.deliveredAt))}</dd></div></dl>
    {formatDisplay(o.deliveryNotes && <p className="order-notes">{formatDisplay(o.deliveryNotes)}</p>)}
    {formatDisplay(can(user, 'orders.view') && <Link className="inline-link" to={`/orders/${translateDisplay(id)}`}><ClipboardCheck size={17} />{translateUi("طلب التصنيع والتجميع")}</Link>)}
    <div className="bom-summary"><h2>{translateUi("سجل التجارب")}<span className="count">{formatDisplay(d.trials.length)}</span></h2>{formatDisplay(trial && <select aria-label={translateUi("التجربة المعروضة")} value={trial.id} onChange={e => setSelected(e.target.value)}>{formatDisplay(d.trials.map((t, i) => <option key={t.id} value={t.id}>{translateUi("تجربة")}{' '}{formatDisplay(d.trials.length - i)} · {formatDisplay(trialStates[t.status])} · {formatDisplay(date(t.startedAt))}</option>))}</select>)}</div>
    {formatDisplay(!trial ? <div className="state">{translateUi("لم تبدأ تجربة لهذا الطلب")}</div> : <section className="operations-trial">
      <dl className="order-meta"><div><dt>{translateUi("نتيجة التجربة")}</dt><dd><Badge value={trial.status} trial /></dd></div><div><dt>{translateUi("نسخة التركيب المختبرة")}</dt><dd>{formatDisplay(trial.configurationVersion)}</dd></div><div><dt>{translateUi("بدأها")}</dt><dd>{formatDisplay(trial.startedBy)}<span className="record-code">{formatDisplay(date(trial.startedAt))}</span></dd></div><div><dt>{translateUi("أغلقها")}</dt><dd>{formatDisplay(trial.finishedBy ?? '—')}<span className="record-code">{formatDisplay(date(trial.finishedAt))}</span></dd></div></dl>
      {formatDisplay(trial.startNotes && <p className="order-notes">{formatDisplay(trial.startNotes)}</p>)}{formatDisplay(trial.resultNotes && <p className="order-notes">{formatDisplay(trial.resultNotes === 'Machine configuration changed during the trial' ? 'تغير تركيب الماكينة أثناء التجربة' : trial.resultNotes)}</p>)}
      {formatDisplay(trial.resolvedAt && <div className="notice"><Check size={17} /><div><strong>{translateUi("تمت المعالجة ·")}{' '}{formatDisplay(trial.resolvedBy)} · {formatDisplay(date(trial.resolvedAt))}</strong><p>{formatDisplay(trial.resolutionNotes)}</p></div></div>)}
      <div className="table-wrap"><table><thead><tr><th>{translateUi("بند الفحص")}</th><th>{translateUi("معيار النجاح")}</th><th>{translateUi("النتيجة")}</th><th>{translateUi("الملاحظات")}</th></tr></thead><tbody>{formatDisplay(trial.checks.map(c => <tr key={c.id}><td>{formatDisplay(c.label)}</td><td className="cell-notes">{formatDisplay(c.criterion)}</td><td>{formatDisplay(resultStates[c.result])}</td><td className="cell-notes">{formatDisplay(c.notes || '—')}</td></tr>))}</tbody></table></div>
      <div className="bom-summary"><h2>{translateUi("القطع عند بدء التجربة")}</h2><span className="muted">{formatDisplay(trial.configuration.length)} {translateUi("قطعة")}</span></div><div className="table-wrap"><table><thead><tr><th>{translateUi("الموضع")}</th><th>{translateUi("القطعة")}</th><th>Serial Number</th></tr></thead><tbody>{formatDisplay(trial.configuration.map(p => <tr key={p.installationId}><td dir="ltr">{formatDisplay(p.positionCode)} / {formatDisplay(p.occurrence)}</td><td>{formatDisplay(p.partName)}<span className="record-code" dir="ltr">{formatDisplay(p.partCode)}</span></td><td dir="ltr">{formatDisplay(p.serialNumber)}</td></tr>))}</tbody></table></div>
    </section>)}
    <div className="bom-summary"><h2>{translateUi("سجل إجراءات التشغيل")}</h2></div><ol className="task-history">{formatDisplay(d.events.map(e => <li key={e.id}><div><strong>{formatDisplay(eventStates[e.action])}</strong><time>{formatDisplay(date(e.createdAt))}</time></div><span>{formatDisplay(e.actorName)}</span><p>{formatDisplay(e.action === 'INVALIDATED' ? 'تغير تركيب الماكينة أثناء التجربة' : e.notes)}</p></li>))}</ol>
    {formatDisplay(action && <OperationsAction kind={action.kind} data={action.data} close={() => setAction(null)} saved={async result => { client.setQueryData(localizedKey(['operations', id]), result); setAction(null); setSelected(''); setNotice('تم حفظ إجراء التشغيل'); await invalidateStock(client); }} />)}
  </>;
}
function OperationsAction({ kind, data, close, saved }: { kind: Action; data: Detail; close: () => void; saved: (d: Detail) => Promise<void> }) {
  useLanguage();
  const templates = useQuery({ queryKey: ['operations', 'templates'], queryFn: () => api<Template[]>('/operations/templates'), enabled: kind === 'start' });
  const [checks, setChecks] = useState(() => data.trials[0]?.checks.map(c => ({ label: c.label, criterion: c.criterion })) ?? [{ label: '', criterion: '' }]);
  const [results, setResults] = useState(() => data.trials[0]?.checks.map(c => ({ id: c.id, result: '', notes: '' })) ?? []);
  const [notes, setNotes] = useState(''), [pending, setPending] = useState(false), [error, setError] = useState<unknown>(null);
  const noteLabel = kind === 'resolve' ? 'تفاصيل المعالجة' : kind === 'abort' ? 'سبب إيقاف التجربة' : kind === 'deliver' ? 'بيانات التسليم والمستلم' : kind === 'finish' ? 'خلاصة التجربة' : 'ملاحظات بدء التجربة';
  const failed = results.some(r => r.result === 'FAIL');
  const valid = (kind === 'start' ? checks.length > 0 && checks.every(c => c.label.trim() && c.criterion.trim()) : !!notes.trim()) && (kind !== 'finish' || results.every(r => r.result && (r.result !== 'FAIL' || r.notes.trim())));
  return <Modal title={formatDisplay(actionNames[kind])} close={() => { if (!pending) close(); }}><form className="form-stack" onSubmit={async e => {
    e.preventDefault(); if (!valid || pending) return; setPending(true); setError(null);
    const o = data.order, t = data.trials[0];
    const endpoint = ['start', 'deliver'].includes(kind) ? `/operations/orders/${translateDisplay(o.id)}/${translateDisplay(kind)}` : `/operations/trials/${translateDisplay(t.id)}/${translateDisplay(kind)}`;
    const body = kind === 'start' ? { orderVersion: o.version, configurationVersion: o.configurationVersion, checks, notes } : kind === 'finish' ? { version: t.version, outcome: failed ? 'FAILED' : 'PASSED', checks: results, notes } : kind === 'resolve' ? { version: t.version, orderVersion: o.version, notes } : { version: kind === 'deliver' ? o.version : t.version, notes };
    try { await saved(await api<Detail>(endpoint, 'POST', body)); } catch (e) { setError(e); } finally { setPending(false); }
  }}><SourceTextPreview values={{ checks }} /><ErrorBox error={error} /><fieldset disabled={pending} className="operations-form">
    {formatDisplay(kind === 'start' && <><ErrorBox error={templates.error} /><label>{translateUi("قالب الفحص")}<select aria-label={translateUi("قالب الفحص")} defaultValue="" onChange={e => { const t = templates.data?.find(v => v.id === e.target.value); if (t) setChecks(t.checks.map(c => ({ ...c }))); }}><option value="">{translateUi("فحص مخصص / آخر تجربة")}</option>{formatDisplay(templates.data?.filter(t => t.active).map(t => <option key={t.id} value={t.id}>{formatDisplay(t.name)}</option>))}</select></label></>)}
    {formatDisplay(kind === 'start' && <><div className="bom-summary"><h3>{translateUi("بنود الفحص ومعايير النجاح")}</h3><IconButton label={translateUi("إضافة بند فحص")} disabled={checks.length >= 30} onClick={() => setChecks([...checks, { label: '', criterion: '' }])}><Plus size={18} /></IconButton></div>{formatDisplay(checks.map((c, i) => <div className="inspection-row" key={i}><label>{translateUi("بند الفحص")}{' '}{formatDisplay(i + 1)}<input aria-label={formatDisplay(translateUi("بند الفحص {{v0}}", {v0: i + 1}))} maxLength={200} required value={c.label} onChange={e => setChecks(checks.map((v, n) => n === i ? { ...v, label: e.target.value } : v))} /></label><label>{translateUi("معيار النجاح")}{' '}{formatDisplay(i + 1)}<textarea aria-label={formatDisplay(translateUi("معيار النجاح {{v0}}", {v0: i + 1}))} maxLength={500} required rows={2} value={c.criterion} onChange={e => setChecks(checks.map((v, n) => n === i ? { ...v, criterion: e.target.value } : v))} /></label><IconButton label={formatDisplay(translateUi("حذف بند {{v0}}", {v0: i + 1}))} disabled={checks.length === 1} onClick={() => setChecks(checks.filter((_, n) => n !== i))}><Trash2 size={17} /></IconButton></div>))}</>)}
    {formatDisplay(kind === 'finish' && <>{formatDisplay(data.trials[0].checks.map((c, i) => <div className="inspection-result" key={c.id}><h3>{formatDisplay(c.label)}</h3><p className="cell-notes">{formatDisplay(c.criterion)}</p><label>{translateUi("نتيجة")}{' '}{formatDisplay(c.label)}<select aria-label={formatDisplay(translateUi("نتيجة {{v0}}", {v0: c.label}))} required value={results[i].result} onChange={e => setResults(results.map((v, n) => n === i ? { ...v, result: e.target.value } : v))}><option value="">{translateUi("لم يُفحص")}</option><option value="PASS">{translateUi("اجتاز")}</option><option value="FAIL">{translateUi("لم يجتز")}</option></select></label><label>{translateUi("ملاحظات")}{' '}{formatDisplay(c.label)}<textarea aria-label={formatDisplay(translateUi("ملاحظات {{v0}}", {v0: c.label}))} rows={2} required={results[i].result === 'FAIL'} maxLength={1000} value={results[i].notes} onChange={e => setResults(results.map((v, n) => n === i ? { ...v, notes: e.target.value } : v))} /></label></div>))}<strong>{translateUi("النتيجة:")}{' '}{formatDisplay(results.some(r => !r.result) ? 'فحص غير مكتمل' : failed ? 'فاشلة' : 'ناجحة')}</strong></>)}
    <label>{formatDisplay(noteLabel)}<textarea aria-label={formatDisplay(noteLabel)} value={notes} onChange={e => setNotes(e.target.value)} rows={3} maxLength={1000} required={kind !== 'start'} /></label>
  </fieldset><div className="form-actions"><button className="primary" disabled={pending || !valid}><Check size={17} />{formatDisplay(actionNames[kind])}</button><button type="button" disabled={pending} onClick={close}>{translateUi("رجوع")}</button></div></form></Modal>;
}
