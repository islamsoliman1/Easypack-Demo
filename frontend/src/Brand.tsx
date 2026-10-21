import { translateUi, translateDisplay, useLanguage, dateFormatter, getLocale, getDirection } from './i18n';
export function Brand() {
  useLanguage();
  return <div className="brand" aria-label="EPE EasyPack">
    <img src="/brand-mark.svg" width="40" height="40" alt="" />
    <div className="brand-copy"><strong dir="ltr">EPE</strong><span dir="ltr">EASYPACK</span></div>
  </div>;
}
