import React from 'react';
import { motion } from 'motion/react';
import { PieChart, Pie, Cell, ResponsiveContainer, Tooltip } from 'recharts';
import {
  ArrowUpRight, Users, AlertCircle, Clock,
  TrendingUp, TrendingDown, ChevronRight,
  Briefcase, Activity, Target,
} from 'lucide-react';

/* ── Props ──────────────────────────────────────────────────────────────── */
interface DashboardProps {
  onNavigate: (view: string) => void;
  userData?: any;
}

export default function Dashboard({ onNavigate, userData }: DashboardProps) {
  const [metrics, setMetrics] = React.useState({
    totalAum: 0,
    investorCount: 0,
    sipAmount: 0,
    sipCount: 0,
    failedSips: 0,
    todayOrders: 0,
    aumSub: [] as any[],
    pendingSub: [] as any[],
    leadSub: [] as any[],
    donutData: [] as any[],
    recentActivity: [] as any[],
    onboarding: { kycPending: [] as any[], bankPending: [] as any[], readyToInvest: [] as any[] }
  });

  React.useEffect(() => {
    if (!userData?.id) return;
    const token = userData.token || sessionStorage.getItem('token') || '';
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    if (token) headers['Authorization'] = `Bearer ${token}`;

    const fetchDashboard = async () => {
      try {
        const [ordersRes, investorsRes, schemesRes, leadsRes, onboardingRes] = await Promise.all([
          fetch(`http://localhost:8081/api/v1/orders/by-distributor/${userData.id}`, { headers }),
          fetch(`http://localhost:8081/api/v1/investors/by-distributor/${userData.id}`, { headers }),
          fetch(`http://localhost:8081/api/v1/products/schemes`, { headers }),
          fetch(`http://localhost:8081/api/v1/leads/distributor/${userData.id}`, { headers }),
          fetch(`http://localhost:8081/api/v1/dashboard/distributor/${userData.id}/onboarding`, { headers })
        ]);

        let orders: any[] = [], investors: any[] = [], schemes: any[] = [], leads: any[] = [], onboarding: any = { kycPending: [], bankPending: [], readyToInvest: [] };
        if (ordersRes.ok) orders = await ordersRes.json();
        if (investorsRes.ok) investors = await investorsRes.json();
        if (schemesRes.ok) schemes = await schemesRes.json();
        if (leadsRes.ok) leads = await leadsRes.json();
        if (onboardingRes.ok) onboarding = await onboardingRes.json();

        let totalAum = 0;
        let sipAmount = 0;
        let sipCount = 0;
        let failedSips = 0;
        let todayOrders = 0;

        let successfulOrders = 0;
        let pendingOrders = 0;
        let failedOrders = 0;

        let mfAum = 0;
        let sifAum = 0;
        let othersAum = 0;

        const schemeMap = new Map(schemes.map(s => [s.id, s]));
        const today = new Date().toDateString();

        // Activity feed builder
        const activities: any[] = [];

        orders.forEach((o: any) => {
          if (o.orderStatus === 'COMPLETED') {
            totalAum += o.amount || 0;
            const s = schemeMap.get(o.productSchemeId);
            if (s?.productCategory === 'MUTUAL_FUND') mfAum += o.amount || 0;
            else if (s?.productCategory === 'SIF') sifAum += o.amount || 0;
            else othersAum += o.amount || 0;
          }

          if (o.transactionType === 'SIP') {
            sipCount++;
            if (o.orderStatus === 'COMPLETED') sipAmount += o.amount || 0;
            if (o.orderStatus === 'FAILED') failedSips++;
          }
          if (new Date(o.createdAt || Date.now()).toDateString() === today) {
            todayOrders++;
          }

          if (o.orderStatus === 'COMPLETED' || o.orderStatus === 'SUCCESSFUL') successfulOrders++;
          else if (o.orderStatus === 'FAILED') failedOrders++;
          else pendingOrders++;

          // push to activities
          activities.push({
            date: new Date(o.createdAt || Date.now()),
            action: `Order ${o.orderStatus}`,
            name: `Order #${o.id.substring(0, 6)}`,
            dot: o.orderStatus === 'COMPLETED' ? 'bg-blue-500' : o.orderStatus === 'FAILED' ? 'bg-red-500' : 'bg-amber-500'
          });
        });

        let kycPending = 0;
        let bankPending = 0;
        investors.forEach((i: any) => {
          if (i.kycStatus !== 'COMPLETED') kycPending++;
          if (i.bankVerificationStatus !== 'VERIFIED') bankPending++;

          activities.push({
            date: new Date(i.createdAt || Date.now()),
            action: `Investor Added`,
            name: i.fullName || 'Unknown',
            dot: 'bg-green-500'
          });
        });

        let newLeads = 0;
        let inProgressLeads = 0;
        let convertedLeads = 0;
        leads.forEach((l: any) => {
          if (l.status === 'NEW') newLeads++;
          else if (l.status === 'CONVERTED_TO_INVESTOR' || l.status === 'INVESTMENT_COMPLETED') convertedLeads++;
          else inProgressLeads++;
        });

        // Time formatter
        const formatTime = (d: Date) => {
          const diff = Math.floor((Date.now() - d.getTime()) / 60000); // mins
          if (diff < 60) return `${diff} min ago`;
          if (diff < 1440) return `${Math.floor(diff / 60)} hrs ago`;
          return `${Math.floor(diff / 1440)} days ago`;
        };
        activities.sort((a, b) => b.date.getTime() - a.date.getTime());
        const recentActivity = activities.slice(0, 5).map(a => ({
          ...a, time: formatTime(a.date)
        }));

        setMetrics({
          totalAum,
          investorCount: investors.length,
          sipAmount,
          sipCount,
          failedSips,
          todayOrders,
          aumSub: [
            { label: 'Mutual Funds', value: `₹${(mfAum / 100000).toFixed(2)} L`, change: '', up: true },
            { label: 'SIF', value: `₹${(sifAum / 100000).toFixed(2)} L`, change: '', up: true },
            { label: 'Others', value: `₹${(othersAum / 100000).toFixed(2)} L`, change: '', up: true },
          ],
          pendingSub: [
            { label: 'KYC Pending', value: kycPending },
            { label: 'Bank Link Pending', value: bankPending },
            { label: 'Txn Failed', value: failedOrders },
            { label: 'SIP Failed', value: failedSips },
          ],
          leadSub: [
            { label: 'New Leads', value: newLeads, color: 'bg-blue-400' },
            { label: 'In Progress', value: inProgressLeads, color: 'bg-amber-400' },
            { label: 'Converted', value: convertedLeads, color: 'bg-green-400' },
          ],
          donutData: [
            { name: 'Successful', value: successfulOrders, color: '#22c55e' },
            { name: 'Pending', value: pendingOrders, color: '#eab308' },
            { name: 'Failed', value: failedOrders, color: '#ef4444' },
          ],
          recentActivity,
          onboarding
        });
      } catch (err) {
        console.error('Failed to fetch dashboard metrics', err);
      }
    };
    fetchDashboard();
  }, [userData]);

  return (
    <motion.div
      initial={{ opacity: 0, y: 10 }}
      animate={{ opacity: 1, y: 0 }}
      className="p-8 space-y-6"
    >
      {/* ── Page header ─────────────────────────────────────────────────── */}
      <div className="flex justify-between items-end">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight text-slate-800">Distributor Overview</h1>
          <p className="text-slate-500 text-sm mt-1">Real-time snapshot of your AUM and business growth</p>
        </div>
        <div className="flex gap-1.5 text-xs font-semibold text-slate-500 uppercase tracking-wider bg-slate-100 p-1 rounded-lg">
          <button className="px-3 py-1.5 bg-white shadow-sm rounded text-slate-900">Today</button>
          <button className="px-3 py-1.5 hover:text-slate-700 rounded transition-all">Weekly</button>
          <button className="px-3 py-1.5 hover:text-slate-700 rounded transition-all">Monthly</button>
        </div>
      </div>

      {/* ══════════════════════════════════════════════════════════════════
          5 SNAPSHOT CARDS  (PRD: Key Business Snapshot)
      ══════════════════════════════════════════════════════════════════ */}
      <div className="grid grid-cols-5 gap-4">

        {/* ── Card 1: Total AUM ── */}
        <SnapshotCard onClick={() => onNavigate('aum-breakdown')} accent="border-t-blue-500">
          <CardLabel icon={<TrendingUp className="w-3.5 h-3.5 text-blue-500" />}>Total AUM</CardLabel>
          <p className="text-2xl font-bold text-slate-800 mt-2">₹{(metrics.totalAum / 100000).toFixed(2)} L</p>
          <p className="text-[11px] font-semibold text-green-500 flex items-center gap-0.5 mt-0.5 mb-3">
            <TrendingUp className="w-3 h-3" /> Live
          </p>
          <Divider />
          <div className="space-y-1.5 mt-3">
            {metrics.aumSub.map(s => (
              <div key={s.label} className="flex items-center justify-between gap-1">
                <span className="text-[11px] text-slate-500 truncate">{s.label}</span>
                <span className={`text-[10px] font-semibold ${s.up ? 'text-green-500' : 'text-red-500'}`}>{s.value}</span>
              </div>
            ))}
          </div>
        </SnapshotCard>

        {/* ── Card 2: Investor Base ── */}
        <SnapshotCard onClick={() => onNavigate('investors')} accent="border-t-emerald-500">
          <CardLabel icon={<Users className="w-3.5 h-3.5 text-emerald-500" />}>Investor Base</CardLabel>
          <p className="text-2xl font-bold text-slate-800 mt-2">{metrics.investorCount}</p>
          <p className="text-[11px] text-slate-400 mt-0.5 mb-3">Total Investors</p>
          <Divider />
          <div className="space-y-1.5 mt-3">
            <SubRow label="Active SIP" value={metrics.sipCount} />
            <SubRow label="New (30d)" value="12" highlight />
          </div>
        </SnapshotCard>

        {/* ── Card 3: SIP This Month ── */}
        <SnapshotCard onClick={() => onNavigate('sip-dashboard')} accent="border-t-violet-500">
          <CardLabel icon={<Activity className="w-3.5 h-3.5 text-violet-500" />}>SIP · This Month</CardLabel>
          <p className="text-2xl font-bold text-slate-800 mt-2">₹{(metrics.sipAmount / 1000).toFixed(1)} k</p>
          <p className="text-[11px] font-semibold text-green-500 flex items-center gap-0.5 mt-0.5 mb-3">
            <TrendingUp className="w-3 h-3" /> Live
          </p>
          <Divider />
          <div className="space-y-1.5 mt-3">
            <SubRow label="Active SIPs" value={metrics.sipCount} />
            <SubRow label="Failed SIPs" value={metrics.failedSips} warn />
          </div>
        </SnapshotCard>

        {/* ── Card 4: Pending Actions ── */}
        <SnapshotCard onClick={() => onNavigate('action-center')} accent="border-t-red-500">
          <CardLabel icon={<AlertCircle className="w-3.5 h-3.5 text-red-500" />}>Pending Actions</CardLabel>
          <p className="text-2xl font-bold text-slate-800 mt-2">{(metrics.pendingSub.reduce((acc, curr) => acc + curr.value, 0))}</p>
          <p className="text-[11px] text-slate-400 mt-0.5 mb-3">Items requiring action</p>
          <Divider />
          <div className="space-y-1.5 mt-3">
            {metrics.pendingSub.map(s => (
              <div key={s.label} className="flex items-center justify-between">
                <span className="text-[11px] text-slate-500 truncate">{s.label}</span>
                <span className="text-[11px] font-semibold text-slate-700">{s.value}</span>
              </div>
            ))}
          </div>
        </SnapshotCard>

        {/* ── Card 5: Lead Pipeline ── */}
        <SnapshotCard onClick={() => onNavigate('leads')} accent="border-t-amber-500">
          <CardLabel icon={<Target className="w-3.5 h-3.5 text-amber-500" />}>Lead Pipeline</CardLabel>
          <p className="text-2xl font-bold text-slate-800 mt-2">{metrics.leadSub.reduce((acc, curr) => acc + curr.value, 0)}</p>
          <p className="text-[11px] text-slate-400 mt-0.5 mb-3">Total Leads</p>
          <Divider />
          {/* Mini funnel */}
          <div className="space-y-2 mt-3">
            {metrics.leadSub.map(s => {
              const total = metrics.leadSub.reduce((acc, curr) => acc + curr.value, 0) || 1;
              return (
                <div key={s.label} className="flex items-center gap-2">
                  <div className="flex-1">
                    <div className="flex justify-between mb-0.5">
                      <span className="text-[11px] text-slate-500">{s.label}</span>
                      <span className="text-[11px] font-semibold text-slate-700">{s.value}</span>
                    </div>
                    <div className="h-1 bg-slate-100 rounded-full overflow-hidden">
                      <div
                        className={`h-full rounded-full ${s.color}`}
                        style={{ width: `${Math.round((s.value / total) * 100)}%` }}
                      />
                    </div>
                  </div>
                </div>
              )
            })}
          </div>
        </SnapshotCard>

      </div>

      {/* ── Lower section: kanban + donut ───────────────────────────────── */}
      <div className="grid grid-cols-3 gap-6">
        {/* Onboarding Pipeline */}
        <div className="col-span-2 bg-white rounded-2xl shadow-sm border border-slate-200 overflow-hidden flex flex-col">
          <div className="p-5 border-b border-slate-100 flex justify-between items-center">
            <h2 className="font-semibold text-slate-800">Onboarding Pipeline</h2>
            <button className="text-[#0B1B3E] text-xs font-semibold flex items-center gap-1 hover:underline">
              View All <ArrowUpRight className="w-3 h-3" />
            </button>
          </div>
          <div className="flex-1 p-5 overflow-x-auto">
            <div className="flex gap-4 min-w-max">
              <KanbanColumn title="KYC Pending" count={metrics.onboarding.kycPending.length} accent="border-t-amber-400">
                {metrics.onboarding.kycPending.map((item: any) => (
                  <KanbanCard key={item.id} name={item.name} detail={item.detail} days={item.days} status={item.status} />
                ))}
              </KanbanColumn>
              <KanbanColumn title="Bank Pending" count={metrics.onboarding.bankPending.length} accent="border-t-blue-400">
                {metrics.onboarding.bankPending.map((item: any) => (
                  <KanbanCard key={item.id} name={item.name} detail={item.detail} days={item.days} status={item.status} />
                ))}
              </KanbanColumn>
              <KanbanColumn title="Ready to Invest" count={metrics.onboarding.readyToInvest.length} accent="border-t-green-400">
                {metrics.onboarding.readyToInvest.map((item: any) => (
                  <KanbanCard key={item.id} name={item.name} detail={item.detail} days={item.days} status={item.status} />
                ))}
              </KanbanColumn>
            </div>
          </div>
        </div>

        {/* Today's Transactions donut */}
        <div className="bg-[#0B1B3E] rounded-2xl shadow-sm p-6 text-white flex flex-col relative overflow-hidden">
          <div className="absolute top-0 right-0 w-64 h-64 bg-blue-500 opacity-10 rounded-full blur-3xl transform translate-x-1/2 -translate-y-1/2" />
          <h2 className="font-semibold mb-6">Today's Transactions</h2>
          <div className="h-48 w-full relative">
            <ResponsiveContainer width="100%" height="100%">
              <PieChart>
                <Pie
                  data={metrics.donutData} cx="50%" cy="50%"
                  innerRadius={60} outerRadius={80}
                  paddingAngle={5} dataKey="value" stroke="none"
                >
                  {metrics.donutData.map((entry, i) => (
                    <Cell key={`cell-${i}`} fill={entry.color} />
                  ))}
                </Pie>
                <Tooltip
                  contentStyle={{ backgroundColor: '#1A3066', border: 'none', borderRadius: '8px', color: 'white', fontSize: '12px' }}
                  itemStyle={{ color: '#fff' }}
                />
              </PieChart>
            </ResponsiveContainer>
            <div className="absolute inset-0 flex flex-col items-center justify-center pointer-events-none">
              <span className="text-2xl font-bold">{metrics.todayOrders}</span>
              <span className="text-[10px] text-blue-200 uppercase tracking-wide">Orders</span>
            </div>
          </div>
          <div className="mt-4 space-y-3">
            {metrics.donutData.map(item => (
              <div key={item.name} className="flex justify-between items-center text-sm">
                <div className="flex items-center gap-2">
                  <div className="w-2.5 h-2.5 rounded-full" style={{ backgroundColor: item.color }} />
                  <span className="text-blue-100">{item.name}</span>
                </div>
                <span className="font-medium font-mono">{item.value}</span>
              </div>
            ))}
          </div>
        </div>
      </div>

      {/* ── Recent Activity ──────────────────────────────────────────────── */}
      <div className="bg-white rounded-2xl shadow-sm border border-slate-200 overflow-hidden">
        <div className="p-5 border-b border-slate-100 flex justify-between items-center">
          <h2 className="font-semibold text-slate-800">Recent Activity</h2>
          <button className="text-[#0B1B3E] text-xs font-semibold flex items-center gap-1 hover:underline">
            View All <ArrowUpRight className="w-3 h-3" />
          </button>
        </div>
        <div className="divide-y divide-slate-100">
          {metrics.recentActivity.map((item, i) => (
            <div key={i} className="px-5 py-3.5 flex items-center gap-4 hover:bg-slate-50 transition-colors cursor-pointer">
              <div className={`w-2 h-2 rounded-full flex-shrink-0 ${item.dot}`} />
              <div className="flex-1 flex justify-between items-center">
                <div>
                  <span className="text-sm font-medium text-slate-800">{item.action}</span>
                  <span className="text-sm text-slate-400 mx-1.5">·</span>
                  <span className="text-sm text-slate-500">{item.name}</span>
                </div>
                <span className="text-xs text-slate-400">{item.time}</span>
              </div>
            </div>
          ))}
          {metrics.recentActivity.length === 0 && (
            <div className="p-5 text-center text-sm text-slate-400">No recent activity</div>
          )}
        </div>
      </div>
    </motion.div>
  );
}

