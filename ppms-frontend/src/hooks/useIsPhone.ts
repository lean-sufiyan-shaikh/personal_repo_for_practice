import { useEffect, useState } from 'react'

const PHONE_QUERY = '(max-width: 768px)'

function matchesPhone(): boolean {
  return typeof window.matchMedia === 'function' && window.matchMedia(PHONE_QUERY).matches
}

/** True at the same width where the dashboard switches to its phone layout. */
export function useIsPhone(): boolean {
  const [isPhone, setIsPhone] = useState(matchesPhone)
  useEffect(() => {
    if (typeof window.matchMedia !== 'function') return
    const query = window.matchMedia(PHONE_QUERY)
    const onChange = () => setIsPhone(query.matches)
    query.addEventListener('change', onChange)
    return () => query.removeEventListener('change', onChange)
  }, [])
  return isPhone
}
