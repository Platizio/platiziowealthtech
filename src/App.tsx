import React, { useState } from 'react';
import {
  LayoutDashboard, Users, BookOpen, Search, Bell,
  ArrowLeftRight, UserCircle2, TrendingUp, BellRing, Target,
  BarChart3, Network, Layers, UserCheck, ShieldCheck
} from 'lucide-react';

// Distributor views
import Onboarding    from './views/Onboarding';
import Dashboard     from './views/Dashboard';
import Investors     from './views/Investors';
import Ledger        from './views/Ledger';
import Transactions  from './views/Transactions';
import Profile       from './views/Profile';
import Earnings      from './views/Earnings';
import Notifications from './views/Notifications';
import Leads         from './views/Leads';

// Admin views
import AdminOverview    from './views/AdminOverview';
import DistributorMgmt  from './views/DistributorMgmt';
import ProductMgmt      from './views/ProductMgmt';
import InvestorMgmt     from './views/InvestorMgmt';

// ─── Types ──────────────────────────────────────────────────────────────────

type Mode = 'distributor' | 'admin';

type DistributorView =
  | 'dashboard' | 'investors' | 'ledger' | 'transactions'
  | 'leads' | 'earnings' | 'notifications' | 'profile';

type AdminView =
  | 'admin-overview' | 'distributor-mgmt' | 'product-mgmt' | 'investor-mgmt';

// ─── Nav definitions ────────────────────────────────────────────────────────

const DIST_NAV_MAIN: { id: DistributorView; icon: React.ReactNode; label: string }[] = [
  { id: 'dashboard',    icon: <LayoutDashboard className="w-4 h-4" />, label: 'Dashboard'       },
  { id: 'investors',    icon: <Users           className="w-4 h-4" />, label: 'Investors'       },
  { id: 'ledger',       icon: <BookOpen        className="w-4 h-4" />, label: 'Product Catalog' },
  { id: 'transactions', icon: <ArrowLeftRight  className="w-4 h-4" />, label: 'Transactions'    },
];

const DIST_NAV_SEC: { id: DistributorView; icon: React.ReactNode; label: string; badge?: number }[] = [
  { id: 'leads',         icon: <Target      className="w-4 h-4" />, label: 'Lead Pipeline'         },
  { id: 'earnings',      icon: <TrendingUp  className="w-4 h-4" />, label: 'Earnings'              },
  { id: 'notifications', icon: <BellRing    className="w-4 h-4" />, label: 'Notifications', badge: 3 },
  { id: 'profile',       icon: <UserCircle2 className="w-4 h-4" />, label: 'Profile & Compliance'  },
];

const ADMIN_NAV: { id: AdminView; icon: React.ReactNode; label: string }[] = [
  { id: 'admin-overview',    icon: <BarChart3   className="w-4 h-4" />, label: 'Overview'               },
  { id: 'distributor-mgmt',  icon: <Network     className="w-4 h-4" />, label: 'Distributor Management' },
  { id: 'product-mgmt',      icon: <Layers      className="w-4 h-4" />, label: 'Product Management'     },
  { id: 'investor-mgmt',     icon: <UserCheck   className="w-4 h-4" />, label: 'Investor Management'    },
];

// ─── App ────────────────────────────────────────────────────────────────────