/* ── Sub-components ──────────────────────────────────────────────────────── */

function SnapshotCard({
  children, onClick, accent,
}: {
  children: React.ReactNode; onClick: () => void; accent: string;
}) {
  return (
    <motion.div
      whileHover={{ y: -2 }}
      onClick={onClick}
      className={`bg-white rounded-2xl border border-slate-200 shadow-sm p-4 cursor-pointer
                  hover:shadow-md transition-shadow group relative overflow-hidden
                  border-t-2 ${accent}`}
    >
      {children}
      {/* Hover arrow */}
      <ChevronRight
        className="w-3.5 h-3.5 text-slate-200 group-hover:text-blue-400 transition-all
                   absolute top-3.5 right-3.5 group-hover:translate-x-0.5"
      />
    </motion.div>
  );
}

function CardLabel({ icon, children }: { icon: React.ReactNode; children: React.ReactNode }) {
  return (
    <div className="flex items-center gap-1.5">
      {icon}
      <p className="text-[10px] font-bold text-slate-500 uppercase tracking-wider leading-none">
        {children}
      </p>
    </div>
  );
}

function Divider() {
  return <div className="border-t border-slate-100" />;
}

function SubRow({
  label, value, highlight, warn,
}: {
  label: string; value: string | number; highlight?: boolean; warn?: boolean;
}) {
  return (
    <div className="flex items-center justify-between">
      <span className="text-[11px] text-slate-500 truncate">{label}</span>
      <span className={`text-[11px] font-semibold ${warn ? 'text-red-500' : highlight ? 'text-green-600' : 'text-slate-700'}`}>
        {value}
      </span>
    </div>
  );
}

