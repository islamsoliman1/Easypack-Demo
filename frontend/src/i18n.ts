import i18next from 'i18next';
import { initReactI18next, useTranslation } from 'react-i18next';
import translations from './translations.json';
import content from './demo-content.json';
import systemContent from './system-content.json';
import notificationContent from './notification-content.json';
import notificationUi from './notifications/translations.json';
import factoryUi from './factory-translations.json';
import catalogMediaUi from './catalog-media-translations.json';

export type Language = 'ar' | 'en';
function initialLanguage(): Language {
  try { return localStorage.getItem('easypack-language') === 'en' ? 'en' : 'ar'; } catch { return 'ar'; }
}
const reviewedContent = { ...content, ...notificationContent };
const englishSources: Record<string,string> = { ...Object.fromEntries(Object.entries(reviewedContent).map(([ar,en])=>[en,ar])), ...systemContent };
const english: Record<string, string> = { ...translations, ...notificationUi, ...factoryUi, ...catalogMediaUi, ...reviewedContent, ...Object.fromEntries(Object.keys(englishSources).map(key=>[key,key])) };
const arabic = { ...Object.fromEntries(Object.keys(english).map(key => [key, key])), ...englishSources };
void i18next.use(initReactI18next).init({ lng: initialLanguage(), fallbackLng: 'ar', supportedLngs: ['ar', 'en'],
  resources: { ar: { translation: arabic }, en: { translation: english } }, keySeparator: false, nsSeparator: false,
  interpolation: { escapeValue: false }, initAsync: false, react: { useSuspense: false } });
export const getLanguage = (): Language => i18next.language === 'en' ? 'en' : 'ar';
export const getLocale = () => getLanguage() === 'ar' ? 'ar-EG' : 'en-GB';
export const getDirection = () => getLanguage() === 'ar' ? 'rtl' : 'ltr';
function updateDocument() {
  document.documentElement.lang = getLanguage(); document.documentElement.dir = getDirection();
  document.title = getLanguage() === 'ar' ? 'EasyPack · إدارة المصنع' : 'EasyPack · Factory management';
}
updateDocument();
i18next.on('languageChanged', updateDocument);
export function useLanguage(): Language { useTranslation(); return getLanguage(); }
export function setLanguage(language: Language) {
  try { localStorage.setItem('easypack-language', language); } catch { /* Private browser storage can be disabled. */ }
  void i18next.changeLanguage(language);
}
export function translateUi(key: string, values?: Record<string, unknown>): string {
  return i18next.t(key, Object.fromEntries(Object.entries(values ?? {}).map(([name,value]) => [name, formatDisplay(value)]))) as string;
}
// Translate only reviewed display strings, never business objects or user-entered form values.
export function translateDisplay<T>(value: T): T {
  if (typeof value === 'string' && getLanguage() === 'en' && /^طلب التصنيع [A-Za-z0-9][A-Za-z0-9._/-]{0,63}$/.test(value))
    return ('Manufacturing order ' + value.slice('طلب التصنيع '.length)) as T;
  if (typeof value === 'string' && Object.hasOwn(english, value)) return translateUi(value) as T;
  return value;
}
export function formatDisplay<T>(value: T): T {
  if (typeof value === 'number') return new Intl.NumberFormat(getLocale(), { maximumFractionDigits: 12 }).format(value) as T;
  return translateDisplay(value);
}
export function searchable(value: string): string {
  let result = value;
  for (const [source, target] of Object.entries(content)) {
    if (value.includes(source)) result += ` ${target}`;
    if (value.toLowerCase().includes(target.toLowerCase())) result += ` ${source}`;
  }
  return result;
}
// Formatters at module scope must follow the current language after every switch.
export function dateFormatter(options?: Intl.DateTimeFormatOptions) {
  return { format: (value: Date | number) => new Intl.DateTimeFormat(getLocale(), options).format(value) };
}
export function originalData<T>(value: T): T {
  if (Array.isArray(value)) return value.map(originalData) as T;
  if (value && typeof value === 'object') {
    const object = value as Record<string, unknown>;
    return Object.fromEntries(Object.entries(object).filter(([key]) => key !== 'originalText').map(([key, item]) =>
      [key, originalData((object.originalText as Record<string, unknown> | undefined)?.[key] ?? item)])) as T;
  }
  return value;
}
