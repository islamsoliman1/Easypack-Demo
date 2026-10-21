import { formatDisplay } from './i18n';
import { SourceTextPreview } from './SourceTextPreview';
import { searchable } from './i18n';
import { translateUi, translateDisplay, useLanguage, dateFormatter, getLocale, getDirection } from './i18n';
import { useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { useQuery, localizedKey } from './localizedQuery';
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
  useLanguage();
  const { user } = useAuth(); const client = useQueryClient();
  const query = useQuery({ queryKey: [endpoint], queryFn: () => api<T[]>(endpoint), enabled: can(user, read) });
  const [search, setSearch] = useState(''), [status, setStatus] = useState('all'), [page, setPage] = useState(0);
  const [editing, setEditing] = useState<T | 'new' | null>(null), [notice, setNotice] = useState(false);
  if (!can(user, read)) return <Denied />;
  const manage = write.every(p => can(user, p)), all = query.data ?? [];
  const filtered = all.filter(r => searchable(searchText(r)).toLowerCase().includes(search.toLowerCase()) && (status === 'all' || r.active === (status === 'active')));
  const pages = Math.max(1, Math.ceil(filtered.length / 10)), current = Math.min(page, pages - 1);
  const saved = async () => { setEditing(null); setNotice(true); await client.invalidateQueries({ queryKey: [endpoint] }); };
  return <><div className="page-heading"><div><div className="eyebrow">{translateUi("بيانات المصنع")}</div><h1>{formatDisplay(title)} <span className="count">{formatDisplay(all.length)}</span></h1></div>
    {formatDisplay(manage && <button className="primary" onClick={() => { setNotice(false); setEditing('new'); }}><Plus size={18} />{formatDisplay(addLabel)}</button>)}</div>
    {formatDisplay(notice && <div className="notice" role="status"><Check size={17} />{translateUi("تم حفظ التغييرات")}</div>)}
    <div className="toolbar"><label className="search"><Search size={18} /><input aria-label={formatDisplay(translateUi("بحث {{v0}}", {v0: title}))} placeholder={translateUi("بحث بالاسم أو الكود")} value={search} onChange={e => { setSearch(e.target.value); setPage(0); }} /></label>
      <select aria-label={translateUi("حالة السجل")} value={status} onChange={e => { setStatus(e.target.value); setPage(0); }}><option value="all">{translateUi("كل الحالات")}</option><option value="active">{translateUi("نشط")}</option><option value="inactive">{translateUi("مؤرشف")}</option></select>
      <IconButton label={translateUi("تحديث القائمة")} onClick={() => void query.refetch()} disabled={query.isFetching}><RefreshCw size={18} /></IconButton></div>
    {formatDisplay(query.isPending ? <Busy /> : query.error ? <QueryError error={query.error} retry={() => void query.refetch()} /> : <>
      <div className="table-wrap"><table className="catalog-table"><thead><tr>{formatDisplay(columns.map(c => <th key={c.title}>{formatDisplay(c.title)}</th>))}<th>{translateUi("الحالة")}</th>{formatDisplay(manage && <th className="actions">{translateUi("الإجراءات")}</th>)}</tr></thead><tbody>
        {formatDisplay(filtered.slice(current * 10, current * 10 + 10).map(r => <tr key={r.id}>{formatDisplay(columns.map(c => <td key={c.title}>{formatDisplay(c.render(r))}</td>))}<td><span className={`badge ${translateDisplay(r.active ? 'active' : 'inactive')}`}>{formatDisplay(r.active ? 'نشط' : 'مؤرشف')}</span></td>
          {formatDisplay(manage && <td><IconButton label={formatDisplay(translateUi("تعديل {{v0}}", {v0: r.name}))} onClick={() => { setNotice(false); setEditing(r); }}><Pencil size={17} /></IconButton></td>)}</tr>))}
      </tbody></table>{formatDisplay(!filtered.length && <div className="state">{translateUi("لا توجد نتائج")}</div>)}</div>
      <div className="pagination"><span>{formatDisplay(filtered.length)} {translateUi("سجل")}</span><div><IconButton label={translateUi("الصفحة السابقة")} disabled={!current} onClick={() => setPage(current - 1)}><ChevronRight size={18} /></IconButton><span>{formatDisplay(current + 1)} / {formatDisplay(pages)}</span><IconButton label={translateUi("الصفحة التالية")} disabled={current === pages - 1} onClick={() => setPage(current + 1)}><ChevronLeft size={18} /></IconButton></div></div>
    </>)}
    {formatDisplay(editing && <Modal title={formatDisplay(editing === 'new' ? addLabel : translateUi("تعديل {{v0}}", {v0: editing.name}))} close={() => setEditing(null)}>{formatDisplay(form({ original: editing === 'new' ? undefined : editing, saved, cancel: () => setEditing(null) }))}</Modal>)}
  </>;
}
function FormActions({ busy, cancel }: { busy: boolean; cancel: () => void }) {
  useLanguage(); return <div className="form-actions"><button className="primary" disabled={busy}><Check size={17} />{translateUi("حفظ")}</button><button type="button" onClick={cancel}>{translateUi("إلغاء")}</button></div>; }
