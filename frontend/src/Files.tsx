import { formatDisplay } from './i18n';
import { translateUi, translateDisplay, useLanguage, dateFormatter, getLocale, getDirection } from './i18n';
import { useEffect, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useQuery, localizedKey } from './localizedQuery';
import { Download, Eye, Paperclip, RefreshCw, Trash2, Upload } from 'lucide-react';
import { api, ApiError } from './api';
import { can, useAuth } from './access';
import { Busy, ErrorBox, IconButton, Modal, QueryError } from './ui';

type FileRecord = { id: string; filename: string; contentType: string; sizeBytes: number; version: number; uploadedBy: string; uploadedAt: string; canManage: boolean };
export type FileKind = 'ORDER' | 'MACHINE' | 'PART' | 'DEFECT';
export function saveBlob(blob: Blob, filename: string) { const url = URL.createObjectURL(blob), a = document.createElement('a'); a.href = url; a.download = filename; a.click(); window.setTimeout(() => URL.revokeObjectURL(url), 1000); }
export function FileButton({ kind, id, name }: { kind: FileKind; id: string; name: string }) {
  useLanguage();
  const [open, setOpen] = useState(false);
  return <><IconButton label={formatDisplay(translateUi("مرفقات {{v0}}", {v0: name}))} onClick={() => setOpen(true)}><Paperclip size={17} /></IconButton>{formatDisplay(open && <Modal title={formatDisplay(translateUi("مرفقات {{v0}}", {v0: name}))} close={() => setOpen(false)}><div className="modal-body"><Files kind={kind} id={id} /></div></Modal>)}</>;
}
export function Files({ kind, id }: { kind: FileKind; id: string }) {
  useLanguage();
  const { user } = useAuth(); const client = useQueryClient(), key = ['files', kind, id];
  const query = useQuery({ queryKey: key, queryFn: () => api<FileRecord[]>(`/files?kind=${translateDisplay(kind)}&targetId=${translateDisplay(id)}`) });
  const [error, setError] = useState<unknown>(null), [pending, setPending] = useState(false), [selected, setSelected] = useState<File | null>(null), [preview, setPreview] = useState<FileRecord | null>(null), [removing, setRemoving] = useState<FileRecord | null>(null), [inputKey, setInputKey] = useState(0);
  const action = async (fn: () => Promise<unknown>) => { setPending(true); setError(null); try { await fn(); } catch (e) { setError(e); } finally { setPending(false); } };
  return <section className="resource-files"><div className="bom-summary"><h3>{translateUi("الرسومات والمرفقات")}</h3><IconButton label={translateUi("تحديث المرفقات")} disabled={pending || query.isFetching} onClick={() => void query.refetch()}><RefreshCw size={17} /></IconButton></div><ErrorBox error={error} />
    {formatDisplay(can(user, 'files.manage') && <form className="file-upload" onSubmit={e => { e.preventDefault(); if (!selected) return; void action(async () => {
      if (selected.size > 10485760 || !selected.size) throw new ApiError(400, 'FILE_SIZE'); const data = new FormData(); data.append('file', selected);
      await api(`/files?kind=${translateDisplay(kind)}&targetId=${translateDisplay(id)}`, 'POST', data); setSelected(null); setInputKey(v => v + 1); await client.invalidateQueries({ queryKey: key });
    }); }}><label>{translateUi("الملف")}<input key={inputKey} type="file" aria-label={translateUi("ملف المرفق")} accept=".pdf,.png,.jpg,.jpeg" disabled={pending} onChange={e => setSelected(e.target.files?.[0] ?? null)} /></label><button disabled={pending || !selected}><Upload size={17} />{translateUi("رفع")}</button></form>)}
    {formatDisplay(query.isPending ? <Busy /> : query.error ? <QueryError error={query.error} retry={() => void query.refetch()} /> : <ul className="file-list">{formatDisplay(!query.data?.length && <li className="muted">{translateUi("لا توجد مرفقات")}</li>)}{formatDisplay(query.data?.map(f => <li key={f.id}><div><strong>{formatDisplay(f.filename)}</strong><span className="record-code">{formatDisplay(Math.ceil(f.sizeBytes / 1024))} KB · {formatDisplay(f.uploadedBy)}</span></div><div className="row-actions">{formatDisplay(f.contentType.startsWith('image/') && <IconButton label={formatDisplay(translateUi("عرض {{v0}}", {v0: f.filename}))} disabled={pending} onClick={() => setPreview(f)}><Eye size={17} /></IconButton>)}<IconButton label={formatDisplay(translateUi("تنزيل {{v0}}", {v0: f.filename}))} disabled={pending} onClick={() => void action(async () => saveBlob(await api<Blob>(`/files/${translateDisplay(f.id)}`, 'GET', undefined, true), f.filename))}><Download size={17} /></IconButton>{formatDisplay(f.canManage && <IconButton label={formatDisplay(translateUi("حذف {{v0}}", {v0: f.filename}))} disabled={pending} onClick={() => setRemoving(f)}><Trash2 size={17} /></IconButton>)}</div></li>))}</ul>)}
    {formatDisplay(preview && <Modal title={formatDisplay(preview.filename)} close={() => setPreview(null)}><Preview file={preview} /></Modal>)}
    {formatDisplay(removing && <Modal title={translateUi("حذف المرفق")} close={() => { if (!pending) setRemoving(null); }}><div className="modal-body"><p className="order-notes">{formatDisplay(removing.filename)}</p><ErrorBox error={error} /><div className="form-actions"><button className="danger" disabled={pending} onClick={() => void action(async () => { await api(`/files/${translateDisplay(removing.id)}`, 'DELETE', { version: removing.version }); setRemoving(null); await client.invalidateQueries({ queryKey: key }); })}><Trash2 size={17} />{translateUi("تأكيد الحذف")}</button><button disabled={pending} onClick={() => setRemoving(null)}>{translateUi("رجوع")}</button></div></div></Modal>)}
  </section>;
}
function Preview({ file }: { file: FileRecord }) {
  useLanguage();
  const [url, setUrl] = useState(''), [error, setError] = useState<unknown>(null);
  useEffect(() => { let alive = true, local = ''; void api<Blob>(`/files/${translateDisplay(file.id)}`, 'GET', undefined, true).then(b => { local = URL.createObjectURL(b); if (alive) setUrl(local); else URL.revokeObjectURL(local); }).catch(e => { if (alive) setError(e); }); return () => { alive = false; if (local) URL.revokeObjectURL(local); }; }, [file.id]);
  return <div className="modal-body"><ErrorBox error={error} />{formatDisplay(url ? <img className="file-preview" src={url} alt={formatDisplay(file.filename)} /> : !error && <Busy />)}</div>;
}
