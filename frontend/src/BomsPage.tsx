import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { Check, CopyPlus, Pencil, Plus, RefreshCw, Trash2, Upload } from 'lucide-react';
import { api } from './api';
import { can, useAuth } from './access';
import { Busy, Denied, ErrorBox, IconButton, Modal, QueryError } from './ui';
import { codePattern, dimensions, type Model, type Machine, type Part, type Bom, type BomItem } from './catalog';

export function BomsPage() {
  const [params] = useSearchParams();
  return <BomWorkspace key={params.toString()} />;
}
function BomWorkspace() {
  const { user } = useAuth(); const client = useQueryClient(); const [params, setParams] = useSearchParams();
  const machineId = params.get('machine') ?? '';
  const allowed = can(user, 'catalog.view') && can(user, 'boms.view') && (!machineId || can(user, 'machines.view'));
  const manage = allowed && can(user, 'boms.manage') && (!machineId || can(user, 'machines.manage'));
  const models = useQuery({ queryKey: ['/machine-models'], queryFn: () => api<Model[]>('/machine-models'), enabled: allowed });
  const machines = useQuery({ queryKey: ['/machines'], queryFn: () => api<Machine[]>('/machines'), enabled: allowed && !!machineId });
  const parts = useQuery({ queryKey: ['/parts'], queryFn: () => api<Part[]>('/parts'), enabled: allowed });
  const modelId = params.get('model') ?? '', selectedModel = models.data?.find(m => m.id === modelId);
  const selectedMachine = machines.data?.find(m => m.id === machineId);
  const owner = machineId ? selectedMachine : selectedModel;
  const ownerQuery = machineId ? machines : models;
  const listPath = machineId ? `/machines/${machineId}/boms` : `/machine-models/${modelId}/boms`;
  const queryKey = machineId ? ['machine-boms', machineId] : ['boms', modelId];
  const boms = useQuery({ queryKey, queryFn: () => api<Bom[]>(listPath), enabled: allowed && !!owner });
  const templates = useQuery({ queryKey: ['boms', selectedMachine?.modelId], queryFn: () => api<Bom[]>(`/machine-models/${selectedMachine!.modelId}/boms`), enabled: allowed && !!selectedMachine });
  const [sourceId, setSourceId] = useState('');
  const [selectedId, setSelectedId] = useState(''), [editing, setEditing] = useState<BomItem | 'new' | null>(null);
  const [confirm, setConfirm] = useState<'publish' | BomItem | null>(null), [error, setError] = useState<unknown>(null);
  const [confirmationVersion, setConfirmationVersion] = useState(0);
  const [pending, setPending] = useState(false), [notice, setNotice] = useState('');
  const bom = boms.data?.find(b => b.id === selectedId) ?? boms.data?.[0];
  const writable = manage && bom?.status === 'DRAFT' && !!owner?.active;
  if (!allowed) return <Denied />;
  const saved = async (result: Bom) => {
    client.setQueryData<Bom[]>(queryKey, current => [result, ...(current ?? []).filter(b => b.id !== result.id)].sort((a,b) => b.revision - a.revision));
    setSelectedId(result.id); setEditing(null); setConfirm(null); setNotice('تم حفظ التغييرات');
    await client.invalidateQueries({ queryKey });
  };
  const action = async (path: string, method: string, data: unknown) => {
    setPending(true); setError(null); setNotice('');
    try { await saved(await api<Bom>(path, method, data)); } catch (e) { setError(e); } finally { setPending(false); }
  };
  return <><div className="page-heading"><div><div className="eyebrow">{machineId ? 'قائمة مستقلة للماكينة' : 'بيانات المصنع'}</div><h1>{machineId ? 'قطع الماكينة' : 'قوائم المكونات'}</h1></div>
    {manage && <button className="primary" disabled={pending || !owner?.active || boms.isPending || boms.isError || boms.data?.some(b => b.status === 'DRAFT')}
      onClick={() => void action(listPath, 'POST', { sourceBomId: bom?.status === 'PUBLISHED' ? bom.id : sourceId || null })}><CopyPlus size={18} />مسودة جديدة</button>}</div>
    {notice && <div className="notice" role="status"><Check size={17} />{notice}</div>}<ErrorBox error={error} />
    <div className="bom-selectors">{machineId ? <label>الماكينة<select aria-label="الماكينة" value={machineId} disabled={pending} onChange={e => setParams({ machine: e.target.value })}>
      {machines.data?.map(m => <option key={m.id} value={m.id}>{m.serialNumber} · {m.name}{m.active ? '' : ' (مؤرشف)'}</option>)}</select></label> : <label>الموديل<select aria-label="الموديل" value={modelId} disabled={pending} onChange={e => setParams(e.target.value ? { model: e.target.value } : {})}>
      <option value="">اختر الموديل</option>{models.data?.map(m => <option key={m.id} value={m.id}>{m.code} · {m.name}{m.active ? '' : ' (مؤرشف)'}</option>)}</select></label>}
      <label>الإصدار<select aria-label="الإصدار" value={bom?.id ?? ''} disabled={pending || !boms.data?.length} onChange={e => { setSelectedId(e.target.value); setError(null); setNotice(''); }}>
        {!boms.data?.length && <option value="">لا توجد إصدارات</option>}{boms.data?.map(b => <option key={b.id} value={b.id}>الإصدار {b.revision} · {b.status === 'DRAFT' ? 'مسودة' : 'منشور'}</option>)}</select></label>
      <IconButton label="تحديث قوائم المكونات" disabled={!owner || boms.isFetching} onClick={() => void boms.refetch()}><RefreshCw size={18} /></IconButton></div>
    {machineId && manage && !boms.isPending && !boms.isError && !boms.data?.length && <div className="toolbar"><label>مصدر القائمة<select aria-label="مصدر القائمة" value={sourceId} disabled={pending} onChange={e => setSourceId(e.target.value)}>
      <option value="">قائمة فارغة</option>{templates.data?.filter(b => b.status === 'PUBLISHED').map(b => <option key={b.id} value={b.id}>نسخة من الموديل · الإصدار {b.revision}</option>)}</select></label>
      {templates.error && <QueryError error={templates.error} retry={() => void templates.refetch()} />}</div>}
    {ownerQuery.isPending ? <Busy /> : ownerQuery.error ? <QueryError error={ownerQuery.error} retry={() => void ownerQuery.refetch()} /> : !owner ? <div className="state">{machineId ? 'الماكينة غير موجودة' : 'لا يوجد موديل محدد'}</div>
      : boms.isPending ? <Busy /> : boms.error ? <QueryError error={boms.error} retry={() => void boms.refetch()} /> : !bom ? <div className="state">{machineId ? 'لا توجد قائمة قطع لهذه الماكينة' : 'لا توجد قائمة مكونات لهذا الموديل'}</div> : <>
        <div className="bom-summary"><div><h2>{owner.name} <span className={`badge ${bom.status === 'PUBLISHED' ? 'active' : 'draft'}`}>{bom.status === 'PUBLISHED' ? 'منشور' : 'مسودة'}</span></h2>
          {selectedMachine && <div className="muted">{selectedMachine.serialNumber} · {selectedMachine.modelName}</div>}
          <div className="muted">الإصدار {bom.revision} · {bom.items.length} موضع · {bom.items.reduce((sum,i) => sum + i.quantity, 0)} قطعة · {Math.round(bom.items.reduce((sum,i) => sum + i.quantity * i.expectedInstallMinutes, 0) * 100) / 100} دقيقة</div>
          {bom.publishedAt && <time className="muted" dateTime={bom.publishedAt}>{new Date(bom.publishedAt).toLocaleString('ar-EG')}</time>}</div>
          {writable && <div className="bom-actions"><button onClick={() => { setError(null); setEditing('new'); }} disabled={pending || parts.isPending || parts.isError}><Plus size={17} />إضافة مكون</button>
            <button className="primary" disabled={pending || !bom.items.length} onClick={() => { setError(null); setConfirmationVersion(bom.version); setConfirm('publish'); }}><Upload size={17} />نشر الإصدار</button></div>}</div>
        {parts.error && <QueryError error={parts.error} retry={() => void parts.refetch()} />}
        <div className="table-wrap"><table className="bom-table"><thead><tr><th>الموضع</th><th>القطعة</th><th>المقاسات</th><th>الكمية</th><th>دقيقة / قطعة</th><th>ملاحظات</th>{writable && <th>الإجراءات</th>}</tr></thead><tbody>
          {bom.items.map(i => <tr key={i.id}><td><strong dir="ltr">{i.positionCode}</strong></td><td><strong>{i.partName}</strong><span className="record-code" dir="ltr">{i.partCode}</span></td>
            <td><span className="dimensions" dir={i.shape === 'STANDARD' ? 'rtl' : 'ltr'}>{dimensions(i)}</span></td><td>{i.quantity}</td><td>{i.expectedInstallMinutes}</td><td><span className="cell-notes">{i.notes || '—'}</span></td>
            {writable && <td><div className="row-actions"><IconButton label={`تعديل الموضع ${i.positionCode}`} disabled={pending} onClick={() => setEditing(i)}><Pencil size={17} /></IconButton>
              <IconButton label={`حذف الموضع ${i.positionCode}`} disabled={pending} onClick={() => { setError(null); setConfirmationVersion(bom.version); setConfirm(i); }}><Trash2 size={17} /></IconButton></div></td>}</tr>)}
        </tbody></table>{!bom.items.length && <div className="state">لا توجد مكونات</div>}</div>
      </>}
    {editing && bom && <Modal title={editing === 'new' ? 'إضافة مكون' : 'تعديل المكون'} close={() => setEditing(null)}><BomItemForm bom={bom} original={editing === 'new' ? undefined : editing} parts={parts.data ?? []} saved={saved} cancel={() => setEditing(null)} /></Modal>}
    {confirm && bom && <Modal title={confirm === 'publish' ? 'نشر الإصدار' : 'حذف المكون'} close={() => { if (!pending) setConfirm(null); }}><div className="modal-body"><ErrorBox error={error} />
      <p>{confirm === 'publish' ? `سيتم تثبيت الإصدار ${bom.revision} بمكوناته ومقاساته. التعديلات اللاحقة تكون في إصدار جديد.` : `حذف الموضع ${confirm.positionCode} من المسودة؟`}</p>
      <div className="form-actions"><button className="primary" disabled={pending} onClick={() => void action(confirm === 'publish' ? `/boms/${bom.id}/publish` : `/boms/${bom.id}/items/${confirm.id}`, confirm === 'publish' ? 'POST' : 'DELETE', { version: confirmationVersion })}>
        {confirm === 'publish' ? <Upload size={17} /> : <Trash2 size={17} />}{confirm === 'publish' ? 'تأكيد النشر' : 'تأكيد الحذف'}</button><button disabled={pending} onClick={() => setConfirm(null)}>إلغاء</button></div></div></Modal>}
  </>;
}
function BomItemForm({ bom, original, parts, saved, cancel }: { bom: Bom; original?: BomItem; parts: Part[]; saved: (result: Bom) => Promise<void>; cancel: () => void }) {
  const [error, setError] = useState<unknown>(null), [unit, setUnit] = useState('minutes');
  const [version] = useState(bom.version);
  const form = useForm({ defaultValues: { positionCode: original?.positionCode ?? '', partId: original?.partId ?? '', quantity: original?.quantity ?? 1, time: original?.expectedInstallMinutes ?? 15, notes: original?.notes ?? '' } });
  return <form className="form-stack" onSubmit={form.handleSubmit(async v => {
    setError(null); try {
      const expectedInstallMinutes = Math.round(v.time * (unit === 'hours' ? 60 : 1) * 100) / 100;
      await saved(await api<Bom>(`/boms/${bom.id}/items${original ? `/${original.id}` : ''}`, original ? 'PUT' : 'POST', { ...v, expectedInstallMinutes, version }));
    } catch (e) { setError(e); }
  })}><ErrorBox error={error} /><label>كود الموضع<input {...form.register('positionCode')} pattern={codePattern} maxLength={40} required dir="ltr" /></label>
    <label>القطعة<select aria-label="القطعة" {...form.register('partId')} required onChange={e => { form.setValue('partId', e.target.value); const p = parts.find(p => p.id === e.target.value); if (p) { form.setValue('time', p.defaultInstallMinutes); setUnit('minutes'); } }}>
      <option value="">اختر القطعة</option>{parts.filter(p => p.kind === 'SERIALIZED_PIECE' && (p.active || p.id === original?.partId)).map(p => <option key={p.id} value={p.id}>{p.code} · {p.name}{p.active ? '' : ' (مؤرشف)'}</option>)}</select></label>
    <label>الكمية<input type="number" {...form.register('quantity', { valueAsNumber: true })} min="1" max="10000" step="1" required dir="ltr" /></label>
    <div className="form-grid"><label>زمن التركيب لكل قطعة<input type="number" {...form.register('time', { valueAsNumber: true })} min={unit === 'hours' ? 0.01 / 60 : 0.01} max={unit === 'hours' ? 9999999.99 / 60 : 9999999.99} step={unit === 'hours' ? 'any' : '0.01'} required dir="ltr" /></label>
      <label>وحدة الزمن<select aria-label="وحدة الزمن" value={unit} onChange={e => { const next = e.target.value; form.setValue('time', next === 'hours' ? form.getValues('time') / 60 : Math.round(form.getValues('time') * 60 * 100) / 100); setUnit(next); }}><option value="minutes">دقيقة</option><option value="hours">ساعة</option></select></label></div>
    <label>ملاحظات<textarea {...form.register('notes')} rows={2} maxLength={1000} /></label><div className="form-actions"><button className="primary" disabled={form.formState.isSubmitting}><Check size={17} />حفظ</button><button type="button" onClick={cancel}>إلغاء</button></div></form>;
}
