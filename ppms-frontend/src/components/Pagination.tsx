import type { PagedResponse } from '../types/paged'

interface PaginationProps<T> {
  data: PagedResponse<T>
  onPageChange: (page: number) => void
  onPageSizeChange: (size: number) => void
  pageSizeOptions?: number[]
}

const DEFAULT_PAGE_SIZE_OPTIONS = [10, 20, 50]

/**
 * Reusable pagination control bar.
 *
 * Renders nothing when totalPages <= 1 AND the page size selector would show
 * the same or larger count than totalElements (i.e. everything already fits on one page).
 * This keeps the UI clean for short lists.
 */
export function Pagination<T>({
  data,
  onPageChange,
  onPageSizeChange,
  pageSizeOptions = DEFAULT_PAGE_SIZE_OPTIONS,
}: PaginationProps<T>) {
  // Only show pagination controls when there is more than one page
  if (data.totalPages <= 1) return null

  const { page, pageSize, totalElements, totalPages } = data

  const startItem = page * pageSize + 1
  const endItem = Math.min((page + 1) * pageSize, totalElements)

  return (
    <div className="flex flex-col sm:flex-row items-center justify-between gap-3 px-1 py-3 border-t border-gray-200 text-sm text-gray-600">
      {/* Left: showing X–Y of Z */}
      <span className="text-gray-500">
        Showing {startItem}–{endItem} of {totalElements.toLocaleString()}
      </span>

      {/* Center: prev / page buttons / next */}
      <div className="flex items-center gap-1">
        <button
          onClick={() => onPageChange(page - 1)}
          disabled={!data.hasPrevious}
          className="px-2 py-1 rounded border border-gray-300 disabled:opacity-40 disabled:cursor-not-allowed hover:bg-gray-100"
          aria-label="Previous page"
        >
          ‹
        </button>

        {buildPageNumbers(page, totalPages).map((p, i) =>
          p === '…' ? (
            <span key={`ellipsis-${i}`} className="px-2 py-1 text-gray-400">
              …
            </span>
          ) : (
            <button
              key={p}
              onClick={() => onPageChange(p as number)}
              className={`px-2.5 py-1 rounded border text-sm ${
                p === page
                  ? 'bg-blue-600 text-white border-blue-600'
                  : 'border-gray-300 hover:bg-gray-100'
              }`}
            >
              {(p as number) + 1}
            </button>
          )
        )}

        <button
          onClick={() => onPageChange(page + 1)}
          disabled={!data.hasNext}
          className="px-2 py-1 rounded border border-gray-300 disabled:opacity-40 disabled:cursor-not-allowed hover:bg-gray-100"
          aria-label="Next page"
        >
          ›
        </button>
      </div>

      {/* Right: page size selector */}
      <div className="flex items-center gap-2">
        <label htmlFor="page-size-select" className="text-gray-500">
          Rows:
        </label>
        <select
          id="page-size-select"
          value={pageSize}
          onChange={e => {
            onPageSizeChange(Number(e.target.value))
            onPageChange(0) // reset to first page on size change
          }}
          className="border border-gray-300 rounded px-2 py-1 text-sm bg-white focus:outline-none focus:ring-1 focus:ring-blue-500"
        >
          {pageSizeOptions.map(opt => (
            <option key={opt} value={opt}>
              {opt}
            </option>
          ))}
        </select>
      </div>
    </div>
  )
}

/**
 * Produces the list of page indices (0-based) to render as buttons, inserting
 * '…' ellipsis markers to keep the control compact for large page counts.
 *
 * Example for page=5, totalPages=20:
 *   [0, '…', 3, 4, 5, 6, 7, '…', 19]
 */
function buildPageNumbers(current: number, total: number): (number | '…')[] {
  if (total <= 7) {
    return Array.from({ length: total }, (_, i) => i)
  }

  const pages: (number | '…')[] = []
  const addPage = (p: number) => {
    if (!pages.includes(p)) pages.push(p)
  }

  addPage(0)
  if (current - 2 > 1) pages.push('…')
  for (let p = Math.max(1, current - 2); p <= Math.min(total - 2, current + 2); p++) {
    addPage(p)
  }
  if (current + 2 < total - 2) pages.push('…')
  addPage(total - 1)

  return pages
}
