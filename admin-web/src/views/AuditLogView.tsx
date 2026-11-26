import React, { useMemo, useState } from "react";
import type { AuditLogRecord } from "../types/controlCenter";

interface AuditLogViewProps {
  logs: AuditLogRecord[];
}

export const AuditLogView: React.FC<AuditLogViewProps> = ({ logs }) => {
  const [entityFilter, setEntityFilter] = useState<string>("all");

  const filtered = useMemo(() => {
    return entityFilter === "all" ? logs : logs.filter((l) => l.entity === entityFilter);
  }, [logs, entityFilter]);

  return (
    <div className="panel">
      <div className="panel-header">
        <div>
          <h2>Protected Audit Log (Read-Only)</h2>
          <p className="muted" style={{ margin: "4px 0 0" }}>
            Append-only database triggers record safe structural state transitions without storing feedback message or contact PII.
          </p>
        </div>
        <div className="field" style={{ minWidth: 180 }}>
          <label htmlFor="audit-entity-filter">Filter Entity</label>
          <select
            id="audit-entity-filter"
            value={entityFilter}
            onChange={(e) => setEntityFilter(e.target.value)}
          >
            <option value="all">All entities</option>
            <option value="releases">releases</option>
            <option value="announcements">announcements</option>
            <option value="feature_flags">feature_flags</option>
            <option value="feedback">feedback</option>
            <option value="admin_roles">admin_roles</option>
          </select>
        </div>
      </div>

      {filtered.length === 0 ? (
        <p className="muted" style={{ marginBottom: 0 }}>
          No audit entries found for this filter.
        </p>
      ) : (
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Timestamp</th>
                <th>Actor</th>
                <th>Event</th>
                <th>Entity / Key</th>
                <th>Before</th>
                <th>After</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((entry) => (
                <tr key={entry.id}>
                  <td>{new Date(entry.occurred_at).toLocaleString()}</td>
                  <td>{entry.actor_user_id ? `${entry.actor_user_id.slice(0, 8)}...` : "system"}</td>
                  <td>
                    <span className="badge">{entry.event}</span>
                  </td>
                  <td>
                    <div>{entry.entity}</div>
                    <div className="muted">{entry.entity_key}</div>
                  </td>
                  <td>
                    <code>{entry.before_state ? JSON.stringify(entry.before_state) : "—"}</code>
                  </td>
                  <td>
                    <code>{entry.after_state ? JSON.stringify(entry.after_state) : "—"}</code>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
};
