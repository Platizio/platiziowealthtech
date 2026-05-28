import React, { useState, useEffect } from 'react';
import { Outlet, NavLink, useNavigate, useLocation } from 'react-router-dom';
import {
  LayoutDashboard, Users, BookOpen, Search, Bell, Menu,
  ArrowLeftRight, UserCircle2, TrendingUp, Target,
  BarChart3, Network, Layers, UserCheck, ShieldCheck, X, AlertTriangle,
  PieChart, RefreshCw, FileBarChart2, MessageSquare, ClipboardList, Calculator
} from 'lucide-react';
import { apiFetch } from '../config/api';
import { useFocusTrap } from '../hooks/useFocusTrap';

const DIST_NAV_PRIMARY = [
  { id: '/distributor/dashboard',      icon: <LayoutDashboard className="w-4 h-4" />, label: 'Dashboard' },
  { id: '/distributor/leads',          icon: <Target className="w-4 h-4" />,          label: 'Leads' },
  { id: '/distributor/investors',      icon: <Users className="w-4 h-4" />,           label: 'Investors' },
  { id: '/distributor/ledger',         icon: <BookOpen className="w-4 h-4" />,        label: 'Products' },
  { id: '/distributor/transactions',   icon: <ArrowLeftRight className="w-4 h-4" />,  label: 'Transactions' },
  { id: '/distributor/portfolio',      icon: <PieChart className="w-4 h-4" />,      label: 'Portfolio' },
  { id: '/distributor/sip-dashboard',  icon: <RefreshCw className="w-4 h-4" />,      label: 'SIP / Mandates' },
  { id: '/distributor/reports',        icon: <FileBarChart2 className="w-4 h-4" />,  label: 'Reports' },
  { id: '/distributor/action-center',  icon: <ClipboardList className="w-4 h-4" />,  label: 'Action Centre' },
  { id: '/distributor/communications', icon: <MessageSquare className="w-4 h-4" />,  label: 'Communications' },
  { id: '/distributor/calculators',    icon: <Calculator className="w-4 h-4" />,     label: 'Calculators' },
];

const DIST_NAV_ACCOUNT = [
  { id: '/distributor/profile',  icon: <UserCircle2 className="w-4 h-4" />, label: 'Profile & Compliance' },
  { id: '/distributor/earnings', icon: <TrendingUp className="w-4 h-4" />,  label: 'Earnings' },
];

const ADMIN_NAV = [
  { id: '/admin/overview', icon: <BarChart3 className="w-4 h-4" />, label: 'Overview' },
  { id: '/admin/distributor-mgmt', icon: <Network className="w-4 h-4" />, label: 'Distributor Management' },
  { id: '/admin/product-mgmt', icon: <Layers className="w-4 h-4" />, label: 'Product Management' },
  { id: '/admin/investor-mgmt', icon: <UserCheck className="w-4 h-4" />, label: 'Investor Management' },
];

const ADMIN_ROLES = new Set(['ADMIN', 'MASTER_DISTRIBUTOR']);
const normalizeRole = (role?: string) => role?.trim().toUpperCase() || '';