export default function App() {
  const [mode,            setMode]            = useState<Mode>('distributor');
  const [distView,        setDistView]        = useState<DistributorView | 'onboarding'>('onboarding');
  const [adminView,       setAdminView]       = useState<AdminView>('admin-overview');

  // ── Onboarding gate (distributor only) ──────────────────────────────────
  if (mode === 'distributor' && distView === 'onboarding') {
    return (
      <Onboarding onComplete={() => setDistView('dashboard')} />
    );
  }

  // ── Active view component ────────────────────────────────────────────────
  const distViews: Record<DistributorView, React.ReactNode> = {
    dashboard:    <Dashboard    />,
    investors:    <Investors    />,
    ledger:       <Ledger       />,
    transactions: <Transactions />,
    leads:        <Leads        />,
    earnings:     <Earnings     />,
    notifications:<Notifications/>,
    profile:      <Profile      />,
  };

  const adminViews: Record<AdminView, React.ReactNode> = {
    'admin-overview':   <AdminOverview   />,
    'distributor-mgmt': <DistributorMgmt />,
    'product-mgmt':     <ProductMgmt     />,
    'investor-mgmt':    <InvestorMgmt    />,
  };

  const activeView = mode === 'admin'
    ? adminViews[adminView]
    : distViews[distView as DistributorView];

  const switchMode = (next: Mode) => {
    setMode(next);
    // Reset to default view for each mode
    if (next === 'admin')        setAdminView('admin-overview');
    if (next === 'distributor')  setDistView('dashboard');
  };

  // ── Render ───────────────────────────────────────────────────────────────
  return (
    <div className="flex h-screen w-full overflow-hidden bg-[#F1F5F9] text-slate-900 font-sans selection:bg-blue-100 selection:text-blue-900">

      {/* ── Sidebar ─────────────────────────────────────────────────────── */}
      <aside className="w-64 bg-white border-r border-slate-200 flex flex-col flex-shrink-0">
        <div className="p-6 flex-1 overflow-y-auto">

          {/* Logo + mode badge */}
          <div className="flex items-center gap-3 mb-8">
            <div className={`w-8 h-8 rounded-xl flex items-center justify-center text-white font-bold shadow-sm ${mode === 'admin' ? 'bg-violet-700' : 'bg-[#0B1B3E]'}`}>A</div>
            <div>
              <span className="font-bold text-xl tracking-tight text-[#0B1B3E] block leading-tight">Apex Wealth</span>
              {mode === 'admin' && (
                <span className="text-[10px] font-bold text-violet-600 bg-violet-50 px-1.5 py-0.5 rounded uppercase tracking-wider">
                  Admin Panel
                </span>
              )}
            </div>
          </div>

          {/* ── Admin nav ─────────────────────────────────────────────── */}
          {mode === 'admin' && (
            <>
              <p className="text-[10px] font-bold text-slate-400 uppercase tracking-widest mb-2 px-1">Admin</p>
              <nav className="space-y-1">
                {ADMIN_NAV.map(item => (
                  <NavItem
                    key={item.id}
                    active={adminView === item.id}
                    onClick={() => setAdminView(item.id)}
                    icon={item.icon}
                    label={item.label}
                    adminMode
                  />
                ))}
              </nav>
            </>
          )}

          {/* ── Distributor nav ───────────────────────────────────────── */}
          {mode === 'distributor' && (
            <>
              <p className="text-[10px] font-bold text-slate-400 uppercase tracking-widest mb-2 px-1">Main</p>
              <nav className="space-y-1 mb-6">
                {DIST_NAV_MAIN.map(item => (
                  <NavItem
                    key={item.id}
                    active={distView === item.id}
                    onClick={() => setDistView(item.id)}
                    icon={item.icon}
                    label={item.label}
                  />
                ))}
              </nav>

              <div className="border-t border-slate-100 my-4" />

              <p className="text-[10px] font-bold text-slate-400 uppercase tracking-widest mb-2 px-1">Management</p>
              <nav className="space-y-1">
                {DIST_NAV_SEC.map(item => (
                  <NavItem
                    key={item.id}
                    active={distView === item.id}
                    onClick={() => setDistView(item.id)}
                    icon={item.icon}
                    label={item.label}
                    badge={item.badge}
                  />
                ))}
              </nav>
            </>
          )}
        </div>

        {/* ── Sidebar footer ──────────────────────────────────────────── */}
        <div className="p-5 border-t border-slate-100 flex-shrink-0">
          {mode === 'admin' ? (
            <>
              <div className="bg-violet-50 px-4 py-3 rounded-xl border border-violet-100 mb-4">
                <p className="text-[10px] uppercase font-bold text-violet-400 tracking-wider mb-1">Access Level</p>
                <p className="text-sm font-semibold text-violet-700 flex items-center gap-1.5">
                  <ShieldCheck className="w-4 h-4" /> Master Distributor
                </p>
              </div>
              <div className="flex items-center gap-3">
                <div className="w-8 h-8 rounded-full bg-violet-200 flex items-center justify-center text-xs font-bold text-violet-700">MD</div>
                <div>
                  <p className="text-sm font-semibold text-slate-800">Apex Wealth Team</p>
                  <p className="text-xs text-slate-500">admin@apexwealth.in</p>
                </div>
              </div>
            </>
          ) : (
            <>
              <div className="bg-slate-50 px-4 py-3 rounded-xl border border-slate-100 mb-4">
                <p className="text-[10px] uppercase font-bold text-slate-400 tracking-wider mb-1">ARN Status</p>
                <p className="text-sm font-semibold text-green-600 flex items-center gap-1.5">
                  <span className="relative flex h-2 w-2">
                    <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-green-400 opacity-75"></span>
                    <span className="relative inline-flex rounded-full h-2 w-2 bg-green-500"></span>
                  </span>
                  Active: ARN-102943
                </p>
              </div>
              <div className="flex items-center gap-3">
                <div className="w-8 h-8 rounded-full bg-slate-200 flex items-center justify-center text-xs font-bold text-slate-600">AS</div>
                <div>
                  <p className="text-sm font-semibold text-slate-800">Aditya Sharma</p>
                  <p className="text-xs text-slate-500 cursor-pointer hover:text-blue-600 transition-colors"
                    onClick={() => setDistView('onboarding')}>
                    Sign out
                  </p>
                </div>
              </div>
            </>
          )}
        </div>
      </aside>

      {/* ── Main content ────────────────────────────────────────────────── */}
      <main className="flex-1 flex flex-col min-w-0">

        {/* ── Top header ────────────────────────────────────────────────── */}
        <header className="h-16 bg-white/80 backdrop-blur-md border-b border-slate-200 flex items-center justify-between px-8 flex-shrink-0 sticky top-0 z-40">
          {/* Search */}
          <div className="relative w-72 max-w-full">
            <Search className="w-4 h-4 absolute left-3 top-2.5 text-slate-400" />
            <input
              type="text"
              placeholder={mode === 'admin' ? 'Search distributors, investors…' : 'Search clients, funds, or PAN…'}
              className="w-full pl-10 pr-4 py-2 text-sm bg-slate-100/50 border border-slate-200 rounded-full focus:bg-white focus:ring-2 focus:ring-blue-100 focus:border-blue-500 transition-all outline-none"
            />
          </div>

          {/* Right actions */}
          <div className="flex items-center gap-4">
            {/* ── Mode Toggle ── */}
            <div className="flex items-center bg-slate-100 rounded-xl p-1 gap-0.5">
              <button
                onClick={() => switchMode('distributor')}
                className={`px-3.5 py-1.5 text-xs font-semibold rounded-lg transition-all ${
                  mode === 'distributor'
                    ? 'bg-white shadow-sm text-slate-900'
                    : 'text-slate-500 hover:text-slate-700'
                }`}
              >
                Distributor
              </button>
              <button
                onClick={() => switchMode('admin')}
                className={`px-3.5 py-1.5 text-xs font-semibold rounded-lg transition-all ${
                  mode === 'admin'
                    ? 'bg-[#0B1B3E] shadow-sm text-white'
                    : 'text-slate-500 hover:text-slate-700'
                }`}
              >
                Admin
              </button>
            </div>

            {/* Bell */}
            <button
              onClick={() => mode === 'distributor' && setDistView('notifications')}
              className="text-slate-400 hover:text-slate-600 transition-colors relative"
            >
              <Bell className="w-5 h-5" />
              <span className="absolute top-0 right-0 w-2 h-2 bg-red-500 border border-white rounded-full"></span>
            </button>

            {/* CTA */}
            {mode === 'distributor' ? (
              <button
                onClick={() => setDistView('investors')}
                className="px-4 py-2 text-sm font-medium bg-[#0B1B3E] text-white rounded-lg shadow-sm hover:bg-[#1A3066] transition-colors"
              >
                + New Onboarding
              </button>
            ) : (
              <button
                onClick={() => setAdminView('distributor-mgmt')}
                className="px-4 py-2 text-sm font-medium bg-violet-700 text-white rounded-lg shadow-sm hover:bg-violet-800 transition-colors"
              >
                + Add Distributor
              </button>
            )}
          </div>
        </header>

        {/* ── View ─────────────────────────────────────────────────────── */}
        <div className="flex-1 overflow-auto bg-[#F1F5F9]">
          {activeView}
        </div>
      </main>
    </div>
  );
}

// ─── NavItem ─────────────────────────────────────────────────────────────────

function NavItem({
  active, onClick, icon, label, badge, adminMode
}: {
  key?: string | number;
  active: boolean;
  onClick: () => void;
  icon: React.ReactNode;
  label: string;
  badge?: number;
  adminMode?: boolean;
}) {
  return (
    <button
      onClick={onClick}
      className={`w-full flex items-center gap-3 px-4 py-2.5 text-sm font-medium rounded-lg transition-colors text-left ${
        active
          ? adminMode
            ? 'bg-violet-50 text-violet-700'
            : 'bg-blue-50/80 text-blue-700'
          : 'text-slate-600 hover:bg-slate-50 hover:text-slate-900'
      }`}
    >
      <span className={`${active ? (adminMode ? 'text-violet-600' : 'text-blue-600') : 'text-slate-400'}`}>
        {icon}
      </span>
      <span className="flex-1">{label}</span>
      {badge !== undefined && badge > 0 && (
        <span className="ml-auto w-5 h-5 rounded-full bg-red-500 text-white text-[10px] font-bold flex items-center justify-center">
          {badge}
        </span>
      )}
    </button>
  );
}
