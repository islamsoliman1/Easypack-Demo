import { Link } from 'react-router-dom';
import { useQuery } from './localizedQuery';
import { api } from './api';
import { can, useAuth } from './access';
import { translateUi as t, formatDisplay as f, useLanguage } from './i18n';
import { Busy, QueryError } from './ui';

type Overview = { metrics: Record<string, number>; states: { status: string; count: number }[] };
const states: Record<string, string> = { DRAFT: 'مسودة', OPEN: 'مفتوح', IN_PROGRESS: 'قيد التجميع', AWAITING_TESTING: 'بانتظار تجربة التشغيل', TESTING: 'قيد تجربة التشغيل', REWORK: 'معالجة بعد التجربة', COMPLETED: 'مكتمل', CANCELLED: 'ملغي' };
const metrics = [
  ['machines', 'ماكينات نشطة'], ['orders', 'إجمالي الطلبات'], ['active', 'طلبات قيد التنفيذ'], ['completed', 'طلبات مكتملة'],
  ['delivered', 'تم التسليم'], ['late', 'تسليمات متأخرة'], ['missing', 'قطع ناقصة للطلبات الجارية'], ['defects', 'عيوب مفتوحة'],
  ['available', 'قطع متاحة بالمخزن'], ['quarantined', 'قطع تحت الفحص'], ['running', 'جلسات عمل جارية'], ['unassigned', 'مهام بدون عامل'],
];

// Shared main-project metrics embedded in the demo's existing machine dashboard.
export function FactoryDashboardStats() {
  useLanguage();
  const { user } = useAuth();
  const allowed = can(user, 'reports.view');
  const stats = useQuery({ queryKey: ['factory-dashboard'], queryFn: () => api<Overview>('/factory-dashboard'), enabled: allowed, refetchInterval: 15000 });
  if (!allowed) return null;
  if (stats.isPending) return <Busy />;
  if (stats.error) return <QueryError error={stats.error} retry={() => void stats.refetch()} />;
  const values = stats.data!.metrics;
  return <section aria-label={t('لوحة إدارة المصنع')}>
    <p className="muted">{t('تحديث تلقائي كل 15 ثانية · الإحصائيات تشمل كل الفترات.')}</p>
      <dl className="factory-metrics">{metrics.map(([key, label]) => <div key={key} className={['late', 'missing', 'defects'].includes(key) && values[key] > 0 ? 'metric-alert' : ''}><dt>{t(label)}</dt><dd>{f(values[key])}</dd></div>)}</dl>
      <div className="factory-overview"><section><h2>{t('توزيع حالات الطلبات')}</h2>{stats.data?.states.length ? stats.data.states.map(s => <div className="factory-state" key={s.status}><span>{t(states[s.status] ?? s.status)}</span><progress aria-label={t(states[s.status] ?? s.status)} max={Math.max(1, values.orders)} value={s.count} /><b>{f(s.count)}</b></div>) : <p className="muted">{t('لا توجد طلبات تصنيع بعد.')}</p>}</section>
        <section><h2>{t('تجميع الطلبات الجارية')}</h2><strong className="factory-percent">{f(values.required ? Math.round(values.installed / values.required * 100) : 0)}%</strong><progress aria-label={t('تجميع الطلبات الجارية')} max={Math.max(1, values.required)} value={values.installed} /><p className="muted">{t('{{v0}} من {{v1}} قطعة مركبة', { v0: values.installed, v1: values.required })}</p>{can(user, 'reports.view') && <Link className="inline-link" to="/reports">{t('متابعة المصنع والتقارير')}</Link>}</section></div>

  </section>;
}
