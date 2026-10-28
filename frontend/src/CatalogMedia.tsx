import { lazy, Suspense, useEffect, useRef, useState } from 'react';
import { Box, ImagePlus, Maximize2, Rotate3d } from 'lucide-react';
import { useQueryClient } from '@tanstack/react-query';
import { useQuery } from './localizedQuery';
import { api, ApiError } from './api';
import { useAuth } from './access';
import { translateUi, useLanguage } from './i18n';
import { Busy, ErrorBox, Modal } from './ui';

const CadViewer = lazy(() => import('./CadViewer'));
export type VisualKind = 'MODEL' | 'MACHINE' | 'PART';
export type VisualFile = { id: string; filename: string; contentType: string; sizeBytes: number; targetKind?: string; targetId?: string; purpose?: string };
export type DesignSelection = { design: File | null; model?: File | null; preview: File | null };
export const designAccept = '.sldprt,.sldasm,.slddrw,.step,.stp,.stl,.glb';
export const isBrowserModel = (file: Pick<VisualFile, 'filename'>) => /\.(stl|glb)$/i.test(file.filename);
const validSize = (file: File) => { if (!file.size || file.size > 10485760) throw new ApiError(400, 'FILE_SIZE'); };

export function DesignInputs({ value, onChange, disabled = false }: { value: DesignSelection; onChange: (value: DesignSelection) => void; disabled?: boolean }) {
  useLanguage();
  const [previewUrl, setPreviewUrl] = useState('');
  useEffect(() => {
    if (!value.preview) { setPreviewUrl(''); return; }
    const url = URL.createObjectURL(value.preview); setPreviewUrl(url);
    return () => URL.revokeObjectURL(url);
  }, [value.preview]);
  return <fieldset className="design-inputs" disabled={disabled}><legend><ImagePlus size={18} />{translateUi('التصميم وصورة العرض')}</legend>
    <p className="muted">{translateUi('ارفع الملف الأصلي، ونسخة STL أو GLB للعرض ثلاثي الأبعاد. صورة الكارت اختيارية. الحد الأقصى 10 ميجابايت لكل ملف.')}</p>
    <label>{translateUi('ملف التصميم الأصلي')}<input type="file" accept={designAccept} onChange={e => onChange({ ...value, design: e.target.files?.[0] ?? null })} /></label>
    <label>{translateUi('نسخة العرض ثلاثي الأبعاد · STL أو GLB')}<input type="file" accept=".stl,.glb" onChange={e => onChange({ ...value, model: e.target.files?.[0] ?? null })} /></label>
    <label>{translateUi('صورة الكارت · PNG أو JPG')}<input type="file" accept=".png,.jpg,.jpeg" onChange={e => onChange({ ...value, preview: e.target.files?.[0] ?? null })} /></label>
    {previewUrl && <img className="design-input-preview" src={previewUrl} alt={translateUi('معاينة صورة الكارت')} />}
  </fieldset>;
}

export function useDesignUpload() {
  const client = useQueryClient();
  const completed = useRef(new WeakMap<File, Set<string>>());
  return { upload: async (kind: 'MODEL' | 'PART', id: string, selection: DesignSelection) => {
    const files = [[selection.design, 'DESIGN'], [selection.model, 'DESIGN'], [selection.preview, 'PREVIEW']] as const;
    // Validate every file before starting the sequence; successful steps survive a retry.
    for (const [file] of files) if (file) validSize(file);
    for (const [file, purpose] of files) {
      if (!file) continue;
      const key = `${kind}:${id}:${purpose}`;
      if (completed.current.get(file)?.has(key)) continue;
      const body = new FormData(); body.append('file', file);
      await api(`/files?kind=${kind}&targetId=${id}&purpose=${purpose}`, 'POST', body);
      const targets = completed.current.get(file) ?? new Set<string>(); targets.add(key); completed.current.set(file, targets);
      await client.invalidateQueries({ queryKey: ['files'] });
    }
  } };
}

function useFileBlob(file: VisualFile) {
  return useQuery({ queryKey: ['file-content', file.id], queryFn: () => api<Blob>(`/files/${file.id}`, 'GET', undefined, true), staleTime: Infinity, gcTime: 60000 });
}

