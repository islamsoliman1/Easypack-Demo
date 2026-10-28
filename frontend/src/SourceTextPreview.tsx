import { translateDisplay, useLanguage } from './i18n';

const fields = new Set(['name','displayName','title','description','location','notes','label','criterion']);
export function SourceTextPreview({ values }: { values: unknown }) {
  const language = useLanguage();
  if (language !== 'en') return null;
  const entries: { field: string; source: string; display: string }[] = [];
  const visit = (value: unknown) => {
    if (Array.isArray(value)) { value.forEach(visit); return; }
    if (!value || typeof value !== 'object') return;
    for (const [field, item] of Object.entries(value)) {
      if (fields.has(field) && typeof item === 'string' && item.trim()) entries.push({field,source:item,display:translateDisplay(item)});
      else if (Array.isArray(item)) visit(item);
    }
  };
  visit(values);
  if (!entries.length) return null;
  return <section className="source-preview" aria-label="Reviewed English preview"><strong>Reviewed English preview · edit the original below</strong>
    <dl>{entries.map((entry,index)=><div key={`${entry.field}-${index}`}><dt>{entry.field === 'criterion' ? 'Pass criterion' : entry.field === 'displayName' ? 'Display name' : entry.field[0].toUpperCase()+entry.field.slice(1)}</dt>
      <dd dir="auto">{entry.display}{entry.display === entry.source && /[\u0600-\u06ff]/.test(entry.source) && <small> · Translation not supplied</small>}</dd></div>)}</dl></section>;
}
