import type { Insight } from '../api/types'
import { EvidenceBadge } from './Badges'
import { EvidenceQuote } from './EvidenceQuote'

interface GroupProps {
  title: string
  empty: string
  items: Insight[]
  transcript: string
}

function Group({ title, empty, items, transcript }: GroupProps) {
  return (
    <section className="card insight-group" aria-label={title}>
      <h3>
        {title} <span className="count">{items.length}</span>
      </h3>
      {items.length === 0 ? (
        <p className="muted">{empty}</p>
      ) : (
        <ul className="insights">
          {items.map((i) => (
            <li key={i.id}>
              <p>{i.text}</p>
              <div className="badges">
                <EvidenceBadge status={i.evidence_status} />
                {i.owner && <span className="small">Owner: {i.owner}</span>}
              </div>
              <EvidenceQuote
                quote={i.evidence_quote}
                start={i.evidence_start}
                end={i.evidence_end}
                match={i.evidence_match}
                transcript={transcript}
              />
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

interface Props {
  decisions: Insight[]
  questions: Insight[]
  updates: Insight[]
  transcript: string
}

export function InsightsPanel({ decisions, questions, updates, transcript }: Props) {
  return (
    <div className="insight-grid">
      <Group title="Decisions" empty="No decisions were found." items={decisions} transcript={transcript} />
      <Group title="Unresolved questions" empty="No unresolved questions were found." items={questions} transcript={transcript} />
      <Group title="Completed updates" empty="No completed work was reported." items={updates} transcript={transcript} />
    </div>
  )
}
