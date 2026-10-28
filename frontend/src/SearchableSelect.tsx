import { useId, useRef, useState } from 'react';
import { Check, ChevronDown } from 'lucide-react';
import { searchable, translateUi, useLanguage } from './i18n';

type Option = { value: string; label: string; keywords?: string };
type Props = { label: string; value: string; options: Option[]; onChange: (value: string) => void; placeholder?: string; disabled?: boolean; required?: boolean };

export function SearchableSelect({ label, value, options, onChange, placeholder, disabled, required }: Props) {
  useLanguage();
  const id = useId(), input = useRef<HTMLInputElement>(null);
  const [open, setOpen] = useState(false), [query, setQuery] = useState(''), [highlight, setHighlight] = useState(0);
  const selected = options.find(option => option.value === value);
  const needle = query.trim().toLocaleLowerCase();
  const matches = options.filter(option => searchable(`${option.label} ${option.keywords ?? ''}`).toLocaleLowerCase().includes(needle));
  const activeIndex = Math.min(Math.max(0, highlight), Math.max(0, matches.length - 1));
  const choose = (option: Option) => { onChange(option.value); setQuery(''); setOpen(false); setHighlight(0); input.current?.focus(); };
  return <div className="searchable-select" onBlur={event => {
    if (!event.currentTarget.contains(event.relatedTarget)) { setOpen(false); setQuery(''); setHighlight(0); }
  }}>
    <div className="searchable-select-control">
      <input ref={input} role="combobox" aria-label={label} aria-expanded={open} aria-controls={`${id}-options`}
        aria-autocomplete="list" aria-activedescendant={open && matches.length ? `${id}-option-${activeIndex}` : undefined}
        autoComplete="off" disabled={disabled} required={required} value={open ? query : selected?.label ?? ''}
        placeholder={open ? selected?.label || placeholder || translateUi('اكتب للبحث والاختيار') : placeholder || translateUi('اكتب للبحث والاختيار')}
        onFocus={() => { setOpen(true); setQuery(''); setHighlight(0); }}
        onClick={() => { if (!open) { setOpen(true); setQuery(''); setHighlight(0); } }}
        onChange={event => { setQuery(event.target.value); setOpen(true); setHighlight(0); }}
        onKeyDown={event => {
          if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
            event.preventDefault(); setOpen(true);
            setHighlight(current => !open ? 0 : event.key === 'ArrowDown' ? Math.min(current + 1, matches.length - 1) : Math.max(0, current - 1));
          } else if (event.key === 'Enter' && open) {
            event.preventDefault(); if (matches[activeIndex]) choose(matches[activeIndex]);
          } else if (event.key === 'Escape' && open) {
            event.preventDefault(); event.stopPropagation(); setOpen(false); setQuery(''); setHighlight(0);
          }
        }} />
      <button type="button" tabIndex={-1} disabled={disabled} aria-label={translateUi('عرض الخيارات')}
        onMouseDown={event => event.preventDefault()} onClick={() => {
          if (open) { setOpen(false); setQuery(''); } else { input.current?.focus(); setOpen(true); setQuery(''); setHighlight(0); }
        }}><ChevronDown size={16} aria-hidden="true" /></button>
    </div>
    {open && <div id={`${id}-options`} role="listbox" aria-label={label} className="searchable-select-options">
      {matches.length ? matches.map((option, index) => <button key={option.value} id={`${id}-option-${index}`} type="button" role="option"
        aria-selected={option.value === value} tabIndex={-1} className={`${option.value === value ? 'selected' : ''} ${index === activeIndex ? 'highlighted' : ''}`}
        onMouseDown={event => event.preventDefault()} onMouseEnter={() => setHighlight(index)} onClick={() => choose(option)}>
        <span>{option.label}</span>{option.value === value && <Check size={16} aria-hidden="true" />}
      </button>) : <div className="searchable-select-empty" role="status">{translateUi('لا توجد نتائج')}</div>}
    </div>}
  </div>;
}
