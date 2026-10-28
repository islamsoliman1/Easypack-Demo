import { translateUi, translateDisplay, formatDisplay, useLanguage } from './i18n';
import { useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useQuery } from './localizedQuery';
import { Download, Eye, FileImage, Paperclip, RefreshCw, Trash2, Upload } from 'lucide-react';
import { api, ApiError } from './api';
import { can, useAuth } from './access';
import { Busy, ErrorBox, IconButton, Modal, QueryError } from './ui';
import { FilePreview, designAccept, isBrowserModel } from './CatalogMedia';

type FileRecord = { id: string; filename: string; contentType: string; sizeBytes: number; version: number; uploadedBy: string; uploadedAt: string; canManage: boolean; purpose: string };
export type FileKind = 'ORDER' | 'MODEL' | 'MACHINE' | 'PART' | 'DEFECT';
export function saveBlob(blob: Blob, filename: string) { const url = URL.createObjectURL(blob), a = document.createElement('a'); a.href = url; a.download = filename; a.click(); window.setTimeout(() => URL.revokeObjectURL(url), 1000); }
export function FileButton({ kind, id, name }: { kind: FileKind; id: string; name: string }) {
  useLanguage();
  const [open, setOpen] = useState(false);
  const design = kind === 'MODEL' || kind === 'MACHINE' || kind === 'PART';
  const title = translateUi(design ? 'تصميمات {{v0}}' : 'مرفقات {{v0}}', { v0: name });
  return <><button type="button" className="resource-file-button" aria-label={title} onClick={() => setOpen(true)}>{design ? <FileImage size={17} /> : <Paperclip size={17} />}{translateUi(design ? 'التصميمات' : 'المرفقات')}</button>{open && <Modal title={title} close={() => setOpen(false)}><div className="modal-body"><Files key={`${kind}-${id}`} kind={kind} id={id} /></div></Modal>}</>;
}
export function Files({ kind, id }: { kind: FileKind; id: string }) {
  useLanguage();
  const { user } = useAuth(); const client = useQueryClient(), key = ['files', kind, id];
  const design = ['MODEL', 'MACHINE', 'PART'].includes(kind);
  const editableScope = kind === 'MODEL' ? can(user, 'catalog.manage')
    : kind === 'PART' ? can(user, 'catalog.view') || can(user, 'tasks.view-assigned')
    : kind === 'MACHINE' ? can(user, 'machines.view') || can(user, 'tasks.view-assigned') : true;
  const manage = can(user, 'files.manage') && editableScope;
  const [purpose, setPurpose] = useState('ATTACHMENT');
  const query = useQuery({ queryKey: key, queryFn: () => api<FileRecord[]>(`/files?kind=${kind}&targetId=${id}`) });
  const [error, setError] = useState<unknown>(null), [pending, setPending] = useState(false), [selected, setSelected] = useState<File | null>(null), [preview, setPreview] = useState<FileRecord | null>(null), [removing, setRemoving] = useState<FileRecord | null>(null), [inputKey, setInputKey] = useState(0);
  const action = async (fn: () => Promise<unknown>) => { setPending(true); setError(null); try { await fn(); } catch (e) { setError(e); } finally { setPending(false); } };
  return <section className="resource-files"><div className="bom-summary"><h3>{translateUi("الرسومات والمرفقات")}</h3><IconButton label={translateUi("تحديث المرفقات")} disabled={pending || query.isFetching} onClick={() => void query.refetch()}><RefreshCw size={17} /></IconButton></div><p className="muted">{translateUi(design ? 'ملفات SolidWorks وSTEP للحفظ، STL وGLB للعرض ثلاثي الأبعاد، وPNG أو JPG لصورة الكارت. حتى 10 ميجابايت.' : 'PDF أو PNG أو JPEG · حتى 10 ميجابايت للملف. تُحفظ التصميمات مع هذا السجل.')}</p>{!manage && <p className="muted">{translateUi('رفع التصميمات وحذفها يحتاج صلاحية إدارة المرفقات.')}</p>}<ErrorBox error={error} />
    {formatDisplay(manage && <form className="file-upload" onSubmit={e => { e.preventDefault(); if (!selected) return; void action(async () => {
      if (selected.size > 10485760 || !selected.size) throw new ApiError(400, 'FILE_SIZE'); const data = new FormData(); data.append('file', selected);
      await api(`/files?kind=${kind}&targetId=${id}&purpose=${purpose}`, 'POST', data); setSelected(null); setInputKey(v => v + 1); await client.invalidateQueries({ queryKey: ['files'] });
    }); }}><label>{translateUi("استخدام الملف")}<select value={purpose} disabled={pending} onChange={e => { setPurpose(e.target.value); setSelected(null); setInputKey(v => v + 1); }}><option value="ATTACHMENT">{translateUi("مرفق")}</option>{design && <><option value="DESIGN">{translateUi("ملف تصميم")}</option><option value="PREVIEW">{translateUi("صورة الكارت")}</option></>}</select></label><label>{translateUi("الملف")}<input key={inputKey} type="file" aria-label={translateUi("ملف المرفق")} accept={design && purpose === 'DESIGN' ? designAccept : purpose === 'PREVIEW' ? '.png,.jpg,.jpeg' : '.pdf,.png,.jpg,.jpeg'} disabled={pending} onChange={e => setSelected(e.target.files?.[0] ?? null)} /></label><button disabled={pending || !selected}><Upload size={17} />{translateUi("رفع")}</button></form>)}
    {formatDisplay(query.isPending ? <Busy /> : query.error ? <QueryError error={query.error} retry={() => void query.refetch()} /> : <ul className="file-list">{formatDisplay(!query.data?.length && <li className="muted">{translateUi("لا توجد مرفقات")}</li>)}{formatDisplay(query.data?.map(f => <li key={f.id}><div><strong>{formatDisplay(f.filename)}</strong><span className="record-code">{formatDisplay(Math.ceil(f.sizeBytes / 1024))}{' '}{translateUi("كيلوبايت")} · {formatDisplay(f.uploadedBy)}</span></div><div className="row-actions">{formatDisplay((f.contentType.startsWith('image/') || f.contentType === 'application/pdf' || isBrowserModel(f)) && <IconButton label={translateDisplay(translateUi("عرض {{v0}}", {v0: f.filename}))} disabled={pending} onClick={() => setPreview(f)}><Eye size={17} /></IconButton>)}<IconButton label={translateDisplay(translateUi("تنزيل {{v0}}", {v0: f.filename}))} disabled={pending} onClick={() => void action(async () => saveBlob(await api<Blob>(`/files/${f.id}`, 'GET', undefined, true), f.filename))}><Download size={17} /></IconButton>{formatDisplay(f.canManage && <IconButton label={translateDisplay(translateUi("حذف {{v0}}", {v0: f.filename}))} disabled={pending} onClick={() => setRemoving(f)}><Trash2 size={17} /></IconButton>)}</div></li>))}</ul>)}
    {formatDisplay(preview && <Modal title={translateDisplay(preview.filename)} close={() => setPreview(null)}><FilePreview file={preview} /></Modal>)}
    {formatDisplay(removing && <Modal title={translateUi("حذف المرفق")} close={() => { if (!pending) setRemoving(null); }}><div className="modal-body"><p className="order-notes">{formatDisplay(removing.filename)}</p><ErrorBox error={error} /><div className="form-actions"><button className="danger" disabled={pending} onClick={() => void action(async () => { await api(`/files/${removing.id}`, 'DELETE', { version: removing.version }); setRemoving(null); await client.invalidateQueries({ queryKey: ['files'] }); })}><Trash2 size={17} />{translateUi("تأكيد الحذف")}</button><button disabled={pending} onClick={() => setRemoving(null)}>{translateUi("رجوع")}</button></div></div></Modal>)}
  </section>;
}
