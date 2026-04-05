import { useState, useRef, useEffect } from 'react'
import { createPortal } from 'react-dom'

export interface SelectOption {
  value: string
  label: string
}

interface SearchableSelectProps {
  value: string
  onChange: (value: string) => void
  options: SelectOption[]
  /** Shown when value is empty */
  placeholder?: string
  /** Show search box when option count exceeds this threshold (default 5) */
  searchThreshold?: number
  accentColor?: 'blue' | 'orange'
  /** 'sm' for compact table rows, 'md' (default) for normal form fields */
  size?: 'sm' | 'md'
  disabled?: boolean
  className?: string
}

export function SearchableSelect({
  value,
  onChange,
  options,
  placeholder = 'Select…',
  searchThreshold = 5,
  accentColor = 'blue',
  size = 'md',
  disabled = false,
  className = '',
}: SearchableSelectProps) {
  const [open, setOpen]       = useState(false)
  const [search, setSearch]   = useState('')
  const [dropStyle, setDropStyle] = useState<React.CSSProperties>({})

  const triggerRef  = useRef<HTMLButtonElement>(null)
  const dropRef     = useRef<HTMLDivElement>(null)
  const searchRef   = useRef<HTMLInputElement>(null)

  const selected     = options.find(o => o.value === value)
  const showSearch   = options.length > searchThreshold
  const filtered     = showSearch
    ? options.filter(o => o.label.toLowerCase().includes(search.toLowerCase()))
    : options

  const ringClass  = accentColor === 'orange' ? 'ring-orange-400 border-orange-400' : 'ring-blue-500 border-blue-500'
  const accentText = accentColor === 'orange' ? 'text-orange-600' : 'text-blue-600'
  const accentBg   = accentColor === 'orange' ? 'bg-orange-50' : 'bg-blue-50'
  const isCompact  = size === 'sm'

  // ── Position the dropdown using fixed coords so it escapes modal overflow ──
  function openDropdown() {
    if (!triggerRef.current) return
    const rect        = triggerRef.current.getBoundingClientRect()
    const dropMaxH    = 240
    const spaceBelow  = window.innerHeight - rect.bottom - 8
    const openUpward  = spaceBelow < dropMaxH && rect.top > dropMaxH

    setDropStyle(
      openUpward
        ? { position: 'fixed', bottom: window.innerHeight - rect.top + 4, left: rect.left, width: rect.width, zIndex: 9999 }
        : { position: 'fixed', top: rect.bottom + 4, left: rect.left, width: rect.width, zIndex: 9999 }
    )
    setOpen(true)
  }

  // Focus search on open
  useEffect(() => {
    if (open && showSearch) {
      requestAnimationFrame(() => searchRef.current?.focus())
    }
  }, [open, showSearch])

  // Close on outside click
  useEffect(() => {
    if (!open) return
    const handler = (e: MouseEvent) => {
      const t = e.target as Node
      if (!triggerRef.current?.contains(t) && !dropRef.current?.contains(t)) {
        setOpen(false)
        setSearch('')
      }
    }
    document.addEventListener('mousedown', handler)
    return () => document.removeEventListener('mousedown', handler)
  }, [open])

  // Close on Escape
  useEffect(() => {
    if (!open) return
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') { setOpen(false); setSearch('') }
    }
    document.addEventListener('keydown', handler)
    return () => document.removeEventListener('keydown', handler)
  }, [open])

  return (
    <div className={`relative ${className}`}>
      {/* ── Trigger button ── */}
      <button
        ref={triggerRef}
        type="button"
        disabled={disabled}
        onClick={() => open ? (setOpen(false), setSearch('')) : openDropdown()}
        className={[
          'w-full flex items-center justify-between gap-2 border rounded-lg text-left transition-all',
          isCompact ? 'px-2 py-1.5 text-xs rounded-md' : 'px-3 py-2 text-sm',
          open
            ? `ring-2 ${ringClass}`
            : 'border-slate-300 hover:border-slate-400',
          disabled ? 'bg-slate-50 text-slate-400 cursor-not-allowed' : 'bg-white',
        ].join(' ')}
      >
        <span className={selected ? 'text-slate-800 truncate' : 'text-slate-400 truncate'}>
          {selected ? selected.label : placeholder}
        </span>
        <svg
          className={`w-4 h-4 flex-shrink-0 text-slate-400 transition-transform duration-150 ${open ? 'rotate-180' : ''}`}
          fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}
        >
          <path strokeLinecap="round" strokeLinejoin="round" d="M19 9l-7 7-7-7" />
        </svg>
      </button>

      {/* ── Dropdown portal ── */}
      {open && createPortal(
        <div
          ref={dropRef}
          style={dropStyle}
          className="bg-white border border-slate-200 rounded-xl shadow-2xl overflow-hidden animate-in"
        >
          {/* Search box — only shown when options exceed threshold */}
          {showSearch && (
            <div className="p-2 border-b border-slate-100">
              <div className="relative">
                <svg
                  className="absolute left-2.5 top-1/2 -translate-y-1/2 w-3.5 h-3.5 text-slate-400 pointer-events-none"
                  fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}
                >
                  <path strokeLinecap="round" strokeLinejoin="round" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
                </svg>
                <input
                  ref={searchRef}
                  type="text"
                  value={search}
                  onChange={e => setSearch(e.target.value)}
                  placeholder="Type to search…"
                  className="w-full pl-7 pr-3 py-1.5 text-xs border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500 bg-slate-50"
                />
              </div>
            </div>
          )}

          {/* Options list */}
          <div className="max-h-56 overflow-y-auto">
            {filtered.length === 0 ? (
              <div className="px-4 py-5 text-xs text-slate-400 text-center">No results found</div>
            ) : (
              filtered.map(opt => {
                const isSelected = opt.value === value
                return (
                  <button
                    key={opt.value}
                    type="button"
                    onMouseDown={e => {
                      e.preventDefault()
                      onChange(opt.value)
                      setOpen(false)
                      setSearch('')
                    }}
                    className={[
                      'w-full flex items-center gap-2.5 px-3 py-2.5 text-sm text-left transition-colors',
                      isSelected
                        ? `${accentBg} ${accentText}`
                        : 'text-slate-700 hover:bg-slate-50',
                    ].join(' ')}
                  >
                    {/* Checkmark — occupies space even when not selected to keep alignment */}
                    <svg
                      className={`w-3.5 h-3.5 flex-shrink-0 ${isSelected ? accentText : 'invisible'}`}
                      fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2.5}
                    >
                      <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
                    </svg>
                    <span className={isSelected ? 'font-medium' : ''}>{opt.label}</span>
                  </button>
                )
              })
            )}
          </div>
        </div>,
        document.body
      )}
    </div>
  )
}
