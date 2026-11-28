package com.ppms.invoice;

import com.ppms.ancillary.AncillaryProduct;
import com.ppms.ancillary.AncillaryProductRepository;
import com.ppms.ancillary.AncillaryProductStatus;
import com.ppms.ancillary.AncillarySale;
import com.ppms.ancillary.UnitOfMeasure;
import com.ppms.audit.AuditService;
import com.ppms.common.exception.BusinessException;
import com.ppms.credit.CreditClient;
import com.ppms.credit.CreditClientAccessService;
import com.ppms.credit.CreditClientRepository;
import com.ppms.pump.PumpLocation;
import com.ppms.pump.PumpLocationRepository;
import com.ppms.shift.ShiftCreditEntry;
import com.ppms.support.FixedBusinessClock;
import com.ppms.transaction.TransactionPaymentMode;
import com.ppms.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CreditInvoiceServiceTest {

    private static final long PUMP_ID = 7L;
    private static final long PRODUCT_ID = 5L;
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 28);
    private static final String PUMP_GSTIN = "27AAAPL1234C1ZV";

    private final CreditInvoiceRepository invoiceRepository = mock(CreditInvoiceRepository.class);
    private final CreditInvoiceLineRepository lineRepository = mock(CreditInvoiceLineRepository.class);
    private final InvoiceSequenceRepository sequenceRepository = mock(InvoiceSequenceRepository.class);
    private final CreditClientAccessService clientAccessService = mock(CreditClientAccessService.class);
    private final PumpLocationRepository pumpLocationRepository = mock(PumpLocationRepository.class);
    private final AncillaryProductRepository productRepository = mock(AncillaryProductRepository.class);
    private final CreditClientRepository clientRepository = mock(CreditClientRepository.class);
    private final GstCreditNoteRepository creditNoteRepository = mock(GstCreditNoteRepository.class);

    private final CreditInvoiceService service = new CreditInvoiceService(invoiceRepository, lineRepository,
            sequenceRepository, clientAccessService, pumpLocationRepository, productRepository, mock(AuditService.class),
            clientRepository, creditNoteRepository,
            new FixedBusinessClock(OffsetDateTime.parse("2026-11-28T10:00:00+05:30")));

    private final PumpLocation pump = PumpLocation.builder().id(PUMP_ID).name("Lakshmi Fuels").gstin(PUMP_GSTIN)
            .legalName("Lakshmi Fuels Pvt Ltd").invoicePrefix("PMP3").build();

    @BeforeEach
    void givenPumpWithGstDetailsAndInvoiceSequence() {
        when(pumpLocationRepository.findById(PUMP_ID)).thenReturn(Optional.of(pump));
        when(sequenceRepository.findByIdForUpdate(any()))
                .thenReturn(Optional.of(new InvoiceSequence(new InvoiceSequence.Key(PUMP_ID, "2026-27"), 1)));
        when(invoiceRepository.save(any())).thenAnswer(call -> {
            CreditInvoice invoice = call.getArgument(0);
            invoice.setId(100L);
            return invoice;
        });
        when(productRepository.findAllById(anyList())).thenReturn(List.of(engineOil()));
    }

    @Test
    void sameStateInvoiceKeepsFuelOutsideGstAndSplitsProductGstIntoCgstAndSgst() {
        CreditClient client = givenClient(1L, null, "27BBBCS5678D1Z2");
        givenFuel(1L, "1000.00");
        givenProductSale(1L);

        CreditInvoice invoice = service.issue(PUMP_ID, 1L, TODAY, TODAY, false, new User());

        assertThat(invoice.getInvoiceNumber()).isEqualTo("PMP3/26-27/0001");
        assertThat(invoice.getFuelTotal()).isEqualByComparingTo("1000.00");
        assertThat(invoice.getTaxableTotal()).isEqualByComparingTo("200.00");
        assertThat(invoice.getCgstTotal()).isEqualByComparingTo("18.00");
        assertThat(invoice.getSgstTotal()).isEqualByComparingTo("18.00");
        assertThat(invoice.getIgstTotal()).isEqualByComparingTo("0.00");
        assertThat(invoice.getGrandTotal()).isEqualByComparingTo("1236.00");
        assertThat(invoice.getPlaceOfSupplyStateCode()).isEqualTo("27");
        assertThat(invoice.getBuyerName()).isEqualTo(client.getName());
    }

    @Test
    void clientInAnotherStateIsChargedIgstWithItsStateAsThePlaceOfSupply() {
        givenClient(1L, null, "29CCCDE9012F1Z8");
        givenProductSale(1L);

        CreditInvoice invoice = service.issue(PUMP_ID, 1L, TODAY, TODAY, false, new User());

        assertThat(invoice.getIgstTotal()).isEqualByComparingTo("36.00");
        assertThat(invoice.getCgstTotal()).isEqualByComparingTo("0.00");
        assertThat(invoice.getPlaceOfSupplyStateCode()).isEqualTo("29");
    }

    @Test
    void unregisteredClientIsBilledWithinThePumpsState() {
        givenClient(1L, null, null);
        givenProductSale(1L);

        CreditInvoice invoice = service.issue(PUMP_ID, 1L, TODAY, TODAY, false, new User());

        assertThat(invoice.getCgstTotal()).isEqualByComparingTo("18.00");
        assertThat(invoice.getSgstTotal()).isEqualByComparingTo("18.00");
        assertThat(invoice.getPlaceOfSupplyStateCode()).isEqualTo("27");
        assertThat(invoice.getBuyerGstin()).isNull();
    }

    @Test
    void pumpWithoutGstDetailsCannotIssueInvoices() {
        givenClient(1L, null, null);
        pump.setGstin(null);

        assertThatThrownBy(() -> service.issue(PUMP_ID, 1L, TODAY, TODAY, false, new User()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Add GST details in pump setup first");
        verify(invoiceRepository, never()).save(any());
    }

    @Test
    void clientWithNothingUninvoicedInThePeriodIsRefused() {
        givenClient(1L, null, null);

        assertThatThrownBy(() -> service.issue(PUMP_ID, 1L, TODAY, TODAY, false, new User()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Nothing to invoice")
                .extracting("code").isEqualTo("INVOICE_NOTHING_TO_BILL");
    }

    @Test
    void pumpDefaultMakesAParentsInvoiceCoverItsSubAccountsNamingEachAccount() {
        pump.setGroupInvoiceByDefault(true);
        givenClient(1L, null, null);
        CreditClient subAccount = CreditClient.builder().id(2L).pumpId(PUMP_ID).name("Driver").parentClientId(1L).build();
        when(clientRepository.findByParentClientId(1L)).thenReturn(List.of(subAccount));
        givenFuel(1L, "500.00");
        givenFuel(2L, "300.00");

        CreditInvoice invoice = service.issue(PUMP_ID, 1L, TODAY, TODAY, null, new User());

        assertThat(invoice.isCoversSubAccounts()).isTrue();
        assertThat(invoice.getGrandTotal()).isEqualByComparingTo("800.00");
        assertThat(savedLines()).extracting(CreditInvoiceLine::getAccountName).containsExactly("Client 1", "Driver");
    }

    @Test
    void choosingNotToCoverSubAccountsWinsOverThePumpDefault() {
        pump.setGroupInvoiceByDefault(true);
        givenClient(1L, null, null);
        givenFuel(1L, "500.00");

        CreditInvoice invoice = service.issue(PUMP_ID, 1L, TODAY, TODAY, false, new User());

        assertThat(invoice.isCoversSubAccounts()).isFalse();
        assertThat(invoice.getGrandTotal()).isEqualByComparingTo("500.00");
        verify(clientRepository, never()).findByParentClientId(anyLong());
    }

    @Test
    void cancelledInvoiceCannotBeCancelledAgain() {
        CreditInvoice cancelled = CreditInvoice.builder().id(100L).invoiceNumber("PMP3/26-27/0001")
                .status(CreditInvoiceStatus.CANCELLED).build();
        when(invoiceRepository.findByIdAndPumpIdForUpdate(100L, PUMP_ID)).thenReturn(Optional.of(cancelled));

        assertThatThrownBy(() -> service.cancel(PUMP_ID, 100L, "Again", new User()))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("INVOICE_ALREADY_CANCELLED");
    }

    @Test
    void cancellingFreesTheInvoicedSalesAndKeepsTheReason() {
        CreditInvoice issued = CreditInvoice.builder().id(100L).invoiceNumber("PMP3/26-27/0001").build();
        when(invoiceRepository.findByIdAndPumpIdForUpdate(100L, PUMP_ID)).thenReturn(Optional.of(issued));
        User owner = new User();
        owner.setId(3L);

        CreditInvoice cancelled = service.cancel(PUMP_ID, 100L, " Wrong period ", owner);

        assertThat(cancelled.getStatus()).isEqualTo(CreditInvoiceStatus.CANCELLED);
        assertThat(cancelled.getCancelReason()).isEqualTo("Wrong period");
        verify(lineRepository).releaseLinesOfInvoice(100L);
    }

    private CreditClient givenClient(long id, Long parentId, String gstin) {
        CreditClient client = CreditClient.builder().id(id).pumpId(PUMP_ID).name("Client " + id)
                .parentClientId(parentId).gstin(gstin).build();
        when(clientAccessService.requireClientForPump(PUMP_ID, id)).thenReturn(client);
        return client;
    }

    private void givenFuel(long clientId, String amount) {
        when(lineRepository.findUninvoicedFuelEntries(clientId, TODAY, TODAY)).thenReturn(List.of(
                ShiftCreditEntry.builder().id(clientId * 10).clientId(clientId).amount(new BigDecimal(amount))
                        .fuelType("PETROL").saleDate(TODAY).build()));
    }

    private void givenProductSale(long clientId) {
        when(lineRepository.findUninvoicedAncillarySales(PUMP_ID, clientId, TransactionPaymentMode.CREDIT, TODAY, TODAY))
                .thenReturn(List.of(AncillarySale.builder()
                        .id(21L)
                        .productId(PRODUCT_ID)
                        .quantityUnits(2)
                        .sellingPricePerUnit(new BigDecimal("118.00"))
                        .totalAmount(new BigDecimal("200.00"))
                        .gstAmount(new BigDecimal("36.00"))
                        .totalWithGst(new BigDecimal("236.00"))
                        .gstRatePercent(new BigDecimal("18.00"))
                        .paymentMode(TransactionPaymentMode.CREDIT)
                        .clientId(clientId)
                        .saleDate(TODAY)
                        .build()));
    }

    @SuppressWarnings("unchecked")
    private List<CreditInvoiceLine> savedLines() {
        ArgumentCaptor<List<CreditInvoiceLine>> lines = ArgumentCaptor.forClass(List.class);
        verify(lineRepository).saveAll(lines.capture());
        return lines.getValue();
    }

    private static AncillaryProduct engineOil() {
        return AncillaryProduct.builder()
                .id(PRODUCT_ID)
                .name("Engine Oil")
                .packageSize(new BigDecimal("1.000"))
                .unitOfMeasure(UnitOfMeasure.LITRE)
                .gstRatePercent(new BigDecimal("18.00"))
                .hsnCode("27101980")
                .status(AncillaryProductStatus.ACTIVE)
                .build();
    }
}