export function ModelsPage() {
  useLanguage();
  const { user } = useAuth();
  return <CatalogList<Model> endpoint="/machine-models" title={translateUi("موديلات الماكينات")} addLabel={translateUi("موديل جديد")} read="catalog.view" write={['catalog.view', 'catalog.manage']}
    searchText={m => `${translateDisplay(m.code)} ${translateDisplay(m.name)}`} columns={[
      { title: 'الموديل', render: m => <><strong>{formatDisplay(m.name)}</strong><span className="record-code" dir="ltr">{formatDisplay(m.code)}</span></> },
      { title: 'الوصف', render: m => <span className="cell-notes">{formatDisplay(m.description || '—')}</span> },
      { title: 'المكونات', render: m => can(user, 'boms.view') ? <Link className="inline-link" to={`/boms?model=${translateDisplay(m.id)}`}><ListTree size={17} />{translateUi("قائمة المكونات")}</Link> : '—' },
    ]} form={props => <ModelForm {...props} />} />;
}
function ModelForm({ original, saved, cancel }: FormProps<Model>) {
  useLanguage();
  const [error, setError] = useState<unknown>(null);
  const form = useForm<Model>({ defaultValues: original ?? { code: '', name: '', description: '', active: true, version: 0 } });
  return <form className="form-stack" onSubmit={form.handleSubmit(async v => {
    setError(null); try { await api(`/machine-models${translateDisplay(original ? `/${translateDisplay(original.id)}` : '')}`, original ? 'PUT' : 'POST', v); await saved(); } catch (e) { setError(e); }
  })}><SourceTextPreview values={form.watch()} /><ErrorBox error={error} /><label>{translateUi("كود الموديل")}<input {...form.register('code')} pattern={codePattern} dir="ltr" readOnly={!!original} required maxLength={64} /></label>
    <label>{translateUi("اسم الموديل")}<input {...form.register('name')} required maxLength={120} /></label><label>{translateUi("الوصف")}<textarea {...form.register('description')} rows={3} maxLength={1000} /></label>
    <label className="check-line"><input type="checkbox" {...form.register('active')} />{translateUi("موديل نشط")}</label><FormActions busy={form.formState.isSubmitting} cancel={cancel} /></form>;
}
export function PartsPage() {
  useLanguage();
  return <CatalogList<Part> endpoint="/parts" title={translateUi("كتالوج القطع")} addLabel={translateUi("صنف جديد")} read="catalog.view" write={['catalog.view', 'catalog.manage']}
    searchText={p => `${translateDisplay(p.code)} ${translateDisplay(p.name)}`} columns={[
      { title: 'الصنف', render: p => <><strong>{formatDisplay(p.name)}</strong><span className="record-code" dir="ltr">{formatDisplay(p.code)}</span></> },
      { title: 'النوع', render: p => <><span>{formatDisplay(p.kind === 'SERIALIZED_PIECE' ? 'قطعة جاهزة مسلسلة' : 'خامة مقاسة')}</span><div className="muted">{formatDisplay(p.stockUnit === 'PIECE' ? 'بالعدد' : p.stockUnit === 'LENGTH' ? 'بالطول' : 'بالمساحة')}</div></> },
      { title: 'المقاسات', render: p => <span className="dimensions" dir={p.shape === 'STANDARD' ? getDirection() : 'ltr'}>{formatDisplay(dimensions(p))}</span> },
      { title: 'التركيب / قطعة', render: p => p.kind === 'SERIALIZED_PIECE' ? translateUi("{{v0}} دقيقة", {v0: p.defaultInstallMinutes}) : '—' },
      { title: 'المرفقات', render: p => <FileButton kind="PART" id={p.id} name={p.name} /> },
    ]} form={props => <PartForm {...props} />} />;
}
function PartForm({ original, saved, cancel }: FormProps<Part>) {
  useLanguage();
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
      } else await api(`/parts${translateDisplay(original ? `/${translateDisplay(original.id)}` : '')}`, original ? 'PUT' : 'POST', payload);
      await saved();
    } catch (e) { setError(e); }
  })}><SourceTextPreview values={form.watch()} /><ErrorBox error={error} /><div className="form-grid"><label>{translateUi("كود الصنف")}<input {...form.register('code')} pattern={codePattern} dir="ltr" required maxLength={64} readOnly={!!original} /></label>
    <label>{translateUi("اسم الصنف")}<input {...form.register('name')} required maxLength={120} /></label></div>
    <label>{translateUi("نوع الصنف")}<select aria-label={translateUi("نوع الصنف")} {...form.register('kind')} onChange={e => {
      const kind = e.target.value as Part['kind']; form.setValue('kind', kind);
      form.setValue('stockUnit', kind === 'MEASURED_STOCK' ? 'LENGTH' : 'PIECE');
      if (kind === 'MEASURED_STOCK' && values.shape === 'STANDARD') form.setValue('shape', 'RECTANGULAR');
      form.setValue('defaultInstallMinutes', kind === 'MEASURED_STOCK' ? 0 : values.defaultInstallMinutes || 15);
    }}><option value="SERIALIZED_PIECE">{translateUi("قطعة جاهزة مسلسلة")}</option><option value="MEASURED_STOCK">{translateUi("خامة مقاسة")}</option></select></label>
    <div className="form-grid"><label>{translateUi("الشكل")}<select aria-label={translateUi("الشكل")} {...form.register('shape')} onChange={e => { const shape = e.target.value as Part['shape']; form.setValue('shape', shape); if (shape === 'ROUND' && values.stockUnit === 'AREA') form.setValue('stockUnit', 'LENGTH'); }}>
      {formatDisplay(!raw && <option value="STANDARD">{translateUi("قياسية")}</option>)}<option value="RECTANGULAR">{translateUi("مستطيلة")}</option><option value="ROUND">{translateUi("دائرية")}</option></select></label>
      {formatDisplay(raw && <label>{translateUi("وحدة المخزون")}<select aria-label={translateUi("وحدة المخزون")} {...form.register('stockUnit')}><option value="LENGTH">{translateUi("بالطول")}</option>{formatDisplay(values.shape === 'RECTANGULAR' && <option value="AREA">{translateUi("بالمساحة")}</option>)}</select></label>)}</div>
    {formatDisplay(values.shape !== 'STANDARD' && <div className="form-grid"><label>{translateUi("الطول (مم)")}<input type="number" {...form.register('lengthMm', { valueAsNumber: true })} min="0.001" max="999999.999" step="0.001" required dir="ltr" /></label>
      {formatDisplay(values.shape === 'RECTANGULAR' ? <label>{translateUi("العرض (مم)")}<input type="number" {...form.register('widthMm', { valueAsNumber: true })} min="0.001" max="999999.999" step="0.001" required dir="ltr" /></label>
        : <label>{translateUi("القطر (مم)")}<input type="number" {...form.register('diameterMm', { valueAsNumber: true })} min="0.001" max="999999.999" step="0.001" required dir="ltr" /></label>)}</div>)}
    {formatDisplay(!raw && <label>{translateUi("زمن التركيب الافتراضي (دقيقة / قطعة)")}<input type="number" {...form.register('defaultInstallMinutes', { valueAsNumber: true })} min="0.01" max="9999999.99" step="0.01" required dir="ltr" /></label>)}
    {formatDisplay(linkAllowed && !raw && <><ErrorBox error={models.error} /><label>{translateUi("ربط بمسودة موديل (اختياري)")}<select aria-label={translateUi("ربط بمسودة موديل")} value={modelId} disabled={models.isPending || models.isError} onChange={e => setModelId(e.target.value)}>
      <option value="">{translateUi("بدون ربط")}</option>{formatDisplay(models.data?.filter(m => m.active).map(m => <option key={m.id} value={m.id}>{formatDisplay(m.code)} · {formatDisplay(m.name)}</option>))}</select></label>
      {formatDisplay(modelId && <div className="form-grid"><label>{translateUi("كود الموضع")}<input value={positionCode} onChange={e => setPositionCode(e.target.value)} pattern={codePattern} maxLength={40} required dir="ltr" /></label>
        <label>{translateUi("الكمية")}<input type="number" value={quantity} onChange={e => setQuantity(e.target.valueAsNumber)} min="1" max="10000" step="1" required dir="ltr" /></label></div>)}</>)}
    <label>{translateUi("الوصف")}<textarea {...form.register('description')} rows={2} maxLength={1000} /></label>
    <label className="check-line"><input type="checkbox" {...form.register('active')} />{translateUi("صنف نشط")}</label><FormActions busy={form.formState.isSubmitting} cancel={cancel} /></form>;
}
export function MachinesPage() {
  useLanguage();
  const { user } = useAuth();
  return <CatalogList<Machine> endpoint="/machines" title={translateUi("الماكينات")} addLabel={translateUi("ماكينة جديدة")} read="machines.view" write={['machines.view', 'machines.manage', 'catalog.view']}
    searchText={m => `${translateDisplay(m.serialNumber)} ${translateDisplay(m.name)} ${translateDisplay(m.modelName)} ${translateDisplay(m.location)}`} columns={[
      { title: 'الماكينة', render: m => <><Link className="inline-link" to={`/machines/${translateDisplay(m.id)}`}><strong>{formatDisplay(m.name)}</strong></Link><span className="record-code" dir="ltr">{formatDisplay(m.serialNumber)}</span></> },
      { title: 'الموديل', render: m => <><span>{formatDisplay(m.modelName)}</span><div className="record-code" dir="ltr">{formatDisplay(m.modelCode)}</div></> },
      { title: 'الموقع', render: m => m.location || '—' },
      { title: 'المرفقات', render: m => <FileButton kind="MACHINE" id={m.id} name={m.name} /> },
      { title: 'قطع الماكينة', render: m => can(user, 'catalog.view') && can(user, 'boms.view') ? <Link className="inline-link" to={`/boms?machine=${translateDisplay(m.id)}`}><ListTree size={17} />{translateUi("قطع الماكينة")}</Link> : '—' },
      { title: 'التركيب الفعلي', render: m => can(user, 'tasks.view') && can(user, 'orders.view') ? <Link className="inline-link" to={`/machines/${translateDisplay(m.id)}/assembly`}><Wrench size={17} />{translateUi("التركيب وسجل الفك")}</Link> : '—' },
    ]} form={props => <MachineForm {...props} />} />;
}
export function MachineForm({ original, saved, cancel }: FormProps<Machine>) {
  useLanguage();
  const [error, setError] = useState<unknown>(null);
  const models = useQuery({ queryKey: ['/machine-models'], queryFn: () => api<Model[]>('/machine-models') });
  const form = useForm<Machine>({ defaultValues: original ?? { serialNumber: '', modelId: '', name: '', location: '', notes: '', active: true, version: 0 } });
  return <form className="form-stack" onSubmit={form.handleSubmit(async v => {
    setError(null); try { await api(`/machines${translateDisplay(original ? `/${translateDisplay(original.id)}` : '')}`, original ? 'PUT' : 'POST', { ...v, modelId: original?.modelId ?? v.modelId }); await saved(); } catch (e) { setError(e); }
  })}><SourceTextPreview values={form.watch()} /><ErrorBox error={error} /><ErrorBox error={models.error} /><label>{translateUi("رقم الماكينة")}<input {...form.register('serialNumber')} pattern={codePattern} readOnly={!!original} required dir="ltr" maxLength={64} /></label>
    <label>{translateUi("اسم الماكينة")}<input {...form.register('name')} maxLength={120} required /></label>
    <label>{translateUi("الموديل")}<select aria-label={translateUi("الموديل")} {...form.register('modelId')} value={form.watch('modelId')} disabled={!!original || models.isPending} required><option value="">{translateUi("اختر الموديل")}</option>{formatDisplay(models.data?.filter(m => m.active || m.id === original?.modelId).map(m => <option key={m.id} value={m.id}>{formatDisplay(m.code)} · {formatDisplay(m.name)}</option>))}</select></label>
    <label>{translateUi("الموقع")}<input {...form.register('location')} maxLength={120} /></label><label>{translateUi("ملاحظات")}<textarea {...form.register('notes')} rows={2} maxLength={1000} /></label>
    <label className="check-line"><input type="checkbox" {...form.register('active')} />{translateUi("ماكينة نشطة")}</label><FormActions busy={form.formState.isSubmitting || models.isPending || !!models.error} cancel={cancel} /></form>;
}
