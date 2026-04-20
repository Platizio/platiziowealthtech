import React from 'react';
import { motion } from 'motion/react';
import {
  PieChart, Pie, Cell, ResponsiveContainer, Tooltip
} from 'recharts';
import { ArrowUpRight, Users, AlertCircle, Clock, TrendingUp } from 'lucide-react';

const donutData = [
  { name: 'Successful', value: 65, color: '#22c55e' },
  { name: 'Pending', value: 25, color: '#eab308' },
  { name: 'Failed', value: 10, color: '#ef4444' },
];

const recentActivity = [
  { action: 'KYC Verified', name: 'Sunita Kapur', time: '10 min ago', dot: 'bg-green-500' },
  { action: 'Payment Pending', name: 'Meera Iyer', time: '45 min ago', dot: 'bg-amber-500' },
  { action: 'Order Successful', name: 'Aditya Sharma', time: '2 hrs ago', dot: 'bg-blue-500' },
  { action: 'Order Failed', name: 'Tech Innovations PF', time: '5 hrs ago', dot: 'bg-red-500' },
  { action: 'SIP Due Tomorrow', name: 'Rahul Verma', time: '1 day ago', dot: 'bg-slate-400' },
];

export default function Dashboard() {
  return (
    <motion.div
      initial={{ opacity: 0, y: 10 }}
      animate={{ opacity: 1, y: 0 }}
      className="p-8 space-y-6"
    >
      <div className="flex justify-between items-end mb-2">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight text-slate-800">Distributor Overview</h1>
          <p className="text-slate-500 text-sm mt-1">Real-time snapshot of your AUM and business growth</p>
        </div>
        <div className="flex gap-2 text-xs font-semibold text-slate-500 uppercase tracking-wider bg-slate-100 p-1 rounded-lg">
          <button className="px-3 py-1.5 bg-white shadow-sm rounded text-slate-900 transition-all">Today</button>
          <button className="px-3 py-1.5 hover:text-slate-700 transition-all">Weekly</button>
          <button className="px-3 py-1.5 hover:text-slate-700 transition-all">Monthly</button>
        </div>
      </div>

      {/* KPI Cards — PRD-aligned: onboarding pending, KYC pending, transactions pending, earnings */}
      <div className="grid grid-cols-4 gap-6">
        <KPI
          label="Total AUM"
          value="₹42.58 Cr"
          trend="↑ 4.2% from last month"
          trendColor="text-green-500"
          icon={<TrendingUp className="w-5 h-5 text-blue-500" />}
        />
        <KPI
          label="Onboarding Pending"
          value="8"
          trend="3 KYC · 3 Bank · 2 Risk"
          trendColor="text-orange-500"
          icon={<Users className="w-5 h-5 text-orange-400" />}
        />
        <KPI
          label="Transactions Pending"
          value="14"
          trend="6 Awaiting investor action"
          trendColor="text-amber-500"
          icon={<Clock className="w-5 h-5 text-amber-400" />}
        />
        <KPI
          label="Est. Brokerage"
          value="₹40,200"
          trend="Expected by 15th May"
          trendColor="text-slate-500"
          icon={<ArrowUpRight className="w-5 h-5 text-slate-400" />}
        />
      </div>

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
            <div className="flex gap-4 min-w-max h-full">
              <KanbanColumn title="KYC Pending" count={3} accent="border-t-amber-400">
                <KanbanCard name="Priya Nair" detail="Selfie upload required" days="Today" status="kyc" />
                <KanbanCard name="Prakash Mehta" detail="Document mismatch" days="2 days ago" status="failed" />
                <KanbanCard name="Nisha Patel" detail="In progress" days="3 days ago" status="kyc" />
              </KanbanColumn>
              <KanbanColumn title="Bank Pending" count={3} accent="border-t-blue-400">
                <KanbanCard name="Vikram Singh" detail="Account not verified" days="1 day ago" status="bank" />
                <KanbanCard name="Rajesh Kumar" detail="IFSC mismatch" days="4 days ago" status="bank" />
                <KanbanCard name="Anjali Desai" detail="Verification pending" days="Yesterday" status="bank" />
              </KanbanColumn>
              <KanbanColumn title="Ready to Invest" count={5} accent="border-t-green-400">
                <KanbanCard name="Aditya Sharma" detail="₹5L Lumpsum" days="Just now" status="ready" />
                <KanbanCard name="Sunita Kapur" detail="SIP Setup" days="1 day ago" status="ready" />
                <KanbanCard name="Rahul Verma" detail="₹15L Institutional" days="Today" status="ready" />
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
                  data={donutData}
                  cx="50%" cy="50%"
                  innerRadius={60} outerRadius={80}
                  paddingAngle={5} dataKey="value" stroke="none"
                >
                  {donutData.map((entry, index) => (
                    <Cell key={`cell-${index}`} fill={entry.color} />
                  ))}
                </Pie>
                <Tooltip
                  contentStyle={{ backgroundColor: '#1A3066', border: 'none', borderRadius: '8px', color: 'white', fontSize: '12px' }}
                  itemStyle={{ color: '#fff' }}
                />
              </PieChart>
            </ResponsiveContainer>
            <div className="absolute inset-0 flex flex-col items-center justify-center pointer-events-none">
              <span className="text-2xl font-bold">142</span>
              <span className="text-[10px] text-blue-200 uppercase tracking-wide">Orders</span>
            </div>
          </div>
          <div className="mt-4 space-y-3">
            {donutData.map(item => (
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

      {/* Recent Activity */}
      <div className="bg-white rounded-2xl shadow-sm border border-slate-200 overflow-hidden">
        <div className="p-5 border-b border-slate-100 flex justify-between items-center">
          <h2 className="font-semibold text-slate-800">Recent Activity</h2>
          <button className="text-[#0B1B3E] text-xs font-semibold flex items-center gap-1 hover:underline">
            View All <ArrowUpRight className="w-3 h-3" />
          </button>
        </div>
        <div className="divide-y divide-slate-100">
          {recentActivity.map((item, i) => (
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
        </div>
      </div>
    </motion.div>
  );
}

function KPI({ label, value, trend, trendColor, icon }: {
  label: string; value: string; trend: string; trendColor: string; icon: React.ReactNode;
}) {
  return (
    <div className="bg-white p-6 rounded-2xl shadow-sm border border-slate-200">
      <div className="flex justify-between items-start mb-3">
        <p className="text-slate-500 text-[10px] font-bold uppercase tracking-wider">{label}</p>
        {icon}
      </div>
      <p className="text-2xl font-semibold text-slate-800">{value}</p>
      <p className={`text-xs mt-3 font-medium ${trendColor}`}>{trend}</p>
    </div>
  );
}

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
  bank: 'bg-blue-100 text-blue-700',
  ready: 'bg-green-100 text-green-700',
  failed: 'bg-red-100 text-red-700',
};

function KanbanCard({ name, detail, days, status }: {
  name: string; detail: string; days: string; status: string;
}) {
  return (
    <div className="bg-white p-3 rounded-lg shadow-sm border border-slate-200 hover:border-blue-300 transition-colors cursor-pointer group">
      <div className="flex justify-between items-start mb-1">
        <p className="text-sm font-semibold text-slate-800 group-hover:text-blue-600 transition-colors">{name}</p>
      </div>
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
