import { useMutation, useQueryClient } from '@tanstack/react-query'
import { serverText } from '../../utils/serverText'
import { Trans, useTranslation } from 'react-i18next'
import { balanceSheetApi } from '../../api/balanceSheetApi'
import { parseApiError } from '../../utils/apiError'
import type { BalanceSheetSummary } from '../../api/balanceSheetApi'
import { Spinner } from '../../components/Spinner'
import { useToastStore } from '../../store/toastStore'
import { Modal } from '../../components/Modal'
import { fmtDate } from './bsFormat'

// ── Delete Confirm Modal ────────────────────────────────────────────────────────

interface DeleteConfirmProps {
  report: BalanceSheetSummary
  pumpId: number
  onClose: () => void
}

export function DeleteConfirmModal({ report, pumpId, onClose }: DeleteConfirmProps) {
  const { t } = useTranslation(['balancesheet', 'common'])
  const qc = useQueryClient()
  const { addToast } = useToastStore()

  const mutation = useMutation({
    mutationFn: () => balanceSheetApi.delete(pumpId, report.id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['balance-sheets', pumpId] })
      addToast(t('delete.deleted'), 'success')
      onClose()
    },
    onError: (err) => addToast(parseApiError(err, t('delete.failed')), 'error'),
  })

  return (
    <Modal
      title={t('delete.title')}
      subtitle={t('delete.subtitle')}
      tone="danger"
      size="sm"
      onClose={onClose}
      footer={<>
          <button
            onClick={onClose}
            className="ui-btn ui-btn-secondary"
          >
            {t('common:actions.cancel')}
          </button>
          <button
            onClick={() => mutation.mutate()}
            disabled={mutation.isPending}
            className="ui-btn ui-btn-danger"
          >
            {mutation.isPending
              ? <span className="flex items-center gap-1.5"><Spinner className="w-4 h-4" />{t('delete.deleting')}</span>
              : t('delete.confirm')}
          </button>
      </>}
    >
          <p className="text-sm text-ink-2">
            <Trans
              t={t}
              i18nKey="delete.body"
              values={{ period: serverText(report.periodLabelCode, report.periodLabelParams, report.periodLabel), date: fmtDate(report.reportDate) }}
              components={{ period: <span className="font-medium" />, date: <span className="font-medium" /> }}
            />
          </p>
    </Modal>
  )
}
