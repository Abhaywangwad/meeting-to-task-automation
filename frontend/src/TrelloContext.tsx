import { createContext, useCallback, useContext, useEffect, useState } from 'react'
import { getTrelloStatus } from './api/client'

interface TrelloState {
  /** true/false once known; null while loading or if the backend could not be asked. */
  configured: boolean | null
  refresh: () => void
}

const TrelloContext = createContext<TrelloState>({ configured: null, refresh: () => {} })

export function TrelloProvider({ children }: { children: React.ReactNode }) {
  const [configured, setConfigured] = useState<boolean | null>(null)

  const refresh = useCallback(() => {
    getTrelloStatus()
      .then((s) => setConfigured(s.configured))
      .catch(() => setConfigured(null))
  }, [])

  useEffect(() => {
    refresh()
  }, [refresh])

  return <TrelloContext.Provider value={{ configured, refresh }}>{children}</TrelloContext.Provider>
}

export const useTrello = () => useContext(TrelloContext)
