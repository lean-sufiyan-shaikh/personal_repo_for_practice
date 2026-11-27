import { useId, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { supplierApi } from '../../api/supplierApi'
import type { SaveSupplierRequest, Supplier } from '../../api/supplierApi'
import { FormField } from '../../components/FormField'
import { LoadingButton } from '../../components/LoadingButton'
import { Modal } from '../../components/Modal'
import { NumericInput } from '../../components/NumericInput'
import { StillNeeded } from '../../components/StillNeeded'
import { FormGrid, FormGridFullRow } from '../../components/layout/FormGrid'
import { useToastStore } from '../../store/toastStore'
import { parseApiError } from '../../utils/apiError'
import { localDateInputValue } from '../../utils/date'
import { roundToPaise } from '../../utils/money'

interface Props {
  pumpId: number
  /** Null to add a new supplier. */
  supplier: Supplier | null
  onClose: () => void
}

/** Adds or edits a supplier, with what the pump owed it (or had paid ahead) on the day this ledger starts. */
export function SupplierFormDialog({ pumpId, supplier, onClose }: Props) {
  const { t } = useTranslation(['suppliers', 'common'])
  const queryClient = useQueryClient()
  const { addToast } = useToastStore()
  const stillNeededId = useId()
  const [name, setName] = useState(supplier?.name ?? '')
  const [contactName, setContactName] = useState(supplier?.contactName ?? '')
  const [phone, setPhone] = useState(supplier?.phone ?? '')
  const [email, setEmail] = useState(supplier?.email ?? '')
  const [notes, setNotes] = useState(supplier?.notes ?? '')
  const [openingBalance, setOpeningBalance] = useState(supplier?.openingBalance ? String(supplier.openingBalance) : '')
  const [openingBalanceDate, setOpeningBalanceDate] = useState(supplier?.openingBalanceDate ?? '')
  const [error, setError] = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: (request: SaveSupplierRequest) => supplier
      ? supplierApi.update(pumpId, supplier.id, request)
      : supplierApi.create(pumpId, request),
    onSuccess: saved => {
      for (const key of ['suppliers', 'supplier-balances', 'supplier-ledger']) {
        queryClient.invalidateQueries({ queryKey: [key, pumpId] })
      }
      addToast(t('form.saved', { name: saved.name }), 'success')
      onClose()
    },
    onError: err => setError(parseApiError(err, t('form.failed'))),
  })

  const balanceValue = openingBalance.trim() === '' ? 0 : Number(openingBalance)
  const balanceValid = Number.isFinite(balanceValue)
  const stillNeeded = [
    name.trim() === '' && t('form.name'),
    !balanceValid && t('form.openingBalance'),
    balanceValid && balanceValue !== 0 && openingBalanceDate === '' && t('form.openingBalanceDate'),
  ].filter((item): item is string => typeof item === 'string')

  const submit = () => {
    if (stillNeeded.length > 0) return
    setError(null)
    mutation.mutate({
      name: name.trim(),
      contactName: contactName.trim() || undefined,
      phone: phone.trim() || undefined,
      email: email.trim() || undefined,
      notes: notes.trim() || undefined,
      openingBalance: roundToPaise(balanceValue),
      openingBalanceDate: openingBalanceDate || undefined,
    })
  }

  const optional = <span className="font-normal text-ink-3">{t('common:fields.optionalSuffix')}</span>

  return (
    <Modal
      title={supplier ? t('form.editTitle') : t('form.addTitle')}
      tone="info"
      size="md-wide"
      fullScreenOnPhone
      onClose={onClose}
      canClose={!mutation.isPending}
      bodyClassName="space-y-4"
      footer={<>
        <StillNeeded id={stillNeededId} items={stillNeeded} />
        <button type="button" onClick={onClose} disabled={mutation.isPending} className="ui-btn ui-btn-secondary min-h-11">
          {t('common:actions.cancel')}
        </button>
        <LoadingButton type="button" onClick={submit} disabled={stillNeeded.length > 0} isLoading={mutation.isPending}
          aria-describedby={stillNeeded.length > 0 ? stillNeededId : undefined}
          loadingLabel={t('form.saving')} className="ui-btn-primary min-h-11">
          {t('form.save')}
        </LoadingButton>
      </>}
    >
      <FormGrid>
        <FormGridFullRow>
          <FormField label={t('form.name')}>
            <input type="text" value={name} maxLength={150} onChange={e => setName(e.target.value)} data-autofocus />
          </FormField>
        </FormGridFullRow>
        <FormField label={<>{t('form.contactName')} {optional}</>}>
          <input type="text" value={contactName} maxLength={150} onChange={e => setContactName(e.target.value)} />
        </FormField>
        <FormField label={<>{t('form.phone')} {optional}</>}>
          <input type="tel" value={phone} maxLength={15} onChange={e => setPhone(e.target.value)} />
        </FormField>
        <FormField label={<>{t('form.email')} {optional}</>}>
          <input type="email" value={email} maxLength={255} onChange={e => setEmail(e.target.value)} />
        </FormField>
        <FormField label={<>{t('form.openingBalance')} {optional}</>} help={t('form.openingBalanceHelp')}>
          <NumericInput unit="₹" step="0.01" value={openingBalance} onChange={e => setOpeningBalance(e.target.value)} />
        </FormField>
        <FormField label={t('form.openingBalanceDate')}>
          <input type="date" value={openingBalanceDate} max={localDateInputValue()} onChange={e => setOpeningBalanceDate(e.target.value)} />
        </FormField>
        <FormGridFullRow>
          <FormField label={<>{t('common:fields.notes')} {optional}</>}>
            <textarea value={notes} onChange={e => setNotes(e.target.value)} rows={2} className="resize-none" />
          </FormField>
        </FormGridFullRow>
      </FormGrid>
      {error && <p role="alert" className="ui-alert ui-alert-danger">{error}</p>}
    </Modal>
  )
}
