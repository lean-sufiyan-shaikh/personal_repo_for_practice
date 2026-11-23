

export function ReviewRow({ label, value, highlight = false }: { label: string; value: string | null; highlight?: boolean }) {
  return (
    <div className="flex items-center justify-between px-4 py-2.5">
      <span className="text-ink-3 text-xs">{label}</span>
      <span className={`font-medium text-sm ${highlight ? 'text-primary' : 'text-ink'}`}>{value ?? '—'}</span>
    </div>
  )
}
