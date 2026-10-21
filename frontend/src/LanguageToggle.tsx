import { setLanguage, useLanguage } from './i18n';

export function LanguageToggle() {
  const language = useLanguage();
  return <button type="button" className="language-toggle" lang={language === 'ar' ? 'en' : 'ar'}
    aria-label={language === 'ar' ? 'Switch to English' : 'التبديل إلى العربية'}
    onClick={() => setLanguage(language === 'ar' ? 'en' : 'ar')}>{language === 'ar' ? 'English' : 'العربية'}</button>;
}
