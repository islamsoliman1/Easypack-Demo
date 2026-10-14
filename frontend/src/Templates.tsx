import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowRight, Check, Pencil, Plus, Trash2 } from 'lucide-react';
import { api } from './api';
import { can, useAuth } from './access';
import { Busy, Denied, ErrorBox, IconButton, Modal, QueryError } from './ui';
import { StockTable } from './Warehouse';

export type Template = { id: string; name: string; active: boolean; version: number; checks: { label: string; criterion: string }[] };
export function TemplatesPage() {
  const { user } = useAuth(), client = useQueryClient();
  const query = useQuery({ queryKey: ['operations', 'templates'], queryFn: () => api<Template[]>('/operations/templates'), enabled: can(user, 'operations.view') });
  const [editing, setEditing] = useState<Template | 'new' | null>(null);
  if (!can(user, 'operations.view')) return <Denied />;
  return <><Link className="inline-link" to="/operations"><ArrowRight size={17} />تجارب التشغيل</Link><div className="page-heading"><h1>قوالب فحص التشغيل</h1>{can(user, 'operations.templates.manage') && <button className="primary" onClick={() => setEditing('new')}><Plus size={17} />قالب جديد</button>}</div>
    {query.isPending ? <Busy /> : query.error ? <QueryError error={query.error} retry={() => void query.refetch()} /> : <StockTable rows={query.data ?? []} searchText={t => t.name} columns={[
      { label: 'القالب', cell: t => <strong>{t.name}</strong> }, { label: 'الفحوصات', cell: t => t.checks.map(c => <div className="cell-notes" key={c.label}>{c.label} · {c.criterion}</div>) },
      { label: 'الحالة', cell: t => <span className={`badge ${t.active ? 'active' : 'inactive'}`}>{t.active ? 'نشط' : 'مؤرشف'}</span> },
      { label: 'الإجراءات', cell: t => can(user, 'operations.templates.manage') && <IconButton label={`تعديل ${t.name}`} onClick={() => setEditing(t)}><Pencil size={17} /></IconButton> },
    ]} />}
    {editing && <TemplateForm original={editing === 'new' ? undefined : editing} close={() => setEditing(null)} saved={async () => { setEditing(null); await client.invalidateQueries({ queryKey: ['operations', 'templates'] }); }} />}
  </>;
}
function TemplateForm({ original, close, saved }: { original?: Template; close: () => void; saved: () => Promise<void> }) {
  const [name, setName] = useState(original?.name ?? ''), [active, setActive] = useState(original?.active ?? true), [checks, setChecks] = useState(original?.checks ?? [{ label: '', criterion: '' }]), [error, setError] = useState<unknown>(null), [pending, setPending] = useState(false);
  return <Modal title={original ? 'تعديل القالب' : 'قالب جديد'} close={() => { if (!pending) close(); }}><form className="form-stack" onSubmit={async e => { e.preventDefault(); setPending(true); setError(null); try { await api(`/operations/templates${original ? `/${original.id}` : ''}`, original ? 'PUT' : 'POST', { version: original?.version ?? 0, name, active, checks }); await saved(); } catch (e) { setError(e); } finally { setPending(false); } }}><ErrorBox error={error} /><fieldset className="operations-form" disabled={pending}>
    <label>اسم القالب<input required maxLength={120} value={name} onChange={e => setName(e.target.value)} /></label><label className="check-line"><input type="checkbox" checked={active} onChange={e => setActive(e.target.checked)} />قالب نشط</label>
    <div className="bom-summary"><h3>الفحوصات</h3><IconButton label="إضافة فحص" disabled={checks.length >= 30} onClick={() => setChecks([...checks, { label: '', criterion: '' }])}><Plus size={17} /></IconButton></div>
    {checks.map((c, i) => <div className="inspection-row" key={i}><label>بند الفحص {i + 1}<input required maxLength={200} value={c.label} onChange={e => setChecks(checks.map((v, n) => n === i ? { ...v, label: e.target.value } : v))} /></label><label>معيار النجاح {i + 1}<textarea required maxLength={500} rows={2} value={c.criterion} onChange={e => setChecks(checks.map((v, n) => n === i ? { ...v, criterion: e.target.value } : v))} /></label><IconButton label={`حذف فحص ${i + 1}`} disabled={checks.length === 1} onClick={() => setChecks(checks.filter((_, n) => n !== i))}><Trash2 size={17} /></IconButton></div>)}
  </fieldset><div className="form-actions"><button className="primary" disabled={pending}><Check size={17} />حفظ</button><button type="button" onClick={close} disabled={pending}>رجوع</button></div></form></Modal>;
}
