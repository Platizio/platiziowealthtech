import InvestorNomineeManager from '../components/InvestorNomineeManager';

/**
 * Investor "Nominees" page (REQUIREMENT #4). Thin wrapper around the shared
 * {@link InvestorNomineeManager}: lists the investor's nominees in the rich
 * shape (GET /investor/nominations), pre-fills distributor-captured fields
 * read-only, lets the investor add nominees (POST /investor/nominations),
 * complete existing ones (PUT /investor/nominations/{id}), upload nominee ID
 * documents (PUT /investor/nominations/{id}/document) and opt out of
 * nomination (POST /investor/nominations/opt-out).
 *
 * Compliance: NO pre-checked boxes and NO defaulted values — nothing is opted
 * in/out until the investor acts. Styled to match InvestorInvest.tsx.
 */
export default function InvestorNominees() {
  return (
    <div className="mx-auto max-w-5xl space-y-6 p-8">
      <div>
        <h1 className="text-2xl font-semibold text-slate-800">Nominees</h1>
        <p className="mt-1 text-sm text-slate-500">
          Nominate who should receive your investments. You can add one or more nominees
          (allocations totalling 100%), complete details your distributor already captured,
          or choose not to nominate.
        </p>
      </div>
      <InvestorNomineeManager />
    </div>
  );
}