function BlobImage({ blob, name, className }: { blob: Blob; name: string; className?: string }) {
  const [url, setUrl] = useState('');
  useEffect(() => { const next = URL.createObjectURL(blob); setUrl(next); return () => URL.revokeObjectURL(next); }, [blob]);
  return url ? <img src={url} alt={name} className={className} /> : null;
}

function VisualContent({ file, interactive = false }: { file: VisualFile; interactive?: boolean }) {
  useLanguage();
  const blob = useFileBlob(file);
  if (blob.isPending) return <Busy />;
  if (blob.error) return <ErrorBox error={blob.error} />;
  if (isBrowserModel(file)) return <Suspense fallback={<Busy />}><CadViewer blob={blob.data!} filename={file.filename} interactive={interactive} /></Suspense>;
  return <BlobImage blob={blob.data!} name={file.filename} className="file-preview" />;
}

export function FilePreview({ file }: { file: VisualFile }) {
  useLanguage();
  const blob = useFileBlob(file);
  const [url, setUrl] = useState('');
  useEffect(() => { if (!blob.data) return; const next = URL.createObjectURL(blob.data); setUrl(next); return () => URL.revokeObjectURL(next); }, [blob.data]);
  if (blob.isPending) return <Busy />;
  if (blob.error) return <ErrorBox error={blob.error} />;
  return <div className="visual-preview">{isBrowserModel(file) ? <Suspense fallback={<Busy />}><CadViewer blob={blob.data!} filename={file.filename} interactive /></Suspense>
    : file.contentType === 'application/pdf' ? <iframe className="file-preview file-pdf-preview" title={file.filename} src={url} />
    : <BlobImage blob={blob.data!} name={file.filename} className="file-preview" />}</div>;
}

export function ResourceVisual({ kind, id, name, compact = false }: { kind: VisualKind; id: string; name: string; compact?: boolean }) {
  useLanguage();
  const { user } = useAuth();
  const host = useRef<HTMLDivElement>(null);
  const [visible, setVisible] = useState(false), [opened, setOpened] = useState<VisualFile | null>(null);
  useEffect(() => {
    if (!host.current) return;
    if (!('IntersectionObserver' in window)) { setVisible(true); return; }
    const observer = new IntersectionObserver(entries => { if (entries.some(e => e.isIntersecting)) { setVisible(true); observer.disconnect(); } }, { rootMargin: '150px' });
    observer.observe(host.current); return () => observer.disconnect();
  }, []);
  const query = useQuery({ queryKey: ['files', 'visuals', kind, id, user.permissions.join(',')], queryFn: () => api<VisualFile[]>(`/files/visuals?kind=${kind}&targetId=${id}`), enabled: !!id && visible });
  const image = query.data?.find(file => file.contentType.startsWith('image/'));
  const model = query.data?.find(isBrowserModel);
  const display = image ?? model ?? query.data?.find(file => file.contentType === 'application/pdf');
  return <div ref={host} className={`resource-visual${compact ? ' resource-visual-compact' : ''}`}>
    {display ? <button type="button" className="resource-visual-open" title={translateUi('عرض تصميم {{v0}}', { v0: name })} aria-label={translateUi('عرض تصميم {{v0}}', { v0: name })} onClick={() => setOpened(display)}>
      {display.contentType === 'application/pdf' ? <Box size={compact ? 24 : 48} /> : <VisualContent key={display.id} file={display} />}
      {!compact && <span className="resource-visual-caption"><Maximize2 size={15} />{translateUi('عرض التصميم')}</span>}
    </button> : <div className="resource-visual-empty" title={translateUi('لم تتم إضافة صورة أو نسخة عرض بعد')}><Box size={compact ? 24 : 44} />{!compact && <small>{translateUi(query.error ? 'تعذر تحميل التصميم' : 'لم تتم إضافة صورة أو نسخة عرض بعد')}</small>}</div>}
    {model && image && <button type="button" className="resource-visual-3d" aria-label={translateUi('عرض ثلاثي الأبعاد لـ {{v0}}', { v0: name })} onClick={() => setOpened(model)}><Rotate3d size={16} />{!compact && translateUi('عرض ثلاثي الأبعاد')}</button>}
    {opened && <Modal title={name} close={() => setOpened(null)}><div className="modal-body"><FilePreview key={opened.id} file={opened} /></div></Modal>}
  </div>;
}
