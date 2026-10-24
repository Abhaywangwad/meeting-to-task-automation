import { createContext, useCallback, useContext, useEffect, useState } from 'react'
import { getTrelloStatus } from './api/client'

interface TrelloState {
  /** true/false once known; null while loading or if the backend could not be asked. */
  configured: boolean | null
  /** Whether the In Progress / Completed lists are configured (needed to move cards); null while unknown. */
  workListsConfigured: boolean | null
  refresh: () => void
}

const TrelloContext = createContext<TrelloState>({ configured: null, workListsConfigured: null, refresh: () => {} })

export function TrelloProvider({ children }: { children: React.ReactNode }) {
  const [state, setState] = useState<{ configured: boolean | null; workListsConfigured: boolean | null }>({
    configured: null,
    workListsConfigured: null,
  })

  const refresh = useCallback(() => {
    getTrelloStatus()
      .then((s) => setState({ configured: s.configured, workListsConfigured: s.work_lists_configured }))
      .catch(() => setState({ configured: null, workListsConfigured: null }))
  }, [])

  useEffect(() => {
    refresh()
  }, [refresh])

  return <TrelloContext.Provider value={{ ...state, refresh }}>{children}</TrelloContext.Provider>
}

export const useTrello = () => useContext(TrelloContext)
