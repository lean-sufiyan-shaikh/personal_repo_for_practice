import { describe, expect, it } from 'vitest'
import backendAuditActionSource from '../../../../ppms-backend/src/main/java/com/ppms/audit/AuditAction.java?raw'
import { AUDIT_ACTION_LABELS, AUDIT_ACTION_STYLES } from './auditActions'

function backendAuditActions(): string[] {
  const enumBody = backendAuditActionSource.slice(backendAuditActionSource.indexOf('{') + 1)
  return [...enumBody.matchAll(/^\s+([A-Z][A-Z_]*)\s*[,}]?\s*$/gm)].map((match) => match[1])
}

describe('audit actions', () => {
  it('reads the actions from the backend enum', () => {
    expect(backendAuditActions()).toEqual(expect.arrayContaining(['USER_CREATED', 'DELIVERY_DELETED', 'SETTLEMENT_CONFIG_UPDATED']))
  })

  it('has a readable label and a style for every backend action', () => {
    const labels: Record<string, string> = AUDIT_ACTION_LABELS
    const styles: Record<string, string> = AUDIT_ACTION_STYLES
    for (const action of backendAuditActions()) {
      expect(labels[action], `label for ${action}`).toMatch(/^[A-Z][a-z]/)
      expect(styles[action], `style for ${action}`).toBeTruthy()
    }
  })

  it('lists no action the backend does not have', () => {
    expect(Object.keys(AUDIT_ACTION_LABELS).sort()).toEqual([...backendAuditActions()].sort())
  })
})