/* ── Kanban (unchanged) ─────────────────────────────────────────────────── */

function KanbanColumn({ title, count, children, accent }: {
  title: string; count: number; children: React.ReactNode; accent: string;
}) {
  return (
    <div className={`w-64 bg-slate-50 rounded-xl p-3 flex flex-col gap-3 border border-slate-100 border-t-2 ${accent}`}>
      <div className="flex justify-between items-center px-1">
        <h3 className="text-xs font-semibold text-slate-600 uppercase tracking-wider">{title}</h3>
        <span className="px-2 py-0.5 rounded-full bg-slate-200 text-slate-600 text-[10px] font-bold">{count}</span>
      </div>
      <div className="flex flex-col gap-2">{children}</div>
    </div>
  );
}

const statusColors: Record<string, string> = {
  kyc: 'bg-amber-100 text-amber-700',
  bank: 'bg-blue-100  text-blue-700',
  ready: 'bg-green-100 text-green-700',
  failed: 'bg-red-100   text-red-700',
};

function KanbanCard({ name, detail, days, status }: {
  name: string; detail: string; days: string; status: string;
}) {
  return (
    <div className="bg-white p-3 rounded-lg shadow-sm border border-slate-200 hover:border-blue-300 transition-colors cursor-pointer group">
      <p className="text-sm font-semibold text-slate-800 group-hover:text-blue-600 transition-colors mb-1">{name}</p>
      <p className="text-xs text-slate-500 mb-2">{detail}</p>
      <div className="flex justify-between items-center">
        <span className={`text-[10px] font-bold px-1.5 py-0.5 rounded ${statusColors[status] ?? 'bg-slate-100 text-slate-500'}`}>
          {status.toUpperCase()}
        </span>
        <span className="text-[10px] text-slate-400 bg-slate-100 px-1.5 py-0.5 rounded">{days}</span>
      </div>
    </div>
  );
}