export default function AppLayout({ userData, onSignOut }: { userData: any, onSignOut: () => void }) {
  const navigate = useNavigate();
  const location = useLocation();
  const [showInactivePopup, setShowInactivePopup] = useState(true);
  const [unreadNotifs, setUnreadNotifs] = useState(0);
  const [sidebarOpen, setSidebarOpen] = useState(false);

  useEffect(() => {
    if (!userData?.id) {
      setUnreadNotifs(0);
      return;
    }

    let cancelled = false;
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };

    const fetchUnreadNotifications = async () => {
      try {
        const res = await apiFetch(`/notifications/distributor/${userData.id}`, { headers });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const data = await res.json();
        const unread = Array.isArray(data)
          ? data.filter((n: any) => n.readFlag === false).length
          : 0;

        if (!cancelled) setUnreadNotifs(unread);
      } catch (err) {
        console.error('Failed to fetch notifications', err);
        if (!cancelled) setUnreadNotifs(0);
      }
    };

    fetchUnreadNotifications();
    const intervalId = window.setInterval(fetchUnreadNotifications, 60000);

    return () => {
      cancelled = true;
      window.clearInterval(intervalId);
    };
  }, [userData?.id]);

  const mode = location.pathname.startsWith('/admin') ? 'admin' : 'distributor';

  const displayName = userData?.fullName || userData?.full_name || userData?.firstName || 'Unknown';
  const displayArn = userData?.arnNumber || userData?.arn_number || userData?.arn || 'Unknown';
  const displayRole = normalizeRole(userData?.role) || 'SUB_DISTRIBUTOR';
  const canAccessAdmin = ADMIN_ROLES.has(displayRole);
  const nismExpiryDate = userData?.nismExpiryDate || userData?.nism_expiry_date || null;
  const arnExpiryDate  = userData?.arnExpiryDate  || userData?.arn_expiry_date  || null;
  
  const parseDate = (dateStr: any) => {
    if (!dateStr) return null;
    if (Array.isArray(dateStr)) {
      return new Date(dateStr[0], dateStr[1] - 1, dateStr[2]);
    }
    const d = new Date(dateStr);
    return isNaN(d.getTime()) ? null : d;
  };

  const checkExpired = (dateStr: any) => {
    const d = parseDate(dateStr);
    if (!d) return false;
    return d < new Date();
  };
  
  const nismExpired = checkExpired(nismExpiryDate);
  const arnExpired  = checkExpired(arnExpiryDate);
  const isExpired   = nismExpired || arnExpired;

  // F-32: focus-trap the expired-credentials dialog while it's open so
  // keyboard users can't Tab into the page behind it. Pre-compute the open
  // condition once so the hook and the JSX use the same value.
  const expiredDialogOpen = isExpired && showInactivePopup && mode === 'distributor';
  const expiredDialogRef = useFocusTrap<HTMLDivElement>(expiredDialogOpen);

  const displayInitials = displayName !== 'Unknown'
    ? displayName.split(' ').map((n: string) => n[0]).join('').substring(0, 2).toUpperCase()
    : 'US';

  // Close sidebar whenever the route changes (mobile nav UX)
  useEffect(() => {
    setSidebarOpen(false);
  }, [location.pathname]);

  const switchMode = (next: 'admin' | 'distributor') => {
    if (next === 'admin') navigate('/admin/overview');
    if (next === 'distributor') navigate('/distributor/dashboard');
  };

  return (
    <>
      {expiredDialogOpen && (
        // F-32: role/aria-modal/aria-labelledby make this a true ARIA dialog
        // for assistive tech; useFocusTrap keeps Tab inside while it's open
        // and restores focus to the previously-focused element on close.
        <div
          ref={expiredDialogRef}
          role="dialog"
          aria-modal="true"
          aria-labelledby="expired-credentials-title"
          className="fixed inset-0 z-[100] flex items-center justify-center p-4 bg-slate-900/40 backdrop-blur-sm"
        >
          <div className="bg-white w-full max-w-sm rounded-3xl shadow-xl p-6 relative">
            <button
              onClick={() => setShowInactivePopup(false)}
              aria-label="Close dialog"
              className="absolute top-4 right-4 p-1.5 text-slate-400 hover:text-slate-600 bg-slate-100 rounded-full transition-colors"
            >
              <X className="w-4 h-4" />
            </button>
            <div className="flex flex-col items-center text-center mt-2">
              <div className="w-12 h-12 rounded-full bg-red-100 flex items-center justify-center mb-4">
                <AlertTriangle className="w-6 h-6 text-red-500" />
              </div>
              <h2 id="expired-credentials-title" className="text-lg font-bold text-slate-800 mb-2">
                {arnExpired && nismExpired ? 'Credentials Expired' : arnExpired ? 'ARN Expired' : 'NISM Expired'}
              </h2>
              <p className="text-sm text-slate-500 mb-6">
                {arnExpired && nismExpired 
                  ? 'Your ARN and NISM certifications have expired. Please renew them to restore full access.' 
                  : arnExpired 
                    ? 'Your ARN certificate has expired. Please renew it to maintain compliance.' 
                    : 'Your NISM certification has expired. Please renew it to avoid transaction restrictions.'}
              </p>
              <button onClick={() => setShowInactivePopup(false)} className="w-full py-2.5 bg-red-500 text-white font-semibold text-sm rounded-xl hover:bg-red-600 transition-colors">
                Close
              </button>
            </div>
          </div>
        </div>
      )}

      <div className="flex h-screen w-full overflow-hidden bg-[#F1F5F9] text-slate-900 font-sans selection:bg-blue-100 selection:text-blue-900">
        {/* Mobile overlay backdrop */}
        {sidebarOpen && (
          <div
            className="fixed inset-0 z-40 bg-slate-900/40 md:hidden"
            onClick={() => setSidebarOpen(false)}
          />
        )}

        {/* Sidebar — fixed overlay on mobile, static in-flow on md+ */}
        <aside className={`
          w-64 bg-white border-r border-slate-200 flex flex-col flex-shrink-0
          fixed inset-y-0 left-0 z-50 transition-transform duration-200
          md:static md:inset-auto md:z-auto md:translate-x-0
          ${sidebarOpen ? 'translate-x-0' : '-translate-x-full'}
        `}>
          <div className="p-6 flex-1 overflow-y-auto">
            <div className="flex items-center gap-3 mb-8">
              <div className={`w-8 h-8 rounded-xl flex items-center justify-center text-white font-bold shadow-sm ${mode === 'admin' ? 'bg-violet-700' : 'bg-[#0B1B3E]'}`}>P</div>
              <div>
                <span className="font-bold text-xl tracking-tight text-[#0B1B3E] block leading-tight">Platizio</span>
                {mode === 'admin' && (
                  <span className="text-[10px] font-bold text-violet-600 bg-violet-50 px-1.5 py-0.5 rounded uppercase tracking-wider">
                    Admin Panel
                  </span>
                )}
              </div>
            </div>

            {mode === 'admin' && (
              <>
                <p className="text-[10px] font-bold text-slate-400 uppercase tracking-widest mb-2 px-1">Admin</p>
                <nav className="space-y-1">
                  {ADMIN_NAV.map(item => (
                    <NavItem key={item.id} to={item.id} icon={item.icon} label={item.label} adminMode />
                  ))}
                </nav>
              </>
            )}

            {mode === 'distributor' && (
              <>
                <nav className="space-y-0.5">
                  {DIST_NAV_PRIMARY.map(item => (
                    <NavItem key={item.id} to={item.id} icon={item.icon} label={item.label} comingSoon={(item as any).comingSoon} />
                  ))}
                </nav>

                <div className="border-t border-slate-100 my-3" />

                <nav className="space-y-0.5">
                  {DIST_NAV_ACCOUNT.map(item => (
                    <NavItem key={item.id} to={item.id} icon={item.icon} label={item.label} />
                  ))}
                </nav>
              </>
            )}
          </div>

          {/* Sidebar footer */}
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
                    <p className="text-sm font-semibold text-slate-800">Platizio Team</p>
                    <p className="text-xs text-slate-500">admin@platizio.in</p>
                  </div>
                </div>
              </>
            ) : (
              <>
                <div className="bg-slate-50 px-4 py-3 rounded-xl border border-slate-100 mb-4">
                  <p className="text-[10px] uppercase font-bold text-slate-400 tracking-wider mb-1">ARN Status</p>
                  <p className={`text-sm font-semibold flex items-center gap-1.5 ${isExpired ? 'text-red-600' : 'text-green-600'}`}>
                    <span className="relative flex h-2 w-2">
                      <span className={`animate-ping absolute inline-flex h-full w-full rounded-full opacity-75 ${isExpired ? 'bg-red-400' : 'bg-green-400'}`}></span>
                      <span className={`relative inline-flex rounded-full h-2 w-2 ${isExpired ? 'bg-red-500' : 'bg-green-500'}`}></span>
                    </span>
                    {isExpired ? 'Inactive' : 'Active'}: {displayArn}
                  </p>
                  {nismExpiryDate && (
                    <p className="text-[10px] text-slate-500 mt-1 font-medium">
                      NISM Expiry: <span className={isExpired ? 'text-red-500' : ''}>{nismExpiryDate}</span>
                    </p>
                  )}
                </div>
                <div className="flex items-center gap-3">
                  <div className="w-8 h-8 rounded-full bg-slate-200 flex items-center justify-center text-xs font-bold text-slate-600">
                    {displayInitials}
                  </div>
                  <div>
                    <p className="text-sm font-semibold text-slate-800">{displayName}</p>
                    <p className="text-xs text-slate-500 cursor-pointer hover:text-red-500 transition-colors"
                      onClick={onSignOut}>
                      Sign out
                    </p>
                  </div>
                </div>
              </>
            )}
          </div>
        </aside>

        {/* Main content */}
        <main className="flex-1 flex flex-col min-w-0">
          <header className="h-16 bg-white/80 backdrop-blur-md border-b border-slate-200 flex items-center gap-3 px-4 md:px-8 flex-shrink-0 sticky top-0 z-40">
            {/* Hamburger — mobile only */}
            <button
              className="md:hidden p-2 rounded-lg text-slate-500 hover:bg-slate-100 transition-colors flex-shrink-0"
              onClick={() => setSidebarOpen(prev => !prev)}
              aria-label="Toggle sidebar"
            >
              <Menu className="w-5 h-5" aria-hidden="true" />
            </button>

            {/* Search bar — desktop only */}
            <div className="relative hidden md:block w-72">
              <Search className="w-4 h-4 absolute left-3 top-2.5 text-slate-400" />
              <input
                type="text"
                placeholder={mode === 'admin' ? 'Search distributors, investors…' : 'Search investors, funds, or PAN…'}
                className="w-full pl-10 pr-4 py-2 text-sm bg-slate-100/50 border border-slate-200 rounded-full focus:bg-white focus:ring-2 focus:ring-blue-100 focus:border-blue-500 transition-all outline-none"
              />
            </div>

            {/* Spacer pushes right-side actions to the end on mobile */}
            <div className="flex-1" />

            <div className="flex items-center gap-2 md:gap-4">
              {/* Mode switcher — desktop only */}
              <div className="hidden md:flex items-center bg-slate-100 rounded-xl p-1 gap-0.5">
                <button
                  onClick={() => switchMode('distributor')}
                  className={`px-3.5 py-1.5 text-xs font-semibold rounded-lg transition-all ${mode === 'distributor'
                    ? 'bg-white shadow-sm text-slate-900'
                    : 'text-slate-500 hover:text-slate-700'
                    }`}
                >
                  Distributor
                </button>
                {canAccessAdmin && (
                  <button
                    onClick={() => switchMode('admin')}
                    className={`px-3.5 py-1.5 text-xs font-semibold rounded-lg transition-all ${mode === 'admin'
                      ? 'bg-[#0B1B3E] shadow-sm text-white'
                      : 'text-slate-500 hover:text-slate-700'
                      }`}
                  >
                    Admin
                  </button>
                )}
              </div>

              <button
                onClick={() => mode === 'distributor' && navigate('/distributor/notifications')}
                aria-label={
                  unreadNotifs > 0
                    ? `View notifications (${unreadNotifs} unread)`
                    : 'View notifications'
                }
                className="text-slate-400 hover:text-slate-600 transition-colors relative"
              >
                <Bell className="w-5 h-5" aria-hidden="true" />
                {unreadNotifs > 0 && (
                  <span
                    aria-hidden="true"
                    className="absolute -top-2 -right-2 min-w-5 h-5 px-1 bg-red-500 border border-white rounded-full text-[10px] font-bold text-white flex items-center justify-center leading-none"
                  >
                    {unreadNotifs > 99 ? '99+' : unreadNotifs}
                  </span>
                )}
              </button>

              {mode === 'distributor' ? (
                <button
                  onClick={() => navigate('/distributor/investor-onboarding')}
                  aria-label="New Onboarding"
                  className="flex items-center gap-1 px-3 md:px-4 py-2 text-sm font-medium bg-[#0B1B3E] text-white rounded-lg shadow-sm hover:bg-[#1A3066] transition-colors"
                >
                  <span aria-hidden="true" className="font-bold text-base leading-none">+</span>
                  <span className="hidden md:inline"> New Onboarding</span>
                </button>
              ) : (
                <button
                  onClick={() => navigate('/admin/distributor-mgmt')}
                  aria-label="Add Distributor"
                  className="flex items-center gap-1 px-3 md:px-4 py-2 text-sm font-medium bg-violet-700 text-white rounded-lg shadow-sm hover:bg-violet-800 transition-colors"
                >
                  <span aria-hidden="true" className="font-bold text-base leading-none">+</span>
                  <span className="hidden md:inline"> Add Distributor</span>
                </button>
              )}
            </div>
          </header>

          <div className="flex-1 overflow-auto bg-[#F1F5F9]">
            <Outlet />
          </div>
        </main>
      </div>
    </>
  );
}

