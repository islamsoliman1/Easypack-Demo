import { forwardRef, useId, useState, type InputHTMLAttributes } from 'react';
import { Eye, EyeOff } from 'lucide-react';
import { getDirection, translateUi, useLanguage } from './i18n';

type PasswordInputProps = Omit<InputHTMLAttributes<HTMLInputElement>, 'type'> & { label: string };

export const PasswordInput = forwardRef<HTMLInputElement, PasswordInputProps>(function PasswordInput(
  { label, id, disabled, ...props }, ref,
) {
  useLanguage();
  const generatedId = useId();
  const inputId = id ?? generatedId;
  const [visible, setVisible] = useState(false);
  const toggleLabel = visible ? translateUi('إخفاء كلمة المرور') : translateUi('إظهار كلمة المرور');

  return <div className="password-field">
    <label htmlFor={inputId}>{label}</label>
    <div className="password-input" dir={getDirection()}>
      <input {...props} ref={ref} id={inputId} disabled={disabled} type={visible ? 'text' : 'password'} />
      <button type="button" className="password-toggle" aria-label={toggleLabel} title={toggleLabel}
        aria-controls={inputId} aria-pressed={visible} disabled={disabled} onClick={() => setVisible(value => !value)}>
        {visible ? <EyeOff size={18} aria-hidden="true" /> : <Eye size={18} aria-hidden="true" />}
      </button>
    </div>
  </div>;
});
