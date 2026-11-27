import { useEffect, useState } from 'react'

interface UpiQrCodeProps {
  /** A upi://pay link. */
  link: string
  label: string
  size?: number
}

/** The pay link as a QR code. The QR library is loaded only when a code is shown, to keep it out of the main bundle. */
export function UpiQrCode({ link, label, size = 160 }: UpiQrCodeProps) {
  const [drawn, setDrawn] = useState<{ link: string; src: string | null } | null>(null)

  useEffect(() => {
    let showing = true
    import('qrcode')
      .then(({ default: QRCode }) => QRCode.toString(link, { type: 'svg', margin: 1, errorCorrectionLevel: 'M' }))
      .then((markup) => {
        if (showing) setDrawn({ link, src: `data:image/svg+xml;charset=utf-8,${encodeURIComponent(markup)}` })
      })
      .catch((error: unknown) => {
        console.error('Could not draw the UPI QR code', error)
        if (showing) setDrawn({ link, src: null })
      })
    return () => { showing = false }
  }, [link])

  if (drawn?.link === link && drawn.src === null) return null
  if (drawn?.link !== link) {
    return <div role="presentation" style={{ width: size, height: size }} className="animate-pulse rounded bg-surface-sunken" />
  }
  return <img src={drawn.src ?? undefined} alt={label} width={size} height={size} className="rounded bg-surface" />
}
