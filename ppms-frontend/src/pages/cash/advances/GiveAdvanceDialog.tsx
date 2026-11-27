import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { staffAdvanceApi } from '../../../api/staffAdvanceApi'
import { userApi } from '../../../api/userApi'
import { FormField } from '../../../components/FormField'
import { LoadingButton } from '../../../components/LoadingButton'
import { Modal } from '../../../components/Modal'
import { NumericInput } from '../../../components/NumericInput'
import { useToastStore } from '../../../store/toastStore'
import { parseApiError } from '../../../utils/apiError'
import { localDateInputValue } from '../../../utils/date'
import { formatCurrency } from '../../../utils/format'

/** Gives a staff member a cash advance; the cash leaves the safe on the advance date. */
export function GiveAdvanceDialog({ pumpId, onClose }: { pumpId: number; onClose: () => void }) {
  const { t } = useTranslation(['cash', 'common'])
  const queryClient = useQueryClient()
  const { addToast } = useToastStore()
  const [staffUserId, setStaffUserId] = useState('')
  const [amount, setAmount] = useState('')
  const [advanceDate, setAdvanceDate] = useState(localDateInputValue())
  const [note, setNote] = useState('')
  const [error, setError] = useState<string | null>(null)

  const { data: staff = [] } = useQuery({
    queryKey: ['staff', pumpId],
    queryFn: () => userApi.getStaff(pumpId),
  })
  const mutation = useMutation({
    mutationFn: () => staffAdvanceApi.give(pumpId, {
      staffUserId: Number(staffUserId), amount: Number(amount), advanceDate, note: note.trim() || undefined,
    }),
    onSuccess: (advance) => {
      queryClient.invalidateQueries({ queryKey: ['staffAdvances', pumpId] })
      queryClient.invalidateQueries({ queryKey: ['cashEvents', pumpId] })
      addToast(t('advances.given', { amount: formatCurrency(advance.amount), name: advance.staffName }), 'success')
      onClose()
    },
    onError: (err: unknown) => setError(parseApiError(err, t('advances.giveFailed'))),
  })
  const canSave = staffUserId !== '' && Number(amount) > 0 && advanceDate !== ''

  return (
    <Modal
      title={t('advances.giveTitle')}
      subtitle={t('advances.giveSubtitle')}
      tone="info"
      size="md"
      fullScreenOnPhone
      onClose={onClose}
      canClose={!mutation.isPending}
      bodyClassName="space-y-4"
      footer={
        <>
          <button type="button" onClick={onClose} disabled={mutation.isPending} className="ui-btn ui-btn-secondary min-h-11">
            {t('common:actions.cancel')}
          </button>
          <LoadingButton type="button" disabled={!canSave} isLoading={mutation.isPending} loadingLabel={t('advances.saving')}
            className="ui-btn-primary min-h-11" onClick={() => { setError(null); mutation.mutate() }}>
            {t('advances.give')}
          </LoadingButton>
        </>
      }
    >
      <FormField label={t('advances.staff')}>
        <select className="ui-input" value={staffUserId} onChange={(e) => setStaffUserId(e.target.value)}>
          <option value="">{t('advances.chooseStaff')}</option>
          {staff.filter((member) => member.role !== 'OWNER')
            .map((member) => <option key={member.id} value={member.id}>{member.fullName}</option>)}
        </select>
      </FormField>
      <FormField label={t('advances.amount')}>
        <NumericInput step="0.01" min="0" value={amount} onChange={(e) => setAmount(e.target.value)} />
      </FormField>
      <FormField label={t('advances.date')}>
        <input type="date" className="ui-input" value={advanceDate} max={localDateInputValue()}
          onChange={(e) => setAdvanceDate(e.target.value)} />
      </FormField>
      <FormField label={t('advances.note')}>
        <input className="ui-input" maxLength={500} value={note} onChange={(e) => setNote(e.target.value)} />
      </FormField>
      {error && <p role="alert" className="ui-alert ui-alert-danger">{error}</p>}
    </Modal>
  )
}
