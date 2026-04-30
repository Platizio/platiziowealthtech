import React, { useState } from 'react';
import { motion } from 'motion/react';
import { CheckCircle2, XCircle, AlertCircle, Clock, RefreshCw } from 'lucide-react';

type NotifType = 'success' | 'error' | 'warning' | 'info';

interface Notif {
  id: number;
  type: NotifType;
  category: string;
  title: string;
  message: string;
  time: string;
  read: boolean;
}

const initialNotifications: Notif[] = [
  {
    id: 1, type: 'success', category: 'KYC',
    title: 'KYC Verified',
    message: "Sunita Kapur's KYC has been successfully completed. She is now eligible for transactions.",
    time: '10 min ago', read: false,
  },
  {
    id: 2, type: 'warning', category: 'Payment',
    title: 'Payment Pending',
    message: "Meera Iyer has not authorized the payment link for Parag Parikh Flexi Cap SIP ₹5,000.",
    time: '45 min ago', read: false,
  },
  {
    id: 3, type: 'success', category: 'Transaction',
    title: 'Order Successful',
    message: 'SIP order for Aditya Sharma in HDFC Large & Mid Cap Fund (₹5,000) has been processed successfully.',
    time: '2 hrs ago', read: false,
  },
  {
    id: 4, type: 'error', category: 'Transaction',
    title: 'Order Failed',
    message: 'Lumpsum order for Tech Innovations PF in Quant Small Cap Fund failed. Reason: Insufficient funds.',
    time: '5 hrs ago', read: true,
  },
  {
    id: 5, type: 'info', category: 'SIP',
    title: 'SIP Due Tomorrow',
    message: 'Monthly SIP of ₹10,000 for Rahul Verma (ICICI Prudential Bluechip) is due on 21st Apr.',
    time: '1 day ago', read: true,
  },
  {
    id: 6, type: 'success', category: 'Redemption',
    title: 'Bank Credit Completed',
    message: "Redemption proceeds of ₹50,000 have been credited to Sunita Kapur's registered bank account.",
    time: '2 days ago', read: true,
  },
  {
    id: 7, type: 'error', category: 'KYC',
    title: 'KYC Failed',
    message: 'KYC verification for Prakash Mehta failed. Document mismatch detected. Please re-initiate.',
    time: '3 days ago', read: true,
  },
  {
    id: 8, type: 'info', category: 'SIP',
    title: 'SIP Paused',
    message: "Vikram Singh's SIP in Kotak Corporate Bond Fund has been paused as per investor request.",
    time: '4 days ago', read: true,
  },
  {
    id: 9, type: 'warning', category: 'Mandate',
    title: 'Mandate Setup Pending',
    message: "eNACH mandate for Nisha Patel's SBI Liquid Fund SIP is still pending registration.",
    time: '5 days ago', read: true,
  },
];

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

const CATEGORIES = ['All', 'KYC', 'Transaction', 'Payment', 'SIP', 'Redemption', 'Mandate'];

export default function Notifications() {
  const [notifications, setNotifications] = useState(initialNotifications);
  const [filter, setFilter] = useState('All');

  const markAllRead = () =>
    setNotifications(prev => prev.map(n => ({ ...n, read: true })));

  const markRead = (id: number) =>
    setNotifications(prev => prev.map(n => n.id === id ? { ...n, read: true } : n));

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
        {filtered.length === 0 && (
          <div className="py-20 text-center">
            <div className="w-16 h-16 mx-auto mb-4 bg-slate-100 rounded-full flex items-center justify-center">
              <CheckCircle2 className="w-8 h-8 text-slate-300" />
            </div>
            <p className="text-slate-500 font-medium">No notifications in this category</p>
          </div>
        )}
        {filtered.map(n => {
          const cfg = typeConfig[n.type];
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
        })}
      </div>
    </motion.div>
  );
}
