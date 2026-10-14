import { useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { Check, ChevronLeft, ChevronRight, ListTree, Pencil, Plus, RefreshCw, Search, Wrench } from 'lucide-react';
import { api } from './api';
import { can, useAuth } from './access';
import { Busy, Denied, ErrorBox, IconButton, Modal, QueryError } from './ui';
import { codePattern, dimensions, type Model, type Part, type Machine } from './catalog';
import { FileButton } from './Files';

type FormProps<T> = { original?: T; saved: () => Promise<void>; cancel: () => void };
function CatalogList<T extends { id: string; name: string; active: boolean }>({ endpoint, title, addLabel, read, write, columns, searchText, form }: {
  endpoint: string; title: string; addLabel: string; read: string; write: string[];
  columns: { title: string; render: (row: T) => ReactNode }[]; searchText: (row: T) => string;
  form: (props: FormProps<T>) => ReactNode;
}) {
  const { user } = useAuth(); const client = useQueryClient();
  const query = useQuery({ queryKey: [endpoint], queryFn: () => api<T[]>(endpoint), enabled: can(user, read) });
  const [search, setSearch] = useState(''), [status, setStatus] = useState('all'), [page, setPage] = useState(0);
  const [editing, setEditing] = useState<T | 'new' | null>(null), [notice, setNotice] = useState(false);
  if (!can(user, read)) return <Denied />;
  const manage = write.every(p => can(user, p)), all = query.data ?? [];
  const filtered = all.filter(r => searchText(r).toLowerCase().includes(search.toLowerCase()) && (status === 'all' || r.active === (status === 'active')));
  const pages = Math.max(1, Math.ceil(filtered.length / 10)), current = Math.min(page, pages - 1);
  const saved = async () => { setEditing(null); setNotice(true); await client.invalidateQueries({ queryKey: [endpoint] }); };
  return <><div className="page-heading"><div><div className="eyebrow">بيانات المصنع</div><h1>{title} <span className="count">{all.length}</span></h1></div>
    {manage && <button className="primary" onClick={() => { setNotice(false); setEditing('new'); }}><Plus size={18} />{addLabel}</button>}</div>
    {notice && <div className="notice" role="status"><Check size={17} />تم حفظ التغييرات</div>}
    <div className="toolbar"><label className="search"><Search size={18} /><input aria-label={`بحث ${title}`} placeholder="بحث بالاسم أو الكود" value={search} onChange={e => { setSearch(e.target.value); setPage(0); }} /></label>
      <select aria-label="حالة السجل" value={status} onChange={e => { setStatus(e.target.value); setPage(0); }}><option value="all">كل الحالات</option><option value="active">نشط</option><option value="inactive">مؤرشف</option></select>
      <IconButton label="تحديث القائمة" onClick={() => void query.refetch()} disabled={query.isFetching}><RefreshCw size={18} /></IconButton></div>
    {query.isPending ? <Busy /> : query.error ? <QueryError error={query.error} retry={() => void query.refetch()} /> : <>
      <div className="table-wrap"><table className="catalog-table"><thead><tr>{columns.map(c => <th key={c.title}>{c.title}</th>)}<th>الحالة</th>{manage && <th className="actions">الإجراءات</th>}</tr></thead><tbody>
        {filtered.slice(current * 10, current * 10 + 10).map(r => <tr key={r.id}>{columns.map(c => <td key={c.title}>{c.render(r)}</td>)}<td><span className={`badge ${r.active ? 'active' : 'inactive'}`}>{r.active ? 'نشط' : 'مؤرشف'}</span></td>
          {manage && <td><IconButton label={`تعديل ${r.name}`} onClick={() => { setNotice(false); setEditing(r); }}><Pencil size={17} /></IconButton></td>}</tr>)}
      </tbody></table>{!filtered.length && <div className="state">لا توجد نتائج</div>}</div>
      <div className="pagination"><span>{filtered.length} سجل</span><div><IconButton label="الصفحة السابقة" disabled={!current} onClick={() => setPage(current - 1)}><ChevronRight size={18} /></IconButton><span>{current + 1} / {pages}</span><IconButton label="الصفحة التالية" disabled={current === pages - 1} onClick={() => setPage(current + 1)}><ChevronLeft size={18} /></IconButton></div></div>
    </>}
    {editing && <Modal title={editing === 'new' ? addLabel : `تعديل ${editing.name}`} close={() => setEditing(null)}>{form({ original: editing === 'new' ? undefined : editing, saved, cancel: () => setEditing(null) })}</Modal>}
  </>;
}
function FormActions({ busy, cancel }: { busy: boolean; cancel: () => void }) { return <div className="form-actions"><button className="primary" disabled={busy}><Check size={17} />حفظ</button><button type="button" onClick={cancel}>إلغاء</button></div>; }
export function ModelsPage() {
  const { user } = useAuth();
  return <CatalogList<Model> endpoint="/machine-models" title="موديلات الماكينات" addLabel="موديل جديد" read="catalog.view" write={['catalog.view', 'catalog.manage']}
    searchText={m => `${m.code} ${m.name}`} columns={[
      { title: 'الموديل', render: m => <><strong>{m.name}</strong><span className="record-code" dir="ltr">{m.code}</span></> },
      { title: 'الوصف', render: m => <span className="cell-notes">{m.description || '—'}</span> },
      { title: 'المكونات', render: m => can(user, 'boms.view') ? <Link className="inline-link" to={`/boms?model=${m.id}`}><ListTree size={17} />قائمة المكونات</Link> : '—' },
    ]} form={props => <ModelForm {...props} />} />;
}
function ModelForm({ original, saved, cancel }: FormProps<Model>) {
  const [error, setError] = useState<unknown>(null);
  const form = useForm<Model>({ defaultValues: original ?? { code: '', name: '', description: '', active: true, version: 0 } });
  return <form className="form-stack" onSubmit={form.handleSubmit(async v => {
    setError(null); try { await api(`/machine-models${original ? `/${original.id}` : ''}`, original ? 'PUT' : 'POST', v); await saved(); } catch (e) { setError(e); }
  })}><ErrorBox error={error} /><label>كود الموديل<input {...form.register('code')} pattern={codePattern} dir="ltr" readOnly={!!original} required maxLength={64} /></label>
    <label>اسم الموديل<input {...form.register('name')} required maxLength={120} /></label><label>الوصف<textarea {...form.register('description')} rows={3} maxLength={1000} /></label>
    <label className="check-line"><input type="checkbox" {...form.register('active')} />موديل نشط</label><FormActions busy={form.formState.isSubmitting} cancel={cancel} /></form>;
}
export function PartsPage() {
  return <CatalogList<Part> endpoint="/parts" title="كتالوج القطع" addLabel="صنف جديد" read="catalog.view" write={['catalog.view', 'catalog.manage']}
    searchText={p => `${p.code} ${p.name}`} columns={[
      { title: 'الصنف', render: p => <><strong>{p.name}</strong><span className="record-code" dir="ltr">{p.code}</span></> },
      { title: 'النوع', render: p => <><span>{p.kind === 'SERIALIZED_PIECE' ? 'قطعة جاهزة مسلسلة' : 'خامة مقاسة'}</span><div className="muted">{p.stockUnit === 'PIECE' ? 'بالعدد' : p.stockUnit === 'LENGTH' ? 'بالطول' : 'بالمساحة'}</div></> },
      { title: 'المقاسات', render: p => <span className="dimensions" dir={p.shape === 'STANDARD' ? 'rtl' : 'ltr'}>{dimensions(p)}</span> },
      { title: 'التركيب / قطعة', render: p => p.kind === 'SERIALIZED_PIECE' ? `${p.defaultInstallMinutes} دقيقة` : '—' },
      { title: 'المرفقات', render: p => <FileButton kind="PART" id={p.id} name={p.name} /> },
    ]} form={props => <PartForm {...props} />} />;
}
function PartForm({ original, saved, cancel }: FormProps<Part>) {
  const [error, setError] = useState<unknown>(null);
  const { user } = useAuth(); const client = useQueryClient();
  const linkAllowed = !original && can(user, 'boms.view') && can(user, 'boms.manage');
  const models = useQuery({ queryKey: ['/machine-models'], queryFn: () => api<Model[]>('/machine-models'), enabled: linkAllowed });
  const [modelId, setModelId] = useState(''), [positionCode, setPositionCode] = useState(''), [quantity, setQuantity] = useState(1);
  const form = useForm<Part>({ defaultValues: original ?? { code: '', name: '', description: '', active: true, version: 0, kind: 'SERIALIZED_PIECE', shape: 'STANDARD', stockUnit: 'PIECE', lengthMm: null, widthMm: null, diameterMm: null, defaultInstallMinutes: 15 } });
  const values = form.watch(), raw = values.kind === 'MEASURED_STOCK';
  return <form className="form-stack" onSubmit={form.handleSubmit(async v => {
    const payload = { ...v, stockUnit: raw ? v.stockUnit : 'PIECE', defaultInstallMinutes: raw ? 0 : v.defaultInstallMinutes,
      lengthMm: v.shape === 'STANDARD' ? null : v.lengthMm, widthMm: v.shape === 'RECTANGULAR' ? v.widthMm : null, diameterMm: v.shape === 'ROUND' ? v.diameterMm : null };
    setError(null); try {
      if (linkAllowed && !raw && modelId) {
        await api('/parts/with-model', 'POST', { part: payload, modelId, positionCode, quantity });
        await client.invalidateQueries({ queryKey: ['boms', modelId] });
      } else await api(`/parts${original ? `/${original.id}` : ''}`, original ? 'PUT' : 'POST', payload);
      await saved();
    } catch (e) { setError(e); }
  })}><ErrorBox error={error} /><div className="form-grid"><label>كود الصنف<input {...form.register('code')} pattern={codePattern} dir="ltr" required maxLength={64} readOnly={!!original} /></label>
    <label>اسم الصنف<input {...form.register('name')} required maxLength={120} /></label></div>
    <label>نوع الصنف<select aria-label="نوع الصنف" {...form.register('kind')} onChange={e => {
      const kind = e.target.value as Part['kind']; form.setValue('kind', kind);
      form.setValue('stockUnit', kind === 'MEASURED_STOCK' ? 'LENGTH' : 'PIECE');
      if (kind === 'MEASURED_STOCK' && values.shape === 'STANDARD') form.setValue('shape', 'RECTANGULAR');
      form.setValue('defaultInstallMinutes', kind === 'MEASURED_STOCK' ? 0 : values.defaultInstallMinutes || 15);
    }}><option value="SERIALIZED_PIECE">قطعة جاهزة مسلسلة</option><option value="MEASURED_STOCK">خامة مقاسة</option></select></label>
    <div className="form-grid"><label>الشكل<select aria-label="الشكل" {...form.register('shape')} onChange={e => { const shape = e.target.value as Part['shape']; form.setValue('shape', shape); if (shape === 'ROUND' && values.stockUnit === 'AREA') form.setValue('stockUnit', 'LENGTH'); }}>
      {!raw && <option value="STANDARD">قياسية</option>}<option value="RECTANGULAR">مستطيلة</option><option value="ROUND">دائرية</option></select></label>
      {raw && <label>وحدة المخزون<select aria-label="وحدة المخزون" {...form.register('stockUnit')}><option value="LENGTH">بالطول</option>{values.shape === 'RECTANGULAR' && <option value="AREA">بالمساحة</option>}</select></label>}</div>
    {values.shape !== 'STANDARD' && <div className="form-grid"><label>الطول (مم)<input type="number" {...form.register('lengthMm', { valueAsNumber: true })} min="0.001" max="999999.999" step="0.001" required dir="ltr" /></label>
      {values.shape === 'RECTANGULAR' ? <label>العرض (مم)<input type="number" {...form.register('widthMm', { valueAsNumber: true })} min="0.001" max="999999.999" step="0.001" required dir="ltr" /></label>
        : <label>القطر (مم)<input type="number" {...form.register('diameterMm', { valueAsNumber: true })} min="0.001" max="999999.999" step="0.001" required dir="ltr" /></label>}</div>}
    {!raw && <label>زمن التركيب الافتراضي (دقيقة / قطعة)<input type="number" {...form.register('defaultInstallMinutes', { valueAsNumber: true })} min="0.01" max="9999999.99" step="0.01" required dir="ltr" /></label>}
    {linkAllowed && !raw && <><ErrorBox error={models.error} /><label>ربط بمسودة موديل (اختياري)<select aria-label="ربط بمسودة موديل" value={modelId} disabled={models.isPending || models.isError} onChange={e => setModelId(e.target.value)}>
      <option value="">بدون ربط</option>{models.data?.filter(m => m.active).map(m => <option key={m.id} value={m.id}>{m.code} · {m.name}</option>)}</select></label>
      {modelId && <div className="form-grid"><label>كود الموضع<input value={positionCode} onChange={e => setPositionCode(e.target.value)} pattern={codePattern} maxLength={40} required dir="ltr" /></label>
        <label>الكمية<input type="number" value={quantity} onChange={e => setQuantity(e.target.valueAsNumber)} min="1" max="10000" step="1" required dir="ltr" /></label></div>}</>}
    <label>الوصف<textarea {...form.register('description')} rows={2} maxLength={1000} /></label>
    <label className="check-line"><input type="checkbox" {...form.register('active')} />صنف نشط</label><FormActions busy={form.formState.isSubmitting} cancel={cancel} /></form>;
}
export function MachinesPage() {
  const { user } = useAuth();
  return <CatalogList<Machine> endpoint="/machines" title="الماكينات" addLabel="ماكينة جديدة" read="machines.view" write={['machines.view', 'machines.manage', 'catalog.view']}
    searchText={m => `${m.serialNumber} ${m.name} ${m.modelName} ${m.location}`} columns={[
      { title: 'الماكينة', render: m => <><strong>{m.name}</strong><span className="record-code" dir="ltr">{m.serialNumber}</span></> },
      { title: 'الموديل', render: m => <><span>{m.modelName}</span><div className="record-code" dir="ltr">{m.modelCode}</div></> },
      { title: 'الموقع', render: m => m.location || '—' },
      { title: 'المرفقات', render: m => <FileButton kind="MACHINE" id={m.id} name={m.name} /> },
      { title: 'قطع الماكينة', render: m => can(user, 'catalog.view') && can(user, 'boms.view') ? <Link className="inline-link" to={`/boms?machine=${m.id}`}><ListTree size={17} />قطع الماكينة</Link> : '—' },
      { title: 'التركيب الفعلي', render: m => can(user, 'tasks.view') && can(user, 'orders.view') ? <Link className="inline-link" to={`/machines/${m.id}/assembly`}><Wrench size={17} />التركيب وسجل الفك</Link> : '—' },
    ]} form={props => <MachineForm {...props} />} />;
}
function MachineForm({ original, saved, cancel }: FormProps<Machine>) {
  const [error, setError] = useState<unknown>(null);
  const models = useQuery({ queryKey: ['/machine-models'], queryFn: () => api<Model[]>('/machine-models') });
  const form = useForm<Machine>({ defaultValues: original ?? { serialNumber: '', modelId: '', name: '', location: '', notes: '', active: true, version: 0 } });
  return <form className="form-stack" onSubmit={form.handleSubmit(async v => {
    setError(null); try { await api(`/machines${original ? `/${original.id}` : ''}`, original ? 'PUT' : 'POST', { ...v, modelId: original?.modelId ?? v.modelId }); await saved(); } catch (e) { setError(e); }
  })}><ErrorBox error={error} /><ErrorBox error={models.error} /><label>رقم الماكينة<input {...form.register('serialNumber')} pattern={codePattern} readOnly={!!original} required dir="ltr" maxLength={64} /></label>
    <label>اسم الماكينة<input {...form.register('name')} maxLength={120} required /></label>
    <label>الموديل<select aria-label="الموديل" {...form.register('modelId')} disabled={!!original || models.isPending} required><option value="">اختر الموديل</option>{models.data?.filter(m => m.active || m.id === original?.modelId).map(m => <option key={m.id} value={m.id}>{m.code} · {m.name}</option>)}</select></label>
    <label>الموقع<input {...form.register('location')} maxLength={120} /></label><label>ملاحظات<textarea {...form.register('notes')} rows={2} maxLength={1000} /></label>
    <label className="check-line"><input type="checkbox" {...form.register('active')} />ماكينة نشطة</label><FormActions busy={form.formState.isSubmitting || models.isPending || !!models.error} cancel={cancel} /></form>;
}
