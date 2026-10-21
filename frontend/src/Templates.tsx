import { formatDisplay } from './i18n';
import { SourceTextPreview } from './SourceTextPreview';
import { translateUi, translateDisplay, useLanguage, dateFormatter, getLocale, getDirection } from './i18n';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { useQuery, localizedKey } from './localizedQuery';
import { ArrowRight, Check, Pencil, Plus, Trash2 } from 'lucide-react';
import { api } from './api';
import { can, useAuth } from './access';
import { Busy, Denied, ErrorBox, IconButton, Modal, QueryError } from './ui';
import { StockTable } from './Warehouse';

export type Template = { id: string; name: string; active: boolean; version: number; checks: { label: string; criterion: string }[] };
export function TemplatesPage() {
  useLanguage();
  const { user } = useAuth(), client = useQueryClient();
  const query = useQuery({ queryKey: ['operations', 'templates'], queryFn: () => api<Template[]>('/operations/templates'), enabled: can(user, 'operations.view') });
  const [editing, setEditing] = useState<Template | 'new' | null>(null);
  if (!can(user, 'operations.view')) return <Denied />;
  return <><Link className="inline-link" to="/operations"><ArrowRight size={17} />{translateUi("تجارب التشغيل")}</Link><div className="page-heading"><h1>{translateUi("قوالب فحص التشغيل")}</h1>{formatDisplay(can(user, 'operations.templates.manage') && <button className="primary" onClick={() => setEditing('new')}><Plus size={17} />{translateUi("قالب جديد")}</button>)}</div>
    {formatDisplay(query.isPending ? <Busy /> : query.error ? <QueryError error={query.error} retry={() => void query.refetch()} /> : <StockTable rows={query.data ?? []} searchText={t => t.name} columns={[
      { label: 'القالب', cell: t => <strong>{formatDisplay(t.name)}</strong> }, { label: 'الفحوصات', cell: t => t.checks.map(c => <div className="cell-notes" key={c.label}>{formatDisplay(c.label)} · {formatDisplay(c.criterion)}</div>) },
      { label: 'الحالة', cell: t => <span className={`badge ${translateDisplay(t.active ? 'active' : 'inactive')}`}>{formatDisplay(t.active ? 'نشط' : 'مؤرشف')}</span> },
      { label: 'الإجراءات', cell: t => can(user, 'operations.templates.manage') && <IconButton label={formatDisplay(translateUi("تعديل {{v0}}", {v0: t.name}))} onClick={() => setEditing(t)}><Pencil size={17} /></IconButton> },
    ]} />)}
    {formatDisplay(editing && <TemplateForm original={editing === 'new' ? undefined : editing} close={() => setEditing(null)} saved={async () => { setEditing(null); await client.invalidateQueries({ queryKey: ['operations', 'templates'] }); }} />)}
  </>;
}
function TemplateForm({ original, close, saved }: { original?: Template; close: () => void; saved: () => Promise<void> }) {
  useLanguage();
  const [name, setName] = useState(original?.name ?? ''), [active, setActive] = useState(original?.active ?? true), [checks, setChecks] = useState(original?.checks ?? [{ label: '', criterion: '' }]), [error, setError] = useState<unknown>(null), [pending, setPending] = useState(false);
  return <Modal title={formatDisplay(original ? 'تعديل القالب' : 'قالب جديد')} close={() => { if (!pending) close(); }}><form className="form-stack" onSubmit={async e => { e.preventDefault(); setPending(true); setError(null); try { await api(`/operations/templates${translateDisplay(original ? `/${translateDisplay(original.id)}` : '')}`, original ? 'PUT' : 'POST', { version: original?.version ?? 0, name, active, checks }); await saved(); } catch (e) { setError(e); } finally { setPending(false); } }}><SourceTextPreview values={{ name, checks }} /><ErrorBox error={error} /><fieldset className="operations-form" disabled={pending}>
    <label>{translateUi("اسم القالب")}<input required maxLength={120} value={name} onChange={e => setName(e.target.value)} /></label><label className="check-line"><input type="checkbox" checked={active} onChange={e => setActive(e.target.checked)} />{translateUi("قالب نشط")}</label>
    <div className="bom-summary"><h3>{translateUi("الفحوصات")}</h3><IconButton label={translateUi("إضافة فحص")} disabled={checks.length >= 30} onClick={() => setChecks([...checks, { label: '', criterion: '' }])}><Plus size={17} /></IconButton></div>
    {formatDisplay(checks.map((c, i) => <div className="inspection-row" key={i}><label>{translateUi("بند الفحص")}{' '}{formatDisplay(i + 1)}<input required maxLength={200} value={c.label} onChange={e => setChecks(checks.map((v, n) => n === i ? { ...v, label: e.target.value } : v))} /></label><label>{translateUi("معيار النجاح")}{' '}{formatDisplay(i + 1)}<textarea required maxLength={500} rows={2} value={c.criterion} onChange={e => setChecks(checks.map((v, n) => n === i ? { ...v, criterion: e.target.value } : v))} /></label><IconButton label={formatDisplay(translateUi("حذف فحص {{v0}}", {v0: i + 1}))} disabled={checks.length === 1} onClick={() => setChecks(checks.filter((_, n) => n !== i))}><Trash2 size={17} /></IconButton></div>))}
  </fieldset><div className="form-actions"><button className="primary" disabled={pending}><Check size={17} />{translateUi("حفظ")}</button><button type="button" onClick={close} disabled={pending}>{translateUi("رجوع")}</button></div></form></Modal>;
}
