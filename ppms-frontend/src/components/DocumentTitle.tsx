import { useEffect } from 'react'
import { useLocation } from 'react-router-dom'
import { pageTitleFor } from '../routes/pageTitles'

/** Names the browser tab after the open page, so several PPMS tabs and screen-reader page changes can be told apart. */
export function DocumentTitle() {
  const { pathname } = useLocation()
  useEffect(() => {
    document.title = pageTitleFor(pathname)
  }, [pathname])
  return null
}
