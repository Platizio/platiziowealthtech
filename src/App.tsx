import React, { useState } from 'react';
import {
  LayoutDashboard, Users, BookOpen, ShieldCheck,
  Search, Bell, ArrowLeftRight, UserCircle2,
  TrendingUp, BellRing, Target
} from 'lucide-react';
import Onboarding from './views/Onboarding';
import Dashboard from './views/Dashboard';
import Investors from './views/Investors';
import Ledger from './views/Ledger';
import Transactions from './views/Transactions';
import Profile from './views/Profile';
import Earnings from './views/Earnings';
import Notifications from './views/Notifications';
import Leads from './views/Leads';

type ViewKey =
  | 'dashboard' | 'investors' | 'ledger' | 'transactions'
  | 'leads' | 'earnings' | 'notifications' | 'profile';

const NAV_MAIN: { id: ViewKey; icon: React.ReactNode; label: string }[] = [
  { id: 'dashboard', icon: <LayoutDashboard className="w-4 h-4" />, label: 'Dashboard' },
  { id: 'investors', icon: <Users className="w-4 h-4" />, label: 'Investors' },
  { id: 'ledger', icon: <BookOpen className="w-4 h-4" />, label: 'Product Catalog' },
  { id: 'transactions', icon: <ArrowLeftRight className="w-4 h-4" />, label: 'Transactions' },
];

const NAV_SECONDARY: { id: ViewKey; icon: React.ReactNode; label: string; badge?: number }[] = [
  { id: 'leads', icon: <Target className="w-4 h-4" />, label: 'Lead Pipeline' },
  { id: 'earnings', icon: <TrendingUp className="w-4 h-4" />, label: 'Earnings' },
  { id: 'notifications', icon: <BellRing className="w-4 h-4" />, label: 'Notifications', badge: 3 },
  { id: 'profile', icon: <UserCircle2 className="w-4 h-4" />, label: 'Profile & Compliance' },
];

export default function App() {
  const [currentView, setCurrentView] = useState<ViewKey | 'onboarding'>('onboarding');

  if (currentView === 'onboarding') {
    return <Onboarding onComplete={() => setCurrentView('dashboard')} />;
  }

  const views: Record<ViewKey, React.ReactNode> = {
    dashboard: <Dashboard />,
    investors: <Investors />,
    ledger: <Ledger />,
    transactions: <Transactions />,
    leads: <Leads />,
    earnings: <Earnings />,
    notifications: <Notifications />,
    profile: <Profile />,
  };

  return (
    <div className="flex h-screen w-full overflow-hidden bg-[#F1F5F9] text-slate-900 font-sans selection:bg-blue-100 selection:text-blue-900">
      {/* Sidebar */}
      <aside className="w-64 bg-white border-r border-slate-200 flex flex-col flex-shrink-0">
        <div className="p-6 flex-1 overflow-y-auto">
          {/* Logo */}
          <div className="flex items-center gap-3 mb-8">
            <div className="w-8 h-8 bg-[#0B1B3E] rounded-xl flex items-center justify-center text-white font-bold shadow-sm">A</div>
            <span className="font-bold text-xl tracking-tight text-[#0B1B3E]">Apex Wealth</span>
          </div>

          {/* Primary nav */}
          <p className="text-[10px] font-bold text-slate-400 uppercase tracking-widest mb-2 px-1">Main</p>
          <nav className="space-y-1 mb-6">
            {NAV_MAIN.map(item => (
              <NavItem
                key={item.id}
                active={currentView === item.id}
                onClick={() => setCurrentView(item.id)}
                icon={item.icon}
                label={item.label}
              />
            ))}
          </nav>

          <div className="border-t border-slate-100 my-4" />

          {/* Secondary nav */}
          <p className="text-[10px] font-bold text-slate-400 uppercase tracking-widest mb-2 px-1">Management</p>
          <nav className="space-y-1">
            {NAV_SECONDARY.map(item => (
              <NavItem
                key={item.id}
                active={currentView === item.id}
                onClick={() => setCurrentView(item.id)}
                icon={item.icon}
                label={item.label}
                badge={item.badge}
              />
            ))}
          </nav>
        </div>

        {/* Footer */}
        <div className="p-5 border-t border-slate-100 flex-shrink-0">
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
              <p
                className="text-xs text-slate-500 cursor-pointer hover:text-blue-600 transition-colors"
                onClick={() => setCurrentView('onboarding')}
              >
                Sign out
              </p>
            </div>
          </div>
        </div>
      </aside>

      {/* Main content */}
      <main className="flex-1 flex flex-col min-w-0">
        {/* Top header */}
        <header className="h-16 bg-white/80 backdrop-blur-md border-b border-slate-200 flex items-center justify-between px-8 flex-shrink-0 sticky top-0 z-40">
          <div className="relative w-96 max-w-full">
            <Search className="w-4 h-4 absolute left-3 top-2.5 text-slate-400" />
            <input
              type="text"
              placeholder="Search clients, funds, or PAN..."
              className="w-full pl-10 pr-4 py-2 text-sm bg-slate-100/50 border border-slate-200 rounded-full focus:bg-white focus:ring-2 focus:ring-blue-100 focus:border-blue-500 transition-all outline-none"
            />
          </div>
          <div className="flex items-center gap-5">
            <button
              onClick={() => setCurrentView('notifications')}
              className="text-slate-400 hover:text-slate-600 transition-colors relative"
            >
              <Bell className="w-5 h-5" />
              <span className="absolute top-0 right-0 w-2 h-2 bg-red-500 border border-white rounded-full"></span>
            </button>
            <button
              onClick={() => setCurrentView('investors')}
              className="px-4 py-2 text-sm font-medium bg-[#0B1B3E] text-white rounded-lg shadow-sm hover:bg-[#1A3066] transition-colors"
            >
              + New Onboarding
            </button>
          </div>
        </header>

        {/* View */}
        <div className="flex-1 overflow-auto bg-[#F1F5F9]">
          {views[currentView as ViewKey]}
        </div>
      </main>
    </div>
  );
}

function NavItem({
  active, onClick, icon, label, badge
}: {
  key?: string | number;
  active: boolean;
  onClick: () => void;
  icon: React.ReactNode;
  label: string;
  badge?: number;
}) {
  return (
    <button
      onClick={onClick}
      className={`w-full flex items-center gap-3 px-4 py-2.5 text-sm font-medium rounded-lg transition-colors text-left ${
        active
          ? 'bg-blue-50/80 text-blue-700'
          : 'text-slate-600 hover:bg-slate-50 hover:text-slate-900'
      }`}
    >
      <span className={`${active ? 'text-blue-600' : 'text-slate-400'}`}>{icon}</span>
      <span className="flex-1">{label}</span>
      {badge !== undefined && badge > 0 && (
        <span className="ml-auto w-5 h-5 rounded-full bg-red-500 text-white text-[10px] font-bold flex items-center justify-center">
          {badge}
        </span>
      )}
    </button>
  );
}
