import type { ReactNode } from 'react'

/** A horizontally scrolling table wrapper that keyboard users can focus and scroll, named for screen readers. */
export function ScrollRegion({ label, className, children }: { label: string; className: string; children: ReactNode }) {
  return (
    <div className={className} role="region" aria-label={label} tabIndex={0}>
      {children}
    </div>
  )
}
