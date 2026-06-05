type PaginationProps = {
  page: number;
  size: number;
  totalPages: number;
  totalElements: number;
  onPageChange: (page: number) => void;
  onSizeChange?: (size: number) => void;
};

export default function Pagination({
  page,
  size,
  totalPages,
  totalElements,
  onPageChange,
  onSizeChange,
}: PaginationProps) {
  const safeTotalPages = Math.max(totalPages || 0, 1);
  const firstItem = totalElements === 0 ? 0 : page * size + 1;
  const lastItem = Math.min((page + 1) * size, totalElements);
  const sizeOptions = Array.from(new Set([10, 12, 20, 50, 100, size]))
    .filter(option => Number.isFinite(option) && option > 0)
    .sort((a, b) => a - b);

  return (
    <div className="flex flex-wrap items-center justify-between gap-3 border-t border-slate-100 px-4 py-3">
      <p className="text-xs font-medium text-slate-500">
        Showing {firstItem}-{lastItem} of {totalElements}
      </p>
      <div className="flex items-center gap-3">
        {onSizeChange && (
          <select
            value={size}
            onChange={e => onSizeChange(Number(e.target.value))}
            className="rounded-lg border border-slate-200 bg-white px-2 py-1.5 text-xs font-semibold text-slate-600 outline-none"
          >
            {sizeOptions.map(option => (
              <option key={option} value={option}>{option} / page</option>
            ))}
          </select>
        )}
        <div className="flex items-center gap-2">
          <button
            type="button"
            onClick={() => onPageChange(page - 1)}
            disabled={page <= 0}
            className="rounded-lg border border-slate-200 px-3 py-1.5 text-xs font-semibold text-slate-600 transition-colors hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-40"
          >
            Previous
          </button>
          <span className="text-xs font-semibold text-slate-500">
            Page {Math.min(page + 1, safeTotalPages)} of {safeTotalPages}
          </span>
          <button
            type="button"
            onClick={() => onPageChange(page + 1)}
            disabled={page >= safeTotalPages - 1}
            className="rounded-lg border border-slate-200 px-3 py-1.5 text-xs font-semibold text-slate-600 transition-colors hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-40"
          >
            Next
          </button>
        </div>
      </div>
    </div>
  );
}
