

// ── Section header ────────────────────────────────────────────────────────────

export function SectionLabel({ children }: { children: React.ReactNode }) {
  return (
    <p className="text-xs font-bold uppercase tracking-widest text-ink-3 mb-3">
      {children}
    </p>
  )
}
