import React, { useState, useEffect } from 'react';
import { motion } from 'motion/react';
import { CheckCircle2, XCircle, AlertCircle, Clock, RefreshCw } from 'lucide-react';
import { apiFetch } from '../config/api';

type NotifType = 'success' | 'error' | 'warning' | 'info';

interface Notif {
  id: string;
  type: NotifType;
  category: string;
  title: string;
  message: string;
  time: string;
  read: boolean;
}

const typeConfig: Record<NotifType, { borderColor: string; iconBg: string; icon: React.ReactNode }> = {
  success: {
    borderColor: 'border-l-green-500',
    iconBg: 'bg-green-100',
    icon: <CheckCircle2 className="w-4 h-4 text-green-600" />,
  },
  error: {
    borderColor: 'border-l-red-500',
    iconBg: 'bg-red-100',
    icon: <XCircle className="w-4 h-4 text-red-600" />,
  },
  warning: {
    borderColor: 'border-l-amber-500',
    iconBg: 'bg-amber-100',
    icon: <AlertCircle className="w-4 h-4 text-amber-600" />,
  },
  info: {
    borderColor: 'border-l-blue-500',
    iconBg: 'bg-blue-100',
    icon: <Clock className="w-4 h-4 text-blue-600" />,
  },
};

const CATEGORIES = ['All', 'KYC', 'Transaction', 'Payment', 'SIP', 'Redemption', 'Mandate', 'General'];

function mapBackendType(type: string): { notifType: NotifType, category: string } {
  switch (type) {
    case 'KYC_COMPLETED': return { notifType: 'success', category: 'KYC' };
    case 'KYC_FAILED': return { notifType: 'error', category: 'KYC' };
    case 'PAYMENT_PENDING': return { notifType: 'warning', category: 'Payment' };
    case 'PAYMENT_FAILED': return { notifType: 'error', category: 'Payment' };
    case 'TRANSACTION_SUCCESSFUL': return { notifType: 'success', category: 'Transaction' };
    case 'TRANSACTION_FAILED': return { notifType: 'error', category: 'Transaction' };
    case 'REDEMPTION_SUBMITTED': return { notifType: 'info', category: 'Redemption' };
    case 'REDEMPTION_SUCCESSFUL': return { notifType: 'success', category: 'Redemption' };
    case 'BANK_CREDIT_COMPLETED': return { notifType: 'success', category: 'Redemption' };
    case 'RECURRING_PLAN_EVENT': return { notifType: 'info', category: 'SIP' };
    case 'GENERAL':
    default:
      return { notifType: 'info', category: 'General' };
  }
}

export default function Notifications({ userData }: { userData?: any }) {
  const [notifications, setNotifications] = useState<Notif[]>([]);
  const [filter, setFilter] = useState('All');
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!userData?.id) return;

    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
    };

    const fetchNotifications = async () => {
      try {
        setLoading(true);
        const res = await apiFetch(`/notifications/distributor/${userData.id}`, { headers });
        if (res.ok) {
          const data = await res.json();
          const mapped: Notif[] = data.map((n: any) => {
            const { notifType, category } = mapBackendType(n.type);
            return {
              id: n.id,
              type: notifType,
              category,
              title: n.title,
              message: n.message,
              time: new Date(n.createdAt || Date.now()).toLocaleString(),
              read: n.readFlag === true
            };
          });
          setNotifications(mapped);
        }
      } catch (err) {
        console.error('Failed to fetch notifications', err);
      } finally {
        setLoading(false);
      }
    };

    fetchNotifications();
  }, [userData]);

  const markAllRead = () => {
    // Optimistic UI update
    setNotifications(prev => prev.map(n => ({ ...n, read: true })));
    // Real implementation would batch patch all unread to backend
  };

  const markRead = async (id: string) => {
    const n = notifications.find(x => x.id === id);
    if (!n || n.read) return;

    // Optimistic update
    setNotifications(prev => prev.map(x => x.id === id ? { ...x, read: true } : x));

    try {
      const headers: Record<string, string> = { 'Content-Type': 'application/json' };

      await apiFetch(`/notifications/${id}/read`, {
        method: 'PATCH',
        headers
      });
    } catch (err) {
      console.error('Failed to mark read', err);
    }
  };

  const filtered = filter === 'All'
    ? notifications
    : notifications.filter(n => n.category === filter);

  const unread = notifications.filter(n => !n.read).length;

  return (
    <motion.div initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} className="p-8 max-w-4xl">
      <div className="flex justify-between items-center mb-8">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight text-slate-800">Notifications</h1>
          <p className="text-slate-500 text-sm mt-1">
            {unread > 0 ? `${unread} unread notification${unread !== 1 ? 's' : ''}` : 'All caught up'}
          </p>
        </div>
        {unread > 0 && (
          <button
            onClick={markAllRead}
            className="px-4 py-2 text-sm font-medium text-slate-600 border border-slate-200 rounded-lg hover:bg-slate-50 transition-colors"
          >
            Mark all as read
          </button>
        )}
      </div>

      {/* Category filter pills */}
      <div className="flex gap-2 mb-6 flex-wrap">
        {CATEGORIES.map(c => (
          <button
            key={c}
            onClick={() => setFilter(c)}
            className={`px-4 py-2 text-sm font-medium rounded-lg transition-colors ${
              filter === c
                ? 'bg-[#0B1B3E] text-white'
                : 'bg-white border border-slate-200 text-slate-600 hover:bg-slate-50'
            }`}
          >
            {c}
          </button>
        ))}
      </div>

      <div className="space-y-3">
        {loading ? (
           <div className="py-20 text-center text-slate-500">Loading notifications...</div>
        ) : filtered.length === 0 ? (
          <div className="py-20 text-center">
            <div className="w-16 h-16 mx-auto mb-4 bg-slate-100 rounded-full flex items-center justify-center">
              <CheckCircle2 className="w-8 h-8 text-slate-300" />
            </div>
            <p className="text-slate-500 font-medium">No notifications in this category</p>
          </div>
        ) : (
          filtered.map(n => {
            const cfg = typeConfig[n.type] || typeConfig['info'];
            return (
              <div
                key={n.id}
                onClick={() => markRead(n.id)}
                className={`bg-white rounded-2xl p-5 shadow-sm border border-l-4 border-slate-200 ${cfg.borderColor} flex gap-4 transition-all cursor-pointer hover:shadow-md ${
                  !n.read ? 'ring-1 ring-blue-100 bg-blue-50/20' : ''
                }`}
              >
                <div className={`w-9 h-9 rounded-xl flex items-center justify-center flex-shrink-0 ${cfg.iconBg}`}>
                  {cfg.icon}
                </div>
                <div className="flex-1 min-w-0">
                  <div className="flex justify-between items-start gap-2">
                    <div className="flex items-center gap-2">
                      <p className="text-sm font-semibold text-slate-800">{n.title}</p>
                      {!n.read && (
                        <span className="w-1.5 h-1.5 rounded-full bg-blue-500 flex-shrink-0" />
                      )}
                    </div>
                    <span className="text-xs text-slate-400 whitespace-nowrap flex-shrink-0">{n.time}</span>
                  </div>
                  <p className="text-sm text-slate-500 mt-1 leading-relaxed">{n.message}</p>
                  <span className="inline-block mt-2 px-2 py-0.5 bg-slate-100 text-slate-500 text-[10px] font-bold uppercase tracking-wider rounded">
                    {n.category}
                  </span>
                </div>
              </div>
            );
          })
        )}
      </div>
    </motion.div>
  );
}
