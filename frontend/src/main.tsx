import { PasswordInput } from './PasswordInput';
import { formatDisplay } from './i18n';
import { translateUi, translateDisplay, useLanguage, dateFormatter, getLocale, getDirection } from './i18n';
import { LanguageToggle } from './LanguageToggle';
import { NotificationProvider } from './notifications/useNotifications';
import { NotificationBell } from './notifications/NotificationBell';
import { searchable } from './i18n';
import React, { useEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter, NavLink, Navigate, Route, Routes, useNavigate } from 'react-router-dom';
import { QueryClient, QueryClientProvider, useQueryClient } from '@tanstack/react-query';
import { useQuery, localizedKey } from './localizedQuery';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Users, ShieldCheck, LogOut, KeyRound, Plus, Pencil, Search, RefreshCw, ChevronLeft, ChevronRight, UserRound, Check, Sun, Moon, Factory, Boxes, ListTree, Component, ClipboardList, Ruler, Wrench } from 'lucide-react';
import { Brand } from './Brand';
import { Auth, useAuth, can } from './access';
import { Busy, ErrorBox, IconButton, Modal, QueryError, Denied } from './ui';
import { ModelsPage, PartsPage, MachinesPage } from './CatalogPages';
import { BomsPage } from './BomsPage';
import { OrdersPage, OrderPage } from './OrdersPage';
import { WarehousePage, PreparationsPage } from './Warehouse';
import { api, ApiError, forgetSession, refreshSession, type User, type Role } from './api';
import './styles.css';

const RawStockPage = React.lazy(() => import('./RawStock').then(m => ({ default: m.RawStockPage })));
const DashboardPage = React.lazy(() => import('./Dashboard').then(m => ({ default: m.DashboardPage })));
const MachinePage = React.lazy(() => import('./Dashboard').then(m => ({ default: m.MachinePage })));
const TasksPage = React.lazy(() => import('./Assembly').then(m => ({ default: m.TasksPage })));
const MachineAssemblyPage = React.lazy(() => import('./Assembly').then(m => ({ default: m.MachineAssemblyPage })));
const DefectsPage = React.lazy(() => import('./Defects').then(m => ({ default: m.DefectsPage })));
const OperationsPage = React.lazy(() => import('./Operations').then(m => ({ default: m.OperationsPage })));
const OperationsOrderPage = React.lazy(() => import('./Operations').then(m => ({ default: m.OperationsOrderPage })));
const ShiftsPage = React.lazy(() => import('./Shifts').then(m => ({ default: m.ShiftsPage })));
const ReportsPage = React.lazy(() => import('./Reports').then(m => ({ default: m.ReportsPage })));
const TemplatesPage = React.lazy(() => import('./Templates').then(m => ({ default: m.TemplatesPage })));
const NotificationsPage = React.lazy(() => import('./notifications/NotificationsPage'));

