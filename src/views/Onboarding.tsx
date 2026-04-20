import React, { useState } from 'react';
import { motion, AnimatePresence } from 'motion/react';
import { 
  CheckCircle2, 
  UploadCloud, 
  Building, 
  FileText,
  ChevronRight,
  ArrowRight
} from 'lucide-react';

export default function Onboarding({ onComplete }: { onComplete: () => void }) {
  const [step, setStep] = useState(1);

  const nextStep = () => {
    if (step === 4) {
      onComplete();
    } else {
      setStep(step + 1);
    }
  };

  const prevStep = () => setStep(step - 1);

  return (
    <div className="min-h-screen bg-[#F1F5F9] flex items-center justify-center p-6">
      <div className="w-full max-w-4xl grid md:grid-cols-3 bg-white rounded-3xl shadow-sm border border-slate-200 overflow-hidden min-h-[600px]">
        
        {/* Sidebar */}
        <div className="bg-[#0B1B3E] p-10 text-white flex flex-col hidden md:flex">
          <div className="flex items-center gap-3 mb-16">
            <div className="w-8 h-8 bg-blue-500 rounded-lg flex items-center justify-center font-bold">A</div>
            <span className="font-semibold text-xl tracking-tight">Apex Wealth</span>
          </div>

          <h2 className="text-2xl font-light mb-12">Institutional<br/>Curator Setup</h2>

          <div className="space-y-8 flex-1">
            {[
              { num: 1, label: 'Identity Verification' },
              { num: 2, label: 'KYC & Compliance' },
              { num: 3, label: 'Financial Routing' },
              { num: 4, label: 'Final Dossier' }
            ].map((s) => (
              <div key={s.num} className={`flex items-center gap-4 transition-colors ${step >= s.num ? 'text-white' : 'text-slate-500'}`}>
                <div className={`w-8 h-8 rounded-full border border-current flex items-center justify-center text-sm ${step === s.num ? 'bg-white text-[#0B1B3E]' : step > s.num ? 'bg-blue-500 border-blue-500 text-white' : ''}`}>
                  {step > s.num ? <CheckCircle2 className="w-5 h-5" /> : s.num}
                </div>
                <span className="font-medium text-sm">{s.label}</span>
              </div>
            ))}
          </div>

          <div className="text-slate-400 text-xs mt-auto pt-8 border-t border-slate-700/50">
            Secure, 256-bit encrypted channel.
          </div>
        </div>

        {/* Form Content */}
        <div className="md:col-span-2 p-10 flex flex-col bg-white">
          <div className="flex-1">
            <AnimatePresence mode="wait">
              {step === 1 && (
                <motion.div key="step1" initial={{ opacity: 0, x: 10 }} animate={{ opacity: 1, x: 0 }} exit={{ opacity: 0, x: -10 }}>
                  <h3 className="text-xl font-semibold mb-2">Personal Identity</h3>
                  <p className="text-slate-500 text-sm mb-8">Enter your details as they appear on your government IDs.</p>
                  
                  <div className="space-y-5">
                    <div className="grid grid-cols-2 gap-5">
                      <div>
                        <label className="block text-xs font-semibold text-slate-500 uppercase tracking-wider mb-2">First Name</label>
                        <input type="text" className="w-full bg-slate-50 border border-slate-200 rounded-lg px-4 py-3 text-sm focus:ring-2 focus:ring-blue-100 focus:border-blue-500 outline-none transition-all" placeholder="e.g. Aditya" />
                      </div>
                      <div>
                        <label className="block text-xs font-semibold text-slate-500 uppercase tracking-wider mb-2">Last Name</label>
                        <input type="text" className="w-full bg-slate-50 border border-slate-200 rounded-lg px-4 py-3 text-sm focus:ring-2 focus:ring-blue-100 focus:border-blue-500 outline-none transition-all" placeholder="e.g. Sharma" />
                      </div>
                    </div>
                    <div>
                      <label className="block text-xs font-semibold text-slate-500 uppercase tracking-wider mb-2">Email Address</label>
                      <input type="email" className="w-full bg-slate-50 border border-slate-200 rounded-lg px-4 py-3 text-sm focus:ring-2 focus:ring-blue-100 focus:border-blue-500 outline-none transition-all" placeholder="aditya@apexwealth.in" />
                    </div>
                    <div>
                      <label className="block text-xs font-semibold text-slate-500 uppercase tracking-wider mb-2">PAN Number</label>
                      <input type="text" className="w-full bg-slate-50 border border-slate-200 rounded-lg px-4 py-3 text-sm focus:ring-2 focus:ring-blue-100 focus:border-blue-500 outline-none transition-all uppercase" placeholder="ABCDE1234F" />
                    </div>
                  </div>
                </motion.div>
              )}

              {step === 2 && (
                <motion.div key="step2" initial={{ opacity: 0, x: 10 }} animate={{ opacity: 1, x: 0 }} exit={{ opacity: 0, x: -10 }}>
                  <h3 className="text-xl font-semibold mb-2">KYC & Compliance</h3>
                  <p className="text-slate-500 text-sm mb-8">Upload your certification and compliance documents.</p>
                  
                  <div className="space-y-6">
                    <div>
                      <label className="block text-xs font-semibold text-slate-500 uppercase tracking-wider mb-2">ARN Number</label>
                      <input type="text" className="w-full bg-slate-50 border border-slate-200 rounded-lg px-4 py-3 text-sm focus:ring-2 focus:ring-blue-100 focus:border-blue-500 outline-none transition-all" placeholder="ARN-XXXXXX" />
                    </div>

                    <div>
                      <label className="block text-xs font-semibold text-slate-500 uppercase tracking-wider mb-2">NISM Certificate</label>
                      <div className="mt-1 flex justify-center px-6 pt-5 pb-6 border-2 border-slate-200 border-dashed rounded-xl hover:bg-slate-50 transition-colors cursor-pointer group">
                        <div className="space-y-1 text-center">
                          <UploadCloud className="mx-auto h-8 w-8 text-slate-400 group-hover:text-blue-500 transition-colors" />
                          <div className="flex text-sm text-slate-600 mt-2">
                            <span className="relative cursor-pointer bg-transparent rounded-md font-medium text-blue-600 hover:text-blue-500">
                              Upload a file
                            </span>
                            <p className="pl-1">or drag and drop</p>
                          </div>
                          <p className="text-xs text-slate-500">PDF, PNG, JPG up to 10MB</p>
                        </div>
                      </div>
                    </div>
                  </div>
                </motion.div>
              )}

              {step === 3 && (
                <motion.div key="step3" initial={{ opacity: 0, x: 10 }} animate={{ opacity: 1, x: 0 }} exit={{ opacity: 0, x: -10 }}>
                  <h3 className="text-xl font-semibold mb-2">Financial Routing</h3>
                  <p className="text-slate-500 text-sm mb-8">Set up your disbursement account for brokerage settlements.</p>
                  
                  <div className="space-y-5">
                    <div>
                      <label className="block text-xs font-semibold text-slate-500 uppercase tracking-wider mb-2">IFSC Code</label>
                      <div className="relative">
                        <input type="text" className="w-full bg-slate-50 border border-slate-200 rounded-lg px-4 py-3 text-sm focus:ring-2 focus:ring-blue-100 focus:border-blue-500 outline-none transition-all uppercase" placeholder="HDFC0001234" defaultValue="HDFC0001234" />
                        <div className="absolute right-3 top-3 flex items-center gap-1 text-green-600 text-xs font-medium">
                          <CheckCircle2 className="w-4 h-4" /> Validated
                        </div>
                      </div>
                      <p className="text-xs text-slate-500 mt-2 flex items-center gap-1"><Building className="w-3 h-3" /> HDFC Bank Ltd, Mumbai Main</p>
                    </div>
                    <div>
                      <label className="block text-xs font-semibold text-slate-500 uppercase tracking-wider mb-2">Account Number</label>
                      <input type="password" className="w-full bg-slate-50 border border-slate-200 rounded-lg px-4 py-3 text-sm focus:ring-2 focus:ring-blue-100 focus:border-blue-500 outline-none transition-all" placeholder="Enter account number" />
                    </div>
                    <div>
                      <label className="block text-xs font-semibold text-slate-500 uppercase tracking-wider mb-2">Re-enter Account Number</label>
                      <input type="text" className="w-full bg-slate-50 border border-slate-200 rounded-lg px-4 py-3 text-sm focus:ring-2 focus:ring-blue-100 focus:border-blue-500 outline-none transition-all" placeholder="Re-enter to confirm" />
                    </div>
                  </div>
                </motion.div>
              )}

              {step === 4 && (
                <motion.div key="step4" initial={{ opacity: 0, x: 10 }} animate={{ opacity: 1, x: 0 }} exit={{ opacity: 0, x: -10 }}>
                  <h3 className="text-xl font-semibold mb-2">Final Dossier</h3>
                  <p className="text-slate-500 text-sm mb-8">Review your information before final submission.</p>
                  
                  <div className="space-y-6">
                    <div className="bg-slate-50 rounded-xl p-5 border border-slate-100">
                      <div className="flex justify-between items-start mb-4">
                        <h4 className="text-sm font-semibold flex items-center gap-2"><FileText className="w-4 h-4 text-slate-400" /> Identity Details</h4>
                        <button onClick={() => setStep(1)} className="text-xs font-semibold text-blue-600">Edit</button>
                      </div>
                      <div className="grid grid-cols-2 gap-y-3 text-sm">
                        <div><span className="text-slate-500 text-xs block">Name</span>Aditya Sharma</div>
                        <div><span className="text-slate-500 text-xs block">PAN</span>ABCDE1234F</div>
                        <div className="col-span-2"><span className="text-slate-500 text-xs block">Email</span>aditya@apexwealth.in</div>
                      </div>
                    </div>

                    <div className="bg-slate-50 rounded-xl p-5 border border-slate-100">
                      <div className="flex justify-between items-start mb-4">
                        <h4 className="text-sm font-semibold flex items-center gap-2"><Building className="w-4 h-4 text-slate-400" /> Professional Details</h4>
                        <button onClick={() => setStep(2)} className="text-xs font-semibold text-blue-600">Edit</button>
                      </div>
                      <div className="grid grid-cols-2 gap-y-3 text-sm">
                        <div><span className="text-slate-500 text-xs block">ARN</span>ARN-102943</div>
                        <div><span className="text-slate-500 text-xs block">Bank</span>HDFC Bank Ltd</div>
                        <div className="col-span-2"><span className="text-slate-500 text-xs block">Account</span>•••• •••• 9012</div>
                      </div>
                    </div>
                  </div>
                </motion.div>
              )}
            </AnimatePresence>
          </div>

          {/* Footer Actions */}
          <div className="mt-10 pt-6 border-t border-slate-100 flex items-center justify-between">
            {step > 1 ? (
              <button 
                onClick={prevStep}
                className="px-6 py-2.5 text-sm font-medium text-slate-600 hover:bg-slate-50 rounded-lg transition-colors"
              >
                Back
              </button>
            ) : <div />}
            
            <button 
              onClick={nextStep}
              className="px-6 py-2.5 bg-[#0B1B3E] text-white text-sm font-medium rounded-lg hover:bg-[#1A3066] transition-colors flex items-center gap-2"
            >
              {step === 4 ? 'Complete Onboarding' : 'Continue'}
              {step !== 4 && <ArrowRight className="w-4 h-4" />}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
