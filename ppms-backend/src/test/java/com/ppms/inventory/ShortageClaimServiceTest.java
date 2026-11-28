package com.ppms.inventory;

import com.ppms.audit.AuditService;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.time.BusinessClock;
import com.ppms.fuel.FuelType;
import com.ppms.notification.NotificationDedupService;
import com.ppms.notification.NotificationType;
import com.ppms.pump.UndergroundTank;
import com.ppms.user.User;
import com.ppms.user.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ShortageClaimServiceTest {

    private static final Long PUMP_ID = 1L;
    private static final Long DELIVERY_ID = 20L;
    private static final Long CLAIM_ID = 30L;

    @Mock private ShortageClaimRepository claimRepository;
    @Mock private NotificationDedupService dedupService;
    @Mock private AuditService auditService;
    @Mock private BusinessClock businessClock;
    @InjectMocks private ShortageClaimService claimService;

    private final User owner = User.builder().id(10L).role(UserRole.OWNER).build();
    private final UndergroundTank tank = UndergroundTank.builder().id(3L).tankIdentifier("T-1").build();

    @Test
    void deliveryThatIsNotShortRaisesNoClaim() {
        when(claimRepository.findByTankerDeliveryId(DELIVERY_ID)).thenReturn(Optional.empty());

        ShortageClaim claim = claimService.syncWithDelivery(delivery(DeliveryCheckStatus.OVER, "100"), tank, owner);

        assertThat(claim).isNull();
        verify(claimRepository, never()).save(any());
    }

    @Test
    void shortDeliveryRaisesAClaimForTheLitresShortAtTheInvoiceCostAndNotifiesOnce() {
        when(claimRepository.findByTankerDeliveryId(DELIVERY_ID)).thenReturn(Optional.empty());
        when(claimRepository.save(any())).thenAnswer(invocation -> {
            ShortageClaim saved = invocation.getArgument(0);
            saved.setId(CLAIM_ID);
            return saved;
        });

        ShortageClaim claim = claimService.syncWithDelivery(delivery(DeliveryCheckStatus.SHORT, "-124"), tank, owner);

        assertThat(claim.getLitresShort()).isEqualByComparingTo("124");
        assertThat(claim.getClaimAmount()).isEqualByComparingTo("11160.00");
        verify(dedupService).maybeInsert(eq(PUMP_ID), eq(NotificationType.DELIVERY_SHORTAGE),
                eq("DELIVERY_SHORTAGE:delivery:" + DELIVERY_ID), anyString(), anyString(), any());
    }

    @Test
    void correctionThatLeavesTheFiguresUnchangedKeepsTheOpenClaim() {
        ShortageClaim open = claim(ShortageClaimStatus.RAISED, "124");
        when(claimRepository.findByTankerDeliveryId(DELIVERY_ID)).thenReturn(Optional.of(open));

        ShortageClaim claim = claimService.syncWithDelivery(delivery(DeliveryCheckStatus.SHORT, "-124"), tank, owner);

        assertThat(claim).isSameAs(open);
        verify(claimRepository, never()).save(any());
        verify(dedupService, never()).maybeInsert(any(), any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void correctionThatLeavesTheDeliveryNoLongerShortWithdrawsTheOpenClaim() {
        ShortageClaim open = claim(ShortageClaimStatus.RAISED, "124");
        when(claimRepository.findByTankerDeliveryId(DELIVERY_ID)).thenReturn(Optional.of(open));

        ShortageClaim claim = claimService.syncWithDelivery(delivery(DeliveryCheckStatus.OK, "-14"), tank, owner);

        assertThat(claim).isNull();
        verify(claimRepository).delete(open);
    }

    @Test
    void settledClaimCannotBeRejected() {
        when(claimRepository.findByIdAndPumpId(CLAIM_ID, PUMP_ID))
                .thenReturn(Optional.of(claim(ShortageClaimStatus.SETTLED, "124")));

        assertThatThrownBy(() -> claimService.reject(PUMP_ID, CLAIM_ID, new RejectShortageClaimRequest("Too late"), owner))
                .isInstanceOfSatisfying(BusinessException.class, refusal -> {
                    assertThat(refusal.getCode()).isEqualTo("SHORTAGE_CLAIM_NOT_RAISED");
                    assertThat(refusal.getParams()).containsEntry("statusCode", "SETTLED");
                });
    }

    @Test
    void deliveryWithASettledClaimCanNoLongerChange() {
        when(claimRepository.findByTankerDeliveryId(DELIVERY_ID))
                .thenReturn(Optional.of(claim(ShortageClaimStatus.SETTLED, "124")));

        assertThatThrownBy(() -> claimService.assertDeliveryChangeable(delivery(DeliveryCheckStatus.SHORT, "-124")))
                .isInstanceOfSatisfying(BusinessException.class,
                        refusal -> assertThat(refusal.getCode()).isEqualTo("SHORTAGE_CLAIM_CLOSED_FOR_DELIVERY"));
    }

    private static TankerDelivery delivery(DeliveryCheckStatus status, String varianceLitres) {
        return TankerDelivery.builder()
                .id(DELIVERY_ID)
                .pumpId(PUMP_ID)
                .fuelType(FuelType.PETROL)
                .invoiceReference("INV-1")
                .quantityDelivered(new BigDecimal("12000"))
                .receivedLitres(new BigDecimal("12000").add(new BigDecimal(varianceLitres)))
                .varianceLitres(new BigDecimal(varianceLitres))
                .costPricePerUnit(new BigDecimal("90"))
                .checkStatus(status)
                .build();
    }

    private static ShortageClaim claim(ShortageClaimStatus status, String litresShort) {
        return ShortageClaim.builder()
                .id(CLAIM_ID)
                .pumpId(PUMP_ID)
                .tankerDeliveryId(DELIVERY_ID)
                .status(status)
                .litresShort(new BigDecimal(litresShort))
                .pricePerLitre(new BigDecimal("90"))
                .claimAmount(new BigDecimal(litresShort).multiply(new BigDecimal("90")))
                .build();
    }
}
