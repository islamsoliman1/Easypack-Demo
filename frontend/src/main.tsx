import { NotificationProvider } from './notifications/useNotifications';
import { NotificationBell } from './notifications/NotificationBell';
import React, { useEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter, NavLink, Navigate, Route, Routes, useNavigate } from 'react-router-dom';
import { QueryClient, QueryClientProvider, useQuery, useQueryClient } from '@tanstack/react-query';
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
const NotificationsPage = React.lazy(() => import('./notifications/NotificationsPage'));

const RawStockPage = React.lazy(() => import('./RawStock').then(m => ({ default: m.RawStockPage })));
const TasksPage = React.lazy(() => import('./Assembly').then(m => ({ default: m.TasksPage })));
const MachineAssemblyPage = React.lazy(() => import('./Assembly').then(m => ({ default: m.MachineAssemblyPage })));
const DefectsPage = React.lazy(() => import('./Defects').then(m => ({ default: m.DefectsPage })));
const OperationsPage = React.lazy(() => import('./Operations').then(m => ({ default: m.OperationsPage })));
const OperationsOrderPage = React.lazy(() => import('./Operations').then(m => ({ default: m.OperationsOrderPage })));
const ShiftsPage = React.lazy(() => import('./Shifts').then(m => ({ default: m.ShiftsPage })));
const ReportsPage = React.lazy(() => import('./Reports').then(m => ({ default: m.ReportsPage })));
const TemplatesPage = React.lazy(() => import('./Templates').then(m => ({ default: m.TemplatesPage })));

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
  const [theme, setTheme] = useState(() => document.documentElement.dataset.theme === 'dark' ? 'dark' : 'light');
  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    try { localStorage.setItem('easypack-theme', theme); } catch { /* Storage may be blocked by browser policy. */ }
  }, [theme]);
  return <IconButton label={theme === 'dark' ? 'الوضع الفاتح' : 'الوضع الداكن'} onClick={() => setTheme(theme === 'dark' ? 'light' : 'dark')}>
    {theme === 'dark' ? <Sun size={19} /> : <Moon size={19} />}
  </IconButton>;
}
const password = z.string().min(12, '12 حرفًا على الأقل').refine(v => new TextEncoder().encode(v).length <= 72, 'بحد أقصى 72 بايت');
function UsersPage() {
  const { user, reload } = useAuth(); const client = useQueryClient();
  const query = useQuery({ queryKey: ['users'], queryFn: () => api<User[]>('/users'), enabled: can(user, 'users.view') });
  const roles = useQuery({ queryKey: ['roles'], queryFn: () => api<Role[]>('/roles'), enabled: can(user, 'roles.view') || can(user, 'permissions.assign') });
  const [search, setSearch] = useState(''); const [status, setStatus] = useState('all'); const [page, setPage] = useState(0);
  const [editing, setEditing] = useState<User | 'new' | null>(null); const [reset, setReset] = useState<User | null>(null);
  const [notice, setNotice] = useState('');
  if (!can(user, 'users.view')) return <Denied />;
  const all = query.data ?? []; const filtered = all.filter(u => `${u.displayName} ${u.email}`.toLowerCase().includes(search.toLowerCase()) && (status === 'all' || u.active === (status === 'active')));
  const pages = Math.max(1, Math.ceil(filtered.length / 10)), current = Math.min(page, pages - 1);
  const saved = async () => { setEditing(null); setReset(null); setNotice('تم حفظ التغييرات'); await client.invalidateQueries({ queryKey: ['users'] }); await reload(); };
  return <><div className="page-heading"><div><div className="eyebrow">الإدارة</div><h1>المستخدمون <span className="count">{all.length}</span></h1></div>{can(user, 'users.create') && <button className="primary" onClick={() => { setNotice(''); setEditing('new'); }}><Plus size={18} />مستخدم جديد</button>}</div>
    {notice && <div className="notice" role="status"><Check size={17} />{notice}</div>}
    <div className="toolbar"><label className="search"><Search size={18} /><input aria-label="بحث المستخدمين" placeholder="بحث بالاسم أو البريد" value={search} onChange={e => { setSearch(e.target.value); setPage(0); }} /></label>
      <select aria-label="حالة المستخدم" value={status} onChange={e => { setStatus(e.target.value); setPage(0); }}><option value="all">كل الحالات</option><option value="active">نشط</option><option value="inactive">معطل</option></select>
      <IconButton label="تحديث القائمة" disabled={query.isFetching} onClick={() => void query.refetch()}><RefreshCw size={18} /></IconButton></div>
    {query.isPending ? <Busy /> : query.error ? <QueryError error={query.error} retry={() => void query.refetch()} /> : <>
      <div className="table-wrap"><table><thead><tr><th>المستخدم</th><th>الأدوار</th><th>الحالة</th><th>كلمة المرور</th><th className="actions">الإجراءات</th></tr></thead><tbody>
        {filtered.slice(current * 10, current * 10 + 10).map(u => <tr key={u.id}><td><strong>{u.displayName}</strong><div className="email" dir="ltr">{u.email}</div></td><td><div className="role-tags">{u.roleIds.length ? u.roleIds.map(id => <span key={id}>{roles.data?.find(r => r.id === id)?.name ?? 'دور مخصص'}</span>) : <span className="muted">بدون دور</span>}</div></td><td><span className={`badge ${u.active ? 'active' : 'inactive'}`}>{u.active ? 'نشط' : 'معطل'}</span></td><td><span className="muted">{u.mustChangePassword ? 'يلزم التغيير' : 'محدّثة'}</span></td><td><div className="row-actions">
          {['users.update', 'users.deactivate', 'permissions.assign'].some(p => can(user, p)) && <IconButton label={`تعديل ${u.displayName}`} onClick={() => { setNotice(''); setEditing(u); }}><Pencil size={17} /></IconButton>}
          {can(user, 'users.reset-password') && <IconButton label={`إعادة تعيين كلمة مرور ${u.displayName}`} onClick={() => setReset(u)}><KeyRound size={17} /></IconButton>}</div></td></tr>)}
      </tbody></table>{filtered.length === 0 && <div className="state">لا توجد نتائج</div>}</div>
      <div className="pagination"><span>{filtered.length} مستخدم</span><div><IconButton label="الصفحة السابقة" disabled={current === 0} onClick={() => setPage(current - 1)}><ChevronRight size={18} /></IconButton><span>{current + 1} / {pages}</span><IconButton label="الصفحة التالية" disabled={current === pages - 1} onClick={() => setPage(current + 1)}><ChevronLeft size={18} /></IconButton></div></div>
    </>}
    {editing && <Modal title={editing === 'new' ? 'مستخدم جديد' : 'تعديل المستخدم'} close={() => setEditing(null)}><UserForm original={editing === 'new' ? undefined : editing} roles={roles.data} rolesError={roles.error} saved={saved} cancel={() => setEditing(null)} /></Modal>}
    {reset && <Modal title={`إعادة تعيين كلمة المرور: ${reset.displayName}`} close={() => setReset(null)}><ResetForm user={reset} saved={saved} /></Modal>}
  </>;
}
const userSchema = z.object({ email: z.email('بريد إلكتروني غير صحيح'), displayName: z.string().trim().min(1, 'الاسم مطلوب').max(120), password: z.string(), active: z.boolean(), roleIds: z.array(z.string()), overrides: z.record(z.string(), z.boolean()) });
function UserForm({ original, roles, rolesError, saved, cancel }: { original?: User; roles?: Role[]; rolesError: unknown; saved: () => Promise<void>; cancel: () => void }) {
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
    try { await api(`/users${original ? `/${original.id}` : ''}`, original ? 'PUT' : 'POST', { ...v, version: original?.version ?? 0 }); await saved(); } catch (e) { setError(e); }
  })}><ErrorBox error={error} /><label>الاسم<input {...form.register('displayName')} readOnly={!!original && !can(user, 'users.update')} required /></label><small className="field-error">{form.formState.errors.displayName?.message}</small>
    <label>البريد الإلكتروني<input {...form.register('email')} type="email" dir="ltr" readOnly={!!original} required autoComplete="off" /></label><small className="field-error">{form.formState.errors.email?.message}</small>
    {!original && <><label>كلمة مرور مؤقتة<input {...form.register('password')} type="password" dir="ltr" autoComplete="new-password" required /></label><small className="field-error">{form.formState.errors.password?.message}</small></>}
    <label className="check-line"><input type="checkbox" checked={values.active} onChange={e => form.setValue('active', e.target.checked)} disabled={!!original && !can(user, 'users.deactivate')} />حساب نشط</label>
    {assign && <fieldset disabled={!roles}><legend>الأدوار</legend><ErrorBox error={rolesError} />{!roles && !rolesError && <Busy />}{roles?.map(role => <label className="check-line" key={role.id}><input type="checkbox" value={role.id} {...form.register('roleIds')} />{role.name}</label>)}</fieldset>}
    {assign && <fieldset><legend>صلاحيات خاصة بالمستخدم</legend><div className="override-list">{Object.entries(names).map(([p, label]) => <label key={p}><span>{label}</span><select aria-label={label} value={values.overrides[p] === undefined ? 'inherit' : values.overrides[p] ? 'allow' : 'deny'} onChange={e => {
      const overrides = { ...values.overrides }; if (e.target.value === 'inherit') delete overrides[p]; else overrides[p] = e.target.value === 'allow'; form.setValue('overrides', overrides);
    }}><option value="inherit">حسب الدور</option><option value="allow">سماح</option><option value="deny">منع</option></select></label>)}</div></fieldset>}
    <div className="form-actions"><button className="primary" disabled={form.formState.isSubmitting || (assign && !roles)}><Check size={17} />حفظ</button><button type="button" onClick={cancel}>إلغاء</button></div>
  </form>;
}
function ResetForm({ user, saved }: { user: User; saved: () => Promise<void> }) {
  const [error, setError] = useState<unknown>(null);
  const form = useForm({ resolver: zodResolver(z.object({ password })), defaultValues: { password: '' } });
  return <form className="form-stack" onSubmit={form.handleSubmit(async v => { setError(null); try { await api(`/users/${user.id}/password`, 'POST', { ...v, version: user.version }); await saved(); } catch (e) { setError(e); } })}><ErrorBox error={error} /><label>كلمة مرور مؤقتة<input type="password" dir="ltr" autoComplete="new-password" {...form.register('password')} required /></label><small className="field-error">{form.formState.errors.password?.message}</small><button className="primary" disabled={form.formState.isSubmitting}><KeyRound size={17} />إعادة التعيين</button></form>;
}
function RolesPage() {
  const { user, reload } = useAuth(); const client = useQueryClient();
  const query = useQuery({ queryKey: ['roles'], queryFn: () => api<Role[]>('/roles'), enabled: can(user, 'roles.view') });
  const [editing, setEditing] = useState<Role | 'new' | null>(null); const [notice, setNotice] = useState(false);
  if (!can(user, 'roles.view')) return <Denied />;
  return <><div className="page-heading"><div><div className="eyebrow">الإدارة</div><h1>الأدوار والصلاحيات</h1></div>{can(user, 'roles.create') && can(user, 'permissions.assign') && <button className="primary" onClick={() => setEditing('new')}><Plus size={18} />دور جديد</button>}</div>
    {notice && <div className="notice" role="status"><Check size={17} />تم حفظ الدور</div>}
    {query.isPending ? <Busy /> : query.error ? <QueryError error={query.error} retry={() => void query.refetch()} /> : <div className="table-wrap"><table><thead><tr><th>الدور</th><th>الوصف</th><th>الصلاحيات</th><th className="actions">الإجراءات</th></tr></thead><tbody>{query.data?.map(role => <tr key={role.id}><td><strong>{role.name}</strong></td><td>{role.description}</td><td><span className="count">{role.permissions.length}</span></td><td>{can(user, 'roles.update') && can(user, 'permissions.assign') && <IconButton label={`تعديل ${role.name}`} onClick={() => { setNotice(false); setEditing(role); }}><Pencil size={17} /></IconButton>}</td></tr>)}</tbody></table></div>}
    {editing && <Modal title={editing === 'new' ? 'دور جديد' : 'تعديل الدور'} close={() => setEditing(null)}><RoleForm original={editing === 'new' ? undefined : editing} saved={async () => { setEditing(null); setNotice(true); await client.invalidateQueries({ queryKey: ['roles'] }); await client.invalidateQueries({ queryKey: ['users'] }); await reload(); }} /></Modal>}
  </>;
}
function RoleForm({ original, saved }: { original?: Role; saved: () => Promise<void> }) {
  const [error, setError] = useState<unknown>(null);
  const schema = z.object({ name: z.string().trim().min(1, 'الاسم مطلوب').max(80), description: z.string().max(240), permissions: z.array(z.string()) });
  const form = useForm({ resolver: zodResolver(schema), defaultValues: { name: original?.name ?? '', description: original?.description ?? '', permissions: original?.permissions ?? [] } });
  return <form className="form-stack" onSubmit={form.handleSubmit(async v => { setError(null); try { await api(`/roles${original ? `/${original.id}` : ''}`, original ? 'PUT' : 'POST', { ...v, version: original?.version ?? 0 }); await saved(); } catch (e) { setError(e); } })}><ErrorBox error={error} />
    <label>اسم الدور<input {...form.register('name')} maxLength={80} required /></label><small className="field-error">{form.formState.errors.name?.message}</small><label>الوصف<textarea {...form.register('description')} maxLength={240} rows={2} /></label>
    <fieldset><legend>الصلاحيات</legend><div className="role-permissions" tabIndex={0} aria-label="قائمة صلاحيات الدور">{Object.entries(names).map(([p, label]) => <label className="check-line" key={p}><input type="checkbox" value={p} {...form.register('permissions')} />{label}</label>)}</div></fieldset>
    <button className="primary" disabled={form.formState.isSubmitting}><Check size={17} />حفظ</button></form>;
}
function Shell() {
  const { user, exit } = useAuth();
  return <div className="app-shell"><aside><Brand /><div className="nav-label">مساحة العمل</div><nav><NavLink to="/" end><Factory size={19} />لوحة المتابعة</NavLink>
    {can(user, 'catalog.view') && <><NavLink to="/machine-models"><Component size={19} />موديلات الماكينات</NavLink><NavLink to="/parts"><Boxes size={19} />كتالوج القطع</NavLink></>}
    {can(user, 'machines.view') && <NavLink to="/machines"><Factory size={19} />الماكينات</NavLink>}
    {can(user, 'catalog.view') && can(user, 'boms.view') && <NavLink to="/boms"><ListTree size={19} />قوائم المكونات</NavLink>}
    {(can(user, 'orders.view') || can(user, 'orders.view-assigned')) && <NavLink to="/orders"><Factory size={19} />طلبات التصنيع</NavLink>}
    {can(user, 'stock.view') && <NavLink to="/warehouse"><Boxes size={19} />المخزن</NavLink>}
    {can(user, 'stock.view') && <NavLink to="/raw-stock"><Ruler size={19} />الخامات والقص</NavLink>}
    {((can(user, 'tasks.view') && can(user, 'orders.view')) || can(user, 'tasks.view-assigned')) && <NavLink to="/tasks"><ListTree size={19} />{can(user, 'tasks.view') && can(user, 'orders.view') ? 'مهام التجميع' : 'مهامي'}</NavLink>}
    {((can(user, 'defects.view') && can(user, 'orders.view')) || (can(user, 'tasks.view') && can(user, 'orders.view')) || can(user, 'tasks.view-assigned')) && <NavLink to="/defects"><Wrench size={19} />{can(user, 'defects.view') && can(user, 'orders.view') ? 'العيوب والإصلاح' : 'بلاغاتي'}</NavLink>}
    {can(user, 'orders.view') && (can(user, 'stock.view') || can(user, 'preparation.manage')) && <NavLink to="/preparations"><ClipboardList size={19} />نواقص وتجهيز القطع</NavLink>}
    {can(user, 'operations.view') && <NavLink to="/operations"><Check size={19} />تجارب التشغيل</NavLink>}
    {can(user, 'shifts.view') && <NavLink to="/shifts"><ClipboardList size={19} />الورديات والاستراحات</NavLink>}
    {can(user, 'reports.view') && <NavLink to="/reports"><ListTree size={19} />متابعة المصنع والتقارير</NavLink>}
    {can(user, 'users.view') && <NavLink to="/users"><Users size={19} />المستخدمون</NavLink>}{can(user, 'roles.view') && <NavLink to="/roles"><ShieldCheck size={19} />الأدوار والصلاحيات</NavLink>}</nav><div className="sidebar-bottom"><span>إدارة المصنع</span><small dir="ltr">EPE · EasyPack</small></div></aside>
    <div className="main-column"><header className="topbar"><span className="demo-label">نسخة تجريبية · EasyPack Demo</span><div><span className="account-name">{user.displayName}</span><NotificationBell /><ThemeToggle /></div></header><main className="workspace"><div className="demo-banner" role="note"><strong>أهلًا بك في تجربة EasyPack</strong><span>استكشف المصنع وجرّب العمليات مباشرة. كل البيانات وهمية، والتعديلات مشتركة بين زوار الديمو.</span></div><Routes><Route path="/notifications" element={<React.Suspense fallback={<Busy />}><NotificationsPage /></React.Suspense>} /><Route path="/" element={<React.Suspense fallback={<Busy />}><ReportsPage /></React.Suspense>} /><Route path="/users" element={<UsersPage />} /><Route path="/roles" element={<RolesPage />} /><Route path="/machine-models" element={<ModelsPage />} /><Route path="/parts" element={<PartsPage />} /><Route path="/machines" element={<MachinesPage />} /><Route path="/machines/:id/assembly" element={<React.Suspense fallback={<Busy />}><MachineAssemblyPage /></React.Suspense>} /><Route path="/boms" element={<BomsPage />} /><Route path="/orders" element={<OrdersPage />} /><Route path="/orders/:id" element={<OrderPage />} /><Route path="/warehouse" element={<WarehousePage />} /><Route path="/preparations" element={<PreparationsPage />} /><Route path="/raw-stock" element={<React.Suspense fallback={<Busy />}><RawStockPage /></React.Suspense>} /><Route path="/tasks" element={<React.Suspense fallback={<Busy />}><TasksPage /></React.Suspense>} /><Route path="/defects" element={<React.Suspense fallback={<Busy />}><DefectsPage /></React.Suspense>} /><Route path="/operations" element={<React.Suspense fallback={<Busy />}><OperationsPage /></React.Suspense>} /><Route path="/operations/templates" element={<React.Suspense fallback={<Busy />}><TemplatesPage /></React.Suspense>} /><Route path="/operations/:id" element={<React.Suspense fallback={<Busy />}><OperationsOrderPage /></React.Suspense>} /><Route path="/shifts" element={<React.Suspense fallback={<Busy />}><ShiftsPage /></React.Suspense>} /><Route path="/reports" element={<React.Suspense fallback={<Busy />}><ReportsPage /></React.Suspense>} /><Route path="*" element={<Navigate to="/" replace />} /></Routes></main><footer className="app-footer"><span dir="ltr">EPE · EasyPack</span><span>إدارة المصنع</span></footer></div>
  </div>;
}
function Application() {
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
  if (loading) return <Busy />;
  if (bootError) return <QueryError error={bootError} retry={() => void boot()} />;
  if (!user) return <QueryError error={new ApiError(503, 'DEMO_UNAVAILABLE')} retry={() => void boot()} />;
  const exit = async () => { clear(); await boot(); };
  return <Auth.Provider value={{ user, reload, exit }}><NotificationProvider><Shell /></NotificationProvider></Auth.Provider>;
}
const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: 10000 } } });
createRoot(document.getElementById('root')!).render(<React.StrictMode><QueryClientProvider client={client}><BrowserRouter><Application /></BrowserRouter></QueryClientProvider></React.StrictMode>);