const NavItem: React.FC<{
  to: string;
  icon: React.ReactNode;
  label: string;
  badge?: number;
  adminMode?: boolean;
  comingSoon?: boolean;
}> = ({ to, icon, label, badge, adminMode, comingSoon }) => {
  if (comingSoon) {
    return (
      <div className="w-full flex items-center gap-3 px-4 py-2.5 text-sm font-medium rounded-lg text-slate-300 cursor-not-allowed select-none">
        <span className="text-slate-300">{icon}</span>
        <span className="flex-1">{label}</span>
        <span className="ml-auto text-[9px] font-bold bg-slate-100 text-slate-400 px-1.5 py-0.5 rounded-md uppercase tracking-wide">
          Soon
        </span>
      </div>
    );
  }

  return (
    <NavLink
      to={to}
      className={({ isActive }) => `w-full flex items-center gap-3 px-4 py-2.5 text-sm font-medium rounded-lg transition-colors text-left ${
        isActive
          ? adminMode
            ? 'bg-violet-50 text-violet-700'
            : 'bg-blue-50/80 text-blue-700'
          : 'text-slate-600 hover:bg-slate-50 hover:text-slate-900'
      }`}
    >
      {({ isActive }) => (
        <>
          <span className={`${isActive ? (adminMode ? 'text-violet-600' : 'text-blue-600') : 'text-slate-400'}`}>
            {icon}
          </span>
          <span className="flex-1">{label}</span>
          {badge !== undefined && badge > 0 && (
            <span className="ml-auto w-5 h-5 rounded-full bg-red-500 text-white text-[10px] font-bold flex items-center justify-center">
              {badge}
            </span>
          )}
        </>
      )}
    </NavLink>
  );
}