const names: Record<string, string> = {
  'users.view': 'عرض المستخدمين', 'users.create': 'إنشاء مستخدم', 'users.update': 'تعديل بيانات المستخدم',
  'users.deactivate': 'تفعيل وتعطيل المستخدم', 'users.reset-password': 'إعادة تعيين كلمة المرور',
  'roles.view': 'عرض الأدوار', 'roles.create': 'إنشاء دور', 'roles.update': 'تعديل الأدوار', 'permissions.assign': 'تعيين الصلاحيات',
  'catalog.view': 'عرض الموديلات وكتالوج القطع', 'catalog.manage': 'إدارة الموديلات وكتالوج القطع',
  'machines.view': 'عرض الماكينات', 'machines.manage': 'إدارة الماكينات',
  'boms.view': 'عرض قوائم المكونات', 'boms.manage': 'إدارة ونشر قوائم المكونات',
  'orders.view': 'عرض جميع طلبات التصنيع', 'orders.view-assigned': 'عرض طلبات التصنيع المسندة لي',
  'orders.manage': 'إنشاء وتعديل مسودات التصنيع', 'orders.release': 'فتح طلبات التصنيع للعمل', 'orders.cancel': 'إلغاء طلبات التصنيع',
  'stock.view': 'عرض المخزن وحركاته', 'stock.receive': 'استلام القطع وإتاحتها بعد الفحص', 'stock.reserve': 'حجز القطع وطلب تجهيز النواقص',
  'stock.issue': 'صرف القطع', 'stock.return': 'استلام مرتجعات القطع', 'preparation.manage': 'بدء تجهيز نواقص التصنيع',
  'raw.receive': 'استلام الخامات المقاسة', 'raw.issue': 'صرف الخامات وإرجاعها دون قص', 'raw.cut': 'تسجيل القص والقطع الناتجة والبواقي',
  'tasks.view': 'عرض جميع مهام التجميع', 'tasks.view-assigned': 'عرض مهام التجميع المسندة لي', 'tasks.assign': 'تكليف العمال بمهام التجميع',
  'tasks.work': 'بدء وتركيب القطع في مهامي', 'tasks.uninstall': 'فك القطع المركبة حسب نطاق العرض',
  'tasks.reject': 'رفض القطع المعيبة في مهامي', 'defects.view': 'عرض جميع بلاغات العيوب', 'defects.manage': 'بدء وإنهاء إصلاح القطع', 'defects.scrap': 'إعدام القطع المعيبة',
  'operations.view': 'عرض تجارب التشغيل', 'operations.start': 'بدء تجارب التشغيل', 'operations.finish': 'تسجيل نتيجة وإيقاف التجربة',
  'operations.resolve': 'تسجيل معالجة فشل التجربة', 'operations.deliver': 'تسجيل التسليم الفعلي',
  'shifts.view': 'عرض الورديات', 'shifts.manage': 'إدارة أيام العمل والاستراحات', 'shifts.extend': 'تمديد الوردية اليومية',
  'time.manage': 'إيقاف وقت العامل مع تسجيل السبب', 'files.manage': 'رفع وحذف المرفقات ضمن نطاق العرض',
  'reports.view': 'عرض متابعة المصنع والتقارير', 'reports.export': 'تصدير وطباعة التقارير', 'operations.templates.manage': 'إدارة قوالب فحص التشغيل',
};
function ThemeToggle() {
  useLanguage();
  const [theme, setTheme] = useState(() => document.documentElement.dataset.theme === 'dark' ? 'dark' : 'light');
  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    try { localStorage.setItem('easypack-theme', theme); } catch { /* Storage may be blocked by browser policy. */ }
  }, [theme]);
  return <IconButton label={formatDisplay(theme === 'dark' ? 'الوضع الفاتح' : 'الوضع الداكن')} onClick={() => setTheme(theme === 'dark' ? 'light' : 'dark')}>
    {formatDisplay(theme === 'dark' ? <Sun size={19} /> : <Moon size={19} />)}
  </IconButton>;
}
const password = z.string().min(12, '12 حرفًا على الأقل').refine(v => new TextEncoder().encode(v).length <= 72, 'بحد أقصى 72 بايت');
function UsersPage() {
  useLanguage();
  const { user, reload } = useAuth(); const client = useQueryClient();
  const query = useQuery({ queryKey: ['users'], queryFn: () => api<User[]>('/users'), enabled: can(user, 'users.view') });
  const roles = useQuery({ queryKey: ['roles'], queryFn: () => api<Role[]>('/roles'), enabled: can(user, 'roles.view') || can(user, 'permissions.assign') });
  const [search, setSearch] = useState(''); const [status, setStatus] = useState('all'); const [page, setPage] = useState(0);
  const [editing, setEditing] = useState<User | 'new' | null>(null); const [reset, setReset] = useState<User | null>(null);
  const [notice, setNotice] = useState('');
  if (!can(user, 'users.view')) return <Denied />;
  const all = query.data ?? []; const filtered = all.filter(u => searchable(`${translateDisplay(u.displayName)} ${translateDisplay(u.email)}`).toLowerCase().includes(search.toLowerCase()) && (status === 'all' || u.active === (status === 'active')));
  const pages = Math.max(1, Math.ceil(filtered.length / 10)), current = Math.min(page, pages - 1);
  const saved = async () => { setEditing(null); setReset(null); setNotice('تم حفظ التغييرات'); await client.invalidateQueries({ queryKey: ['users'] }); await reload(); };
  return <><div className="page-heading"><div><div className="eyebrow">{translateUi("الإدارة")}</div><h1>{translateUi("المستخدمون")}<span className="count">{formatDisplay(all.length)}</span></h1></div>{formatDisplay(can(user, 'users.create') && <button className="primary" onClick={() => { setNotice(''); setEditing('new'); }}><Plus size={18} />{translateUi("مستخدم جديد")}</button>)}</div>
    {formatDisplay(notice && <div className="notice" role="status"><Check size={17} />{formatDisplay(notice)}</div>)}
    <div className="toolbar"><label className="search"><Search size={18} /><input aria-label={translateUi("بحث المستخدمين")} placeholder={translateUi("بحث بالاسم أو البريد")} value={search} onChange={e => { setSearch(e.target.value); setPage(0); }} /></label>
      <select aria-label={translateUi("حالة المستخدم")} value={status} onChange={e => { setStatus(e.target.value); setPage(0); }}><option value="all">{translateUi("كل الحالات")}</option><option value="active">{translateUi("نشط")}</option><option value="inactive">{translateUi("معطل")}</option></select>
      <IconButton label={translateUi("تحديث القائمة")} disabled={query.isFetching} onClick={() => void query.refetch()}><RefreshCw size={18} /></IconButton></div>
    {formatDisplay(query.isPending ? <Busy /> : query.error ? <QueryError error={query.error} retry={() => void query.refetch()} /> : <>
      <div className="table-wrap"><table><thead><tr><th>{translateUi("المستخدم")}</th><th>{translateUi("الأدوار")}</th><th>{translateUi("الحالة")}</th><th>{translateUi("كلمة المرور")}</th><th className="actions">{translateUi("الإجراءات")}</th></tr></thead><tbody>
        {formatDisplay(filtered.slice(current * 10, current * 10 + 10).map(u => <tr key={u.id}><td><strong>{formatDisplay(u.displayName)}</strong><div className="email" dir="ltr">{formatDisplay(u.email)}</div></td><td><div className="role-tags">{formatDisplay(u.roleIds.length ? u.roleIds.map(id => <span key={id}>{formatDisplay(roles.data?.find(r => r.id === id)?.name ?? 'دور مخصص')}</span>) : <span className="muted">{translateUi("بدون دور")}</span>)}</div></td><td><span className={`badge ${translateDisplay(u.active ? 'active' : 'inactive')}`}>{formatDisplay(u.active ? 'نشط' : 'معطل')}</span></td><td><span className="muted">{formatDisplay(u.mustChangePassword ? 'يلزم التغيير' : 'محدّثة')}</span></td><td><div className="row-actions">
          {formatDisplay(['users.update', 'users.deactivate', 'permissions.assign'].some(p => can(user, p)) && <IconButton label={formatDisplay(translateUi("تعديل {{v0}}", {v0: u.displayName}))} onClick={() => { setNotice(''); setEditing(u); }}><Pencil size={17} /></IconButton>)}
          {formatDisplay(can(user, 'users.reset-password') && <IconButton label={formatDisplay(translateUi("إعادة تعيين كلمة مرور {{v0}}", {v0: u.displayName}))} onClick={() => setReset(u)}><KeyRound size={17} /></IconButton>)}</div></td></tr>))}
      </tbody></table>{formatDisplay(filtered.length === 0 && <div className="state">{translateUi("لا توجد نتائج")}</div>)}</div>
      <div className="pagination"><span>{formatDisplay(filtered.length)} {translateUi("مستخدم")}</span><div><IconButton label={translateUi("الصفحة السابقة")} disabled={current === 0} onClick={() => setPage(current - 1)}><ChevronRight size={18} /></IconButton><span>{formatDisplay(current + 1)} / {formatDisplay(pages)}</span><IconButton label={translateUi("الصفحة التالية")} disabled={current === pages - 1} onClick={() => setPage(current + 1)}><ChevronLeft size={18} /></IconButton></div></div>
    </>)}
    {formatDisplay(editing && <Modal title={formatDisplay(editing === 'new' ? 'مستخدم جديد' : 'تعديل المستخدم')} close={() => setEditing(null)}><UserForm original={editing === 'new' ? undefined : editing} roles={roles.data} rolesError={roles.error} saved={saved} cancel={() => setEditing(null)} /></Modal>)}
    {formatDisplay(reset && <Modal title={formatDisplay(translateUi("إعادة تعيين كلمة المرور: {{v0}}", {v0: reset.displayName}))} close={() => setReset(null)}><ResetForm user={reset} saved={saved} /></Modal>)}
  </>;
}
const userSchema = z.object({ email: z.email('بريد إلكتروني غير صحيح'), displayName: z.string().trim().min(1, 'الاسم مطلوب').max(120), password: z.string(), active: z.boolean(), roleIds: z.array(z.string()), overrides: z.record(z.string(), z.boolean()) });
function UserForm({ original, roles, rolesError, saved, cancel }: { original?: User; roles?: Role[]; rolesError: unknown; saved: () => Promise<void>; cancel: () => void }) {
  useLanguage();
  const { user } = useAuth(); const [error, setError] = useState<unknown>(null);
  const assign = can(user, 'permissions.assign');
  const form = useForm({ resolver: zodResolver(userSchema), defaultValues: {
    email: original?.email ?? '', displayName: original?.displayName ?? '', password: '', active: original?.active ?? true,
    roleIds: original?.roleIds ?? [], overrides: original?.overrides ?? {},
  } });
  const values = form.watch();
  return <form className="form-stack" onSubmit={form.handleSubmit(async v => {
    setError(null);
    if (!original && !password.safeParse(v.password).success) { form.setError('password', { message: 'كلمة المرور 12 حرفًا على الأقل وبحد أقصى 72 بايت' }); return; }
    try { await api(`/users${translateDisplay(original ? `/${translateDisplay(original.id)}` : '')}`, original ? 'PUT' : 'POST', { ...v, version: original?.version ?? 0 }); await saved(); } catch (e) { setError(e); }
  })}><ErrorBox error={error} /><label>{translateUi("الاسم")}<input {...form.register('displayName')} readOnly={!!original && !can(user, 'users.update')} required /></label><small className="field-error">{formatDisplay(form.formState.errors.displayName?.message)}</small>
    <label>{translateUi("البريد الإلكتروني")}<input {...form.register('email')} type="email" dir="ltr" readOnly={!!original} required autoComplete="off" /></label><small className="field-error">{formatDisplay(form.formState.errors.email?.message)}</small>
    {formatDisplay(!original && <><PasswordInput label={translateUi("كلمة مرور مؤقتة")} {...form.register('password')} dir="ltr" autoComplete="new-password" required /><small className="field-error">{formatDisplay(form.formState.errors.password?.message)}</small></>)}
    <label className="check-line"><input type="checkbox" checked={values.active} onChange={e => form.setValue('active', e.target.checked)} disabled={!!original && !can(user, 'users.deactivate')} />{translateUi("حساب نشط")}</label>
    {formatDisplay(assign && <fieldset disabled={!roles}><legend>{translateUi("الأدوار")}</legend><ErrorBox error={rolesError} />{formatDisplay(!roles && !rolesError && <Busy />)}{formatDisplay(roles?.map(role => <label className="check-line" key={role.id}><input type="checkbox" value={role.id} {...form.register('roleIds')} />{formatDisplay(role.name)}</label>))}</fieldset>)}
    {formatDisplay(assign && <fieldset><legend>{translateUi("صلاحيات خاصة بالمستخدم")}</legend><div className="override-list">{formatDisplay(Object.entries(names).map(([p, label]) => <label key={p}><span>{formatDisplay(label)}</span><select aria-label={formatDisplay(label)} value={values.overrides[p] === undefined ? 'inherit' : values.overrides[p] ? 'allow' : 'deny'} onChange={e => {
      const overrides = { ...values.overrides }; if (e.target.value === 'inherit') delete overrides[p]; else overrides[p] = e.target.value === 'allow'; form.setValue('overrides', overrides);
    }}><option value="inherit">{translateUi("حسب الدور")}</option><option value="allow">{translateUi("سماح")}</option><option value="deny">{translateUi("منع")}</option></select></label>))}</div></fieldset>)}
    <div className="form-actions"><button className="primary" disabled={form.formState.isSubmitting || (assign && !roles)}><Check size={17} />{translateUi("حفظ")}</button><button type="button" onClick={cancel}>{translateUi("إلغاء")}</button></div>
  </form>;
}
function ResetForm({ user, saved }: { user: User; saved: () => Promise<void> }) {
  useLanguage();
  const [error, setError] = useState<unknown>(null);
  const form = useForm({ resolver: zodResolver(z.object({ password })), defaultValues: { password: '' } });
  return <form className="form-stack" onSubmit={form.handleSubmit(async v => { setError(null); try { await api(`/users/${translateDisplay(user.id)}/password`, 'POST', { ...v, version: user.version }); await saved(); } catch (e) { setError(e); } })}><ErrorBox error={error} /><PasswordInput label={translateUi("كلمة مرور مؤقتة")} dir="ltr" autoComplete="new-password" {...form.register('password')} required /><small className="field-error">{formatDisplay(form.formState.errors.password?.message)}</small><button className="primary" disabled={form.formState.isSubmitting}><KeyRound size={17} />{translateUi("إعادة التعيين")}</button></form>;
}
function RolesPage() {
  useLanguage();
  const { user, reload } = useAuth(); const client = useQueryClient();
  const query = useQuery({ queryKey: ['roles'], queryFn: () => api<Role[]>('/roles'), enabled: can(user, 'roles.view') });
  const [editing, setEditing] = useState<Role | 'new' | null>(null); const [notice, setNotice] = useState(false);
  if (!can(user, 'roles.view')) return <Denied />;
  return <><div className="page-heading"><div><div className="eyebrow">{translateUi("الإدارة")}</div><h1>{translateUi("الأدوار والصلاحيات")}</h1></div>{formatDisplay(can(user, 'roles.create') && can(user, 'permissions.assign') && <button className="primary" onClick={() => setEditing('new')}><Plus size={18} />{translateUi("دور جديد")}</button>)}</div>
    {formatDisplay(notice && <div className="notice" role="status"><Check size={17} />{translateUi("تم حفظ الدور")}</div>)}
    {formatDisplay(query.isPending ? <Busy /> : query.error ? <QueryError error={query.error} retry={() => void query.refetch()} /> : <div className="table-wrap"><table><thead><tr><th>{translateUi("الدور")}</th><th>{translateUi("الوصف")}</th><th>{translateUi("الصلاحيات")}</th><th className="actions">{translateUi("الإجراءات")}</th></tr></thead><tbody>{formatDisplay(query.data?.map(role => <tr key={role.id}><td><strong>{formatDisplay(role.name)}</strong></td><td>{formatDisplay(role.description)}</td><td><span className="count">{formatDisplay(role.permissions.length)}</span></td><td>{formatDisplay(can(user, 'roles.update') && can(user, 'permissions.assign') && <IconButton label={formatDisplay(translateUi("تعديل {{v0}}", {v0: role.name}))} onClick={() => { setNotice(false); setEditing(role); }}><Pencil size={17} /></IconButton>)}</td></tr>))}</tbody></table></div>)}
    {formatDisplay(editing && <Modal title={formatDisplay(editing === 'new' ? 'دور جديد' : 'تعديل الدور')} close={() => setEditing(null)}><RoleForm original={editing === 'new' ? undefined : editing} saved={async () => { setEditing(null); setNotice(true); await client.invalidateQueries({ queryKey: ['roles'] }); await client.invalidateQueries({ queryKey: ['users'] }); await reload(); }} /></Modal>)}
  </>;
}
function RoleForm({ original, saved }: { original?: Role; saved: () => Promise<void> }) {
  useLanguage();
  const [error, setError] = useState<unknown>(null);
  const schema = z.object({ name: z.string().trim().min(1, 'الاسم مطلوب').max(80), description: z.string().max(240), permissions: z.array(z.string()) });
  const form = useForm({ resolver: zodResolver(schema), defaultValues: { name: original?.name ?? '', description: original?.description ?? '', permissions: original?.permissions ?? [] } });
  return <form className="form-stack" onSubmit={form.handleSubmit(async v => { setError(null); try { await api(`/roles${translateDisplay(original ? `/${translateDisplay(original.id)}` : '')}`, original ? 'PUT' : 'POST', { ...v, version: original?.version ?? 0 }); await saved(); } catch (e) { setError(e); } })}><ErrorBox error={error} />
    <label>{translateUi("اسم الدور")}<input {...form.register('name')} maxLength={80} required /></label><small className="field-error">{formatDisplay(form.formState.errors.name?.message)}</small><label>{translateUi("الوصف")}<textarea {...form.register('description')} maxLength={240} rows={2} /></label>
    <fieldset><legend>{translateUi("الصلاحيات")}</legend><div className="role-permissions" tabIndex={0} aria-label={translateUi("قائمة صلاحيات الدور")}>{formatDisplay(Object.entries(names).map(([p, label]) => <label className="check-line" key={p}><input type="checkbox" value={p} {...form.register('permissions')} />{formatDisplay(label)}</label>))}</div></fieldset>
    <button className="primary" disabled={form.formState.isSubmitting}><Check size={17} />{translateUi("حفظ")}</button></form>;
}
function Shell() {
  useLanguage();
  const { user, exit } = useAuth();
  return <div className="app-shell"><aside><Brand /><div className="nav-label">{translateUi("مساحة العمل")}</div><nav><NavLink to="/" end><Factory size={19} />{translateUi("لوحة المتابعة")}</NavLink>
    {formatDisplay(can(user, 'catalog.view') && <><NavLink to="/machine-models"><Component size={19} />{translateUi("موديلات الماكينات")}</NavLink><NavLink to="/parts"><Boxes size={19} />{translateUi("كتالوج القطع")}</NavLink></>)}
    {formatDisplay(can(user, 'machines.view') && <NavLink to="/machines"><Factory size={19} />{translateUi("الماكينات")}</NavLink>)}
    {formatDisplay(can(user, 'catalog.view') && can(user, 'boms.view') && <NavLink to="/boms"><ListTree size={19} />{translateUi("قوائم المكونات")}</NavLink>)}
    {formatDisplay((can(user, 'orders.view') || can(user, 'orders.view-assigned')) && <NavLink to="/orders"><Factory size={19} />{translateUi("طلبات التصنيع")}</NavLink>)}
    {formatDisplay(can(user, 'stock.view') && <NavLink to="/warehouse"><Boxes size={19} />{translateUi("المخزن")}</NavLink>)}
    {formatDisplay(can(user, 'stock.view') && <NavLink to="/raw-stock"><Ruler size={19} />{translateUi("الخامات والقص")}</NavLink>)}
    {formatDisplay(((can(user, 'tasks.view') && can(user, 'orders.view')) || can(user, 'tasks.view-assigned')) && <NavLink to="/tasks"><ListTree size={19} />{formatDisplay(can(user, 'tasks.view') && can(user, 'orders.view') ? 'مهام التجميع' : 'مهامي')}</NavLink>)}
    {formatDisplay(((can(user, 'defects.view') && can(user, 'orders.view')) || (can(user, 'tasks.view') && can(user, 'orders.view')) || can(user, 'tasks.view-assigned')) && <NavLink to="/defects"><Wrench size={19} />{formatDisplay(can(user, 'defects.view') && can(user, 'orders.view') ? 'العيوب والإصلاح' : 'بلاغاتي')}</NavLink>)}
    {formatDisplay(can(user, 'orders.view') && (can(user, 'stock.view') || can(user, 'preparation.manage')) && <NavLink to="/preparations"><ClipboardList size={19} />{translateUi("نواقص وتجهيز القطع")}</NavLink>)}
    {formatDisplay(can(user, 'operations.view') && <NavLink to="/operations"><Check size={19} />{translateUi("تجارب التشغيل")}</NavLink>)}
    {formatDisplay(can(user, 'shifts.view') && <NavLink to="/shifts"><ClipboardList size={19} />{translateUi("الورديات والاستراحات")}</NavLink>)}
    {formatDisplay(can(user, 'reports.view') && <NavLink to="/reports"><ListTree size={19} />{translateUi("متابعة المصنع والتقارير")}</NavLink>)}
    {formatDisplay(can(user, 'users.view') && <NavLink to="/users"><Users size={19} />{translateUi("المستخدمون")}</NavLink>)}{formatDisplay(can(user, 'roles.view') && <NavLink to="/roles"><ShieldCheck size={19} />{translateUi("الأدوار والصلاحيات")}</NavLink>)}</nav><div className="sidebar-bottom"><span>{translateUi("إدارة المصنع")}</span><small dir="ltr">EPE · EasyPack</small></div></aside>
    <div className="main-column"><header className="topbar"><span className="demo-label">{translateUi("نسخة تجريبية · EasyPack Demo")}</span><div><span className="account-name">{formatDisplay(user.displayName)}</span><NotificationBell /><LanguageToggle /><ThemeToggle /></div></header><main className="workspace"><div className="demo-banner" role="note"><strong>{translateUi("أهلًا بك في تجربة EasyPack")}</strong><span>{translateUi("استكشف المصنع وجرّب العمليات مباشرة. كل البيانات وهمية، والتعديلات مشتركة بين زوار الديمو.")}</span></div><p className="translation-note">{getDirection() === 'ltr' ? 'Record text without a reviewed translation is shown in its original language. Edit forms preserve original text.' : 'يظهر النص الذي لم تُراجع ترجمته بلغته الأصلية. تحتفظ نماذج التعديل بالنص الأصلي.'}</p><Routes><Route path="/notifications" element={<React.Suspense fallback={<Busy />}><NotificationsPage /></React.Suspense>} /><Route path="/" element={<React.Suspense fallback={<Busy />}><DashboardPage /></React.Suspense>} /><Route path="/users" element={<UsersPage />} /><Route path="/roles" element={<RolesPage />} /><Route path="/machine-models" element={<ModelsPage />} /><Route path="/parts" element={<PartsPage />} /><Route path="/machines" element={<MachinesPage />} /><Route path="/machines/:id" element={<React.Suspense fallback={<Busy />}><MachinePage /></React.Suspense>} /><Route path="/machines/:id/assembly" element={<React.Suspense fallback={<Busy />}><MachineAssemblyPage /></React.Suspense>} /><Route path="/boms" element={<BomsPage />} /><Route path="/orders" element={<OrdersPage />} /><Route path="/orders/:id" element={<OrderPage />} /><Route path="/warehouse" element={<WarehousePage />} /><Route path="/preparations" element={<PreparationsPage />} /><Route path="/raw-stock" element={<React.Suspense fallback={<Busy />}><RawStockPage /></React.Suspense>} /><Route path="/tasks" element={<React.Suspense fallback={<Busy />}><TasksPage /></React.Suspense>} /><Route path="/defects" element={<React.Suspense fallback={<Busy />}><DefectsPage /></React.Suspense>} /><Route path="/operations" element={<React.Suspense fallback={<Busy />}><OperationsPage /></React.Suspense>} /><Route path="/operations/templates" element={<React.Suspense fallback={<Busy />}><TemplatesPage /></React.Suspense>} /><Route path="/operations/:id" element={<React.Suspense fallback={<Busy />}><OperationsOrderPage /></React.Suspense>} /><Route path="/shifts" element={<React.Suspense fallback={<Busy />}><ShiftsPage /></React.Suspense>} /><Route path="/reports" element={<React.Suspense fallback={<Busy />}><ReportsPage /></React.Suspense>} /><Route path="*" element={<Navigate to="/" replace />} /></Routes></main><footer className="app-footer"><span dir="ltr">EPE · EasyPack</span><span>{translateUi("إدارة المصنع")}</span></footer></div>
  </div>;
}
function Application() {
  useLanguage();
  const [user, setUser] = useState<User | null>(null); const [loading, setLoading] = useState(true);
  const [bootError, setBootError] = useState<unknown>(null); const client = useQueryClient(); const navigate = useNavigate();
  const clear = () => { forgetSession(); setUser(null); client.clear(); navigate('/', { replace: true }); };
  const reload = async () => { setUser(await api<User>('/auth/me')); };
  const boot = async () => {
    setLoading(true); setBootError(null);
    try { setUser((await refreshSession()).user); }
    catch (e) { setBootError(e); }
    finally { setLoading(false); }
  };
  useEffect(() => { void boot(); const listener = () => { clear(); void boot(); }; window.addEventListener('session-ended', listener); return () => window.removeEventListener('session-ended', listener); }, []);
  useEffect(() => {
    if (!user) return;
    const refresh = () => { if (document.visibilityState === 'visible') void reload().catch(() => {}); };
    const timer = window.setInterval(refresh, 15000); window.addEventListener('focus', refresh);
    return () => { clearInterval(timer); window.removeEventListener('focus', refresh); };
  }, [user?.id]);
  if (loading) return <><LanguageToggle /><Busy /></>;
  if (bootError) return <><LanguageToggle /><QueryError error={bootError} retry={() => void boot()} /></>;
  if (!user) return <><LanguageToggle /><QueryError error={new ApiError(503, 'DEMO_UNAVAILABLE')} retry={() => void boot()} /></>;
  const exit = async () => { clear(); await boot(); };
  return <Auth.Provider value={{ user, reload, exit }}><NotificationProvider><Shell /></NotificationProvider></Auth.Provider>;
}
const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: 10000 } } });
createRoot(document.getElementById('root')!).render(<React.StrictMode><QueryClientProvider client={client}><BrowserRouter><Application /></BrowserRouter></QueryClientProvider></React.StrictMode>);
