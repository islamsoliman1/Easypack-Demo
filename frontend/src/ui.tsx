import React, { useEffect, useRef } from 'react';
import { NavLink } from 'react-router-dom';
import { CircleAlert, LoaderCircle, RefreshCw, ShieldCheck, X } from 'lucide-react';
import { ApiError } from './api';

const errors: Record<string, string> = {
  DEMO_STARTING: 'جارٍ تجهيز بيانات الديمو. حاول مجددًا بعد قليل.',
  DEMO_UNAVAILABLE: 'الديمو غير متاح حاليًا. حاول إعادة التحميل.',
  DEMO_BUSY: 'الديمو مشغول حاليًا. حاول مجددًا بعد قليل.',
  INVALID_LOGIN: 'البريد الإلكتروني أو كلمة المرور غير صحيحة.', LOGIN_LIMIT: 'محاولات كثيرة. حاول بعد 15 دقيقة.',
  FORBIDDEN: 'ليست لديك صلاحية لتنفيذ هذا الإجراء.', DUPLICATE: 'الكود أو الرقم أو الموضع داخل القائمة أو البريد الإلكتروني أو اسم الدور مستخدم بالفعل.',
  LAST_ADMIN: 'يجب الإبقاء على مدير نشط واحد على الأقل بجميع صلاحيات الإدارة.',
  STALE_VERSION: 'تم تعديل السجل. أغلق النافذة وحدّث القائمة قبل المحاولة مجددًا.',
  PASSWORD_POLICY: 'كلمة المرور 12 حرفًا على الأقل وبحد أقصى 72 بايت.', CURRENT_PASSWORD: 'كلمة المرور الحالية غير صحيحة.',
  SAME_PASSWORD: 'اختر كلمة مرور مختلفة عن الحالية.', VALIDATION: 'راجع البيانات المدخلة.',
  PASSWORD_CHANGE_REQUIRED: 'يجب تغيير كلمة المرور أولًا.', SESSION_EXPIRED: 'انتهت الجلسة. سجّل الدخول مجددًا.',
  NOT_FOUND: 'السجل غير موجود.', INVALID_SPECIFICATION: 'المقاسات أو وحدة المخزون أو زمن التركيب لا تتوافق مع نوع وشكل الصنف.',
  CODE_IMMUTABLE: 'لا يمكن تغيير الكود بعد الإنشاء.', SPECIFICATION_IN_USE: 'مواصفات الصنف مستخدمة في مكونات أو مخزون. أنشئ صنفًا بكود جديد للمواصفة المختلفة.',
  MODEL_INACTIVE: 'الموديل مؤرشف. لا يمكن إنشاء ماكينة أو إصدار مكونات جديد له.', PART_INACTIVE: 'أحد الأصناف مؤرشف. راجع مكونات المسودة.',
  MACHINE_INACTIVE: 'الماكينة مؤرشفة. لا يمكن تعديل قائمة قطعها.',
  MACHINE_IDENTITY_IMMUTABLE: 'رقم الماكينة وموديلها ثابتان بعد الإنشاء.', DRAFT_EXISTS: 'توجد مسودة بالفعل. حدّث القائمة.',
  INVALID_BOM_SOURCE: 'اختر إصدارًا منشورًا من نفس الموديل أو نفس الماكينة، وليس من ماكينة أخرى.', BOM_LOCKED: 'الإصدار المنشور ثابت. أنشئ مسودة إصدار جديد.',
  BOM_EMPTY: 'أضف مكونًا واحدًا على الأقل قبل النشر.', RAW_MATERIAL_NOT_INSTALLABLE: 'الخامة المقاسة ليست قطعة جاهزة للتركيب.',
  ITEM_NOT_IN_BOM: 'الموضع لا ينتمي إلى قائمة المكونات الحالية.',
  ORDER_DELIVERY_PAST: 'موعد التسليم يجب أن يكون في المستقبل.', ORDER_BOM_INVALID: 'اختر إصدارًا منشورًا من قائمة قطع الماكينة نفسها.',
  ORDER_RESPONSIBLE_INVALID: 'المسؤول يجب أن يكون نشطًا ولديه صلاحية عرض طلباته أو عرض جميع الطلبات.',
  ORDER_LOCKED: 'حالة الطلب لا تسمح بهذا الإجراء. التعديل متاح للمسودات فقط.',
  STOCK_SERIALIZED_ONLY: 'استلام الأرقام المسلسلة متاح للقطع الجاهزة فقط.', STOCK_DUPLICATE_SERIAL: 'أحد الأرقام المسلسلة مكرر في الاستلام أو مسجل بالفعل.',
  STOCK_SELECTION_INVALID: 'راجع القطع المحددة وحالتها وانتماءها للطلب، ثم حدّث البيانات.', STOCK_ORDER_NOT_OPEN: 'حركات تجهيز المخزن متاحة للطلبات المفتوحة فقط.',
  STOCK_RETURN_BEFORE_CANCEL: 'أرجع جميع القطع المصروفة قبل إلغاء الطلب.', STOCK_NO_SHORTAGE: 'لا يوجد نقص متاح لهذا الإجراء.', PREPARATION_EXISTS: 'يوجد طلب تجهيز لهذا الموضع بالفعل.',
  RAW_MEASURED_ONLY: 'اختر صنف خامة مقاسة بالطول أو المساحة.', RAW_DUPLICATE_CODE: 'أحد أكواد الخامات مكرر أو مسجل بالفعل.',
  RAW_PROFILE_MISMATCH: 'شكل الخامة أو عرض القطاع أو القطر لا يتوافق مع القطعة المطلوبة.',
  RAW_UNIT_NOT_AVAILABLE: 'الخامة غير متاحة للصرف. حدّث القائمة.', RAW_UNIT_NOT_ISSUED: 'الخامة ليست مصروفة للتصنيع.',
  RAW_PREPARATION_NOT_ACTIVE: 'طلب التجهيز يجب أن يكون قيد التجهيز وبه نقص غير مغطى.',
  RAW_OUTPUT_EXCEEDS_SHORTAGE: 'عدد القطع الناتجة أكبر من النقص الحالي. حدّث الطلب.',
  RAW_CUT_DOES_NOT_FIT: 'راجع المقاسات والتدوير وعدد الأعمدة؛ التخطيط لا يتسع في الخامة أو عدد القطع لا يقبل القسمة على الأعمدة.',
  RAW_SCRAP_REASON_REQUIRED: 'سجل سبب الهالك في الملاحظات.', RAW_RETURN_BEFORE_CANCEL: 'أرجع الخامات المصروفة أو سجّل قصها قبل إلغاء الطلب.',
  TASK_WORKER_INVALID: 'اختر عاملًا نشطًا لديه صلاحية عرض مهامه والعمل عليها.', TASK_RETURN_BEFORE_ASSIGN: 'أرجع القطعة المصروفة وفك المركب قبل تغيير التكليف. المصروف سابقًا دون مهمة يجب إرجاعه أولًا.',
  TASK_ASSIGN_REASON_REQUIRED: 'سجل سبب تغيير العامل أو إلغاء التكليف.', TASK_RECIPIENT_MISMATCH: 'المستلم ليس مكلفًا بمهمة متاحة لهذه القطعة، أو تم الصرف لكل مهامه بالفعل.',
  TASK_NOT_YOURS: 'بدء التركيب وتأكيده متاحان للعامل المكلف فقط.', TASK_PIECE_REQUIRED: 'يجب صرف قطعة للمهمة أولًا، والمهمة لم تبدأ بعد.',
  TASK_START_REQUIRED: 'ابدأ المهمة أولًا وتأكد من وجود قطعة مصروفة لها.', TASK_SLOT_OCCUPIED: 'هذا الموضع مشغول بقطعة مركبة على الماكينة. راجع سجل التركيب.',
  TASK_NOT_INSTALLED: 'لا توجد قطعة مركبة حاليًا لهذه المهمة.', TASK_UNINSTALL_BEFORE_CANCEL: 'فك القطع المركبة وأرجعها للمخزن قبل إلغاء الطلب.',
  DEFECT_ISSUED_REQUIRED: 'الرفض متاح لقطعة مصروفة في عهدتك فقط. فك القطعة المركبة أولًا.',
  DEFECT_STATE_INVALID: 'حالة بلاغ العيب لا تسمح بهذا الإجراء. حدّث القائمة.',
  DEFECT_USE_WORKFLOW: 'هذه القطعة مرتبطة ببلاغ عيب مفتوح. أكمل مسار الإصلاح والاستلام من شاشة العيوب.',
  DEFECT_RETURN_BEFORE_CANCEL: 'استلم القطع المرفوضة من العمال قبل إلغاء الطلب.',
  OPERATIONS_CONFIGURATION_CHANGED: 'تغير تركيب الماكينة. حدّث البيانات وسجل تجربة جديدة.',
  OPERATIONS_COMPLETED_CONFIGURATION_LOCKED: 'اكتملت تجربة هذه الماكينة وتنتظر التسليم. تركيبها ثابت حتى تسجيل التسليم.',
  OPERATIONS_ASSEMBLY_INCOMPLETE: 'يجب اكتمال تركيب جميع قطع الطلب أولًا.',
  OPERATIONS_OPEN_DEFECTS: 'أغلق بلاغات عيوب الطلب قبل التجربة.',
  OPERATIONS_OPEN_CUSTODY: 'سوّ عهدة القطع والخامات المصروفة قبل التجربة.',
  OPERATIONS_ORDER_STATE: 'حالة الطلب لا تسمح بهذا الإجراء. حدّث بيانات التشغيل.',
  OPERATIONS_ALREADY_RUNNING: 'توجد تجربة جارية لهذه الماكينة بالفعل.',
  OPERATIONS_TRIAL_CLOSED: 'التجربة مغلقة أو أُبطلت. حدّث البيانات.',
  OPERATIONS_CHECKS_INVALID: 'سجل نتيجة كل بند مرة واحدة دون تغيير بنود التجربة.',
  OPERATIONS_RESULT_INVALID: 'نتيجة التجربة لا تتطابق مع نتائج بنود الفحص.',
  OPERATIONS_FAILURE_REASON: 'سجل سبب عدم اجتياز كل بند فاشل.',
  SHIFT_INVALID: 'راجع أيام العمل والاستراحات؛ يجب ألا تتداخل وأن تقع داخل 08:00 إلى 17:00.',
  SHIFT_RANGE: 'اختر فترة ورديات لا تتجاوز 93 يومًا.', SHIFT_PAST: 'لا يمكن تعديل وردية يوم سابق.',
  SHIFT_PERMISSION: 'تغيير أيام العمل وتمديد الوردية لهما صلاحيات مستقلة.', SHIFT_DAY_LOCKED: 'لهذا اليوم وقت مسجل؛ المتاح فقط تمديده دون تعديل الحساب السابق.',
  TIME_ALREADY_RUNNING: 'لديك وقت جارٍ لمهمة أخرى. أوقفه قبل الاستئناف.', TIME_OUTSIDE_SHIFT: 'الوقت خارج الوردية المعتمدة لهذا اليوم.',
  TIME_NOT_RUNNING: 'لا يوجد وقت جارٍ لهذه المهمة.', TIME_REASON_REQUIRED: 'سجل سبب إيقاف أو استئناف الوقت.',
  FILE_TYPE: 'الملف يجب أن يكون PDF أو صورة PNG أو JPEG صالحة.', FILE_SIZE: 'حجم الملف من بايت واحد إلى 10 ميجابايت.', FILE_NAME: 'اسم الملف غير صالح أو طويل جدًا.',
  REPORT_RANGE: 'اختر فترة تقرير صحيحة لا تتجاوز 367 يومًا.',
};
export function message(error: unknown) { return error instanceof ApiError ? errors[error.code] ?? 'تعذر تنفيذ الطلب. حاول مجددًا.' : 'تعذر الاتصال بالخادم. تحقق من الاتصال وحاول مجددًا.'; }
export function Busy() { return <div className="state"><LoaderCircle className="spin" size={24} /><span>جارٍ التحميل</span></div>; }
export function ErrorBox({ error }: { error: unknown }) { return error ? <div className="error" role="alert"><CircleAlert size={18} aria-hidden="true" /><span>{message(error)}</span></div> : null; }
export function IconButton({ label, children, ...props }: React.ButtonHTMLAttributes<HTMLButtonElement> & { label: string }) {
  return <button type="button" className="icon-button" title={label} aria-label={label} {...props}>{children}</button>;
}
export function Modal({ title, close, children }: { title: string; close: () => void; children: React.ReactNode }) {
  const dialog = useRef<HTMLDialogElement>(null);
  useEffect(() => { const el = dialog.current!; el.showModal(); return () => el.close(); }, []);
  return <dialog ref={dialog} className="modal" onCancel={e => { e.preventDefault(); close(); }} onClick={e => { if (e.target === e.currentTarget) close(); }}>
    <header><h2>{title}</h2><IconButton label="إغلاق" onClick={close}><X size={20} /></IconButton></header>{children}
  </dialog>;
}
export function QueryError({ error, retry }: { error: unknown; retry: () => void }) {
  return <div className="state"><ErrorBox error={error} /><button onClick={retry}><RefreshCw size={17} />إعادة المحاولة</button></div>;
}
export function Denied() { return <div className="state"><ShieldCheck size={32} /><h1>غير مصرح بالدخول</h1><p>ليس لديك صلاحية عرض هذه الصفحة.</p><NavLink to="/">حسابي</NavLink></div>; }
