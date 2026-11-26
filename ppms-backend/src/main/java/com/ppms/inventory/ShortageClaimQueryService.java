package com.ppms.inventory;

import com.ppms.common.dto.PageRequests;
import com.ppms.common.dto.PagedResponse;
import com.ppms.common.exception.EntityType;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.pump.DispensaryUnit;
import com.ppms.pump.DispensaryUnitRepository;
import com.ppms.pump.Nozzle;
import com.ppms.pump.NozzleRepository;
import com.ppms.pump.PumpLocation;
import com.ppms.pump.PumpLocationRepository;
import com.ppms.pump.UndergroundTank;
import com.ppms.pump.UndergroundTankRepository;
import com.ppms.user.User;
import com.ppms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Lists and shows shortage claims with their delivery's figures, loading each kind of record once per page. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShortageClaimQueryService {

    public static final int OPEN_CLAIM_ATTENTION_DAYS = 15;

    private final ShortageClaimRepository claimRepository;
    private final TankerDeliveryRepository deliveryRepository;
    private final DeliveryNozzleReadingRepository readingRepository;
    private final UndergroundTankRepository tankRepository;
    private final NozzleRepository nozzleRepository;
    private final DispensaryUnitRepository duRepository;
    private final PumpLocationRepository pumpLocationRepository;
    private final UserRepository userRepository;

    /** Raised claims the supplier has not answered within {@value #OPEN_CLAIM_ATTENTION_DAYS} days. */
    public record OverdueClaims(long count, BigDecimal amount) {
    }

    /** A null status lists every claim. */
    public ShortageClaimListResponse listClaims(Long pumpId, ShortageClaimStatus status, int page, int size) {
        Page<ShortageClaim> claims = status == null
                ? claimRepository.findByPumpIdOrderByCreatedAtDescIdDesc(pumpId, PageRequests.of(page, size))
                : claimRepository.findByPumpIdAndStatusOrderByCreatedAtDescIdDesc(pumpId, status, PageRequests.of(page, size));

        Map<Long, TankerDelivery> deliveryById = deliveryRepository.findAllById(
                        claims.getContent().stream().map(ShortageClaim::getTankerDeliveryId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(TankerDelivery::getId, Function.identity()));
        Map<Long, UndergroundTank> tankById = tankRepository.findAllById(
                        deliveryById.values().stream().map(TankerDelivery::getTankId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(UndergroundTank::getId, Function.identity()));
        Map<Long, String> userNameById = userNamesOf(claims.getContent());

        PagedResponse<ShortageClaimResponse> pageOfClaims = PagedResponse.of(claims.map(claim -> {
            TankerDelivery delivery = deliveryById.get(claim.getTankerDeliveryId());
            return toResponse(claim, delivery, tankById.get(delivery.getTankId()), userNameById).build();
        }));
        return new ShortageClaimListResponse(pageOfClaims, totalsOf(pumpId));
    }

    public ShortageClaimResponse getClaim(Long pumpId, Long claimId) {
        ShortageClaim claim = claimRepository.findByIdAndPumpId(claimId, pumpId)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.SHORTAGE_CLAIM, "Shortage claim not found"));
        TankerDelivery delivery = deliveryRepository.findById(claim.getTankerDeliveryId())
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.DELIVERY, "Delivery not found"));
        UndergroundTank tank = tankRepository.findById(delivery.getTankId()).orElse(null);
        PumpLocation pump = pumpLocationRepository.findById(pumpId)
                .orElseThrow(() -> new ResourceNotFoundException(EntityType.PUMP, "Pump not found"));
        return toResponse(claim, delivery, tank, userNamesOf(List.of(claim)))
                .nozzleReadings(nozzleReadingsOf(delivery))
                .pump(new ShortageClaimResponse.PumpDetails(pump.getName(), pump.getAddress(), pump.getGstin(),
                        pump.getLegalName()))
                .build();
    }

    public OverdueClaims overdueClaims(Long pumpId, OffsetDateTime now) {
        List<ShortageClaim> overdue = claimRepository.findByPumpIdAndStatusAndCreatedAtBefore(
                pumpId, ShortageClaimStatus.RAISED, now.minusDays(OPEN_CLAIM_ATTENTION_DAYS));
        BigDecimal amount = overdue.stream().map(ShortageClaim::getClaimAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new OverdueClaims(overdue.size(), amount);
    }

    private ShortageClaimListResponse.Totals totalsOf(Long pumpId) {
        Map<ShortageClaimStatus, ShortageClaimRepository.StatusTotals> byStatus = claimRepository.totalsByStatus(pumpId)
                .stream().collect(Collectors.toMap(ShortageClaimRepository.StatusTotals::getStatus, Function.identity()));
        ShortageClaimRepository.StatusTotals open = byStatus.get(ShortageClaimStatus.RAISED);
        ShortageClaimRepository.StatusTotals settled = byStatus.get(ShortageClaimStatus.SETTLED);
        ShortageClaimRepository.StatusTotals rejected = byStatus.get(ShortageClaimStatus.REJECTED);
        return new ShortageClaimListResponse.Totals(
                open != null ? open.getClaimCount() : 0,
                open != null ? open.getClaimAmount() : BigDecimal.ZERO,
                settled != null ? settled.getClaimCount() : 0,
                settled != null ? settled.getCreditNoteAmount() : BigDecimal.ZERO,
                rejected != null ? rejected.getClaimCount() : 0);
    }

    private List<ShortageClaimResponse.NozzleReadingLine> nozzleReadingsOf(TankerDelivery delivery) {
        List<DeliveryNozzleReading> readings = readingRepository.findByTankerDeliveryIdOrderByNozzleIdAsc(delivery.getId());
        Map<Long, Nozzle> nozzleById = nozzleRepository.findAllById(
                        readings.stream().map(DeliveryNozzleReading::getNozzleId).toList())
                .stream().collect(Collectors.toMap(Nozzle::getId, Function.identity()));
        Map<Long, DispensaryUnit> duById = duRepository.findAllById(
                        nozzleById.values().stream().map(Nozzle::getDuId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(DispensaryUnit::getId, Function.identity()));
        return readings.stream()
                .map(reading -> {
                    Nozzle nozzle = nozzleById.get(reading.getNozzleId());
                    return new ShortageClaimResponse.NozzleReadingLine(reading.getNozzleId(), nozzle.getNozzleNumber(),
                            DeliveryReceiptService.duNameOf(nozzle, duById), reading.getReadingBefore(),
                            reading.getReadingAfter(), reading.getLitresSold());
                })
                .toList();
    }

    private Map<Long, String> userNamesOf(List<ShortageClaim> claims) {
        Set<Long> userIds = new HashSet<>();
        claims.forEach(claim -> {
            userIds.add(claim.getRaisedByUserId());
            userIds.add(claim.getResolvedByUserId());
        });
        userIds.remove(null);
        return userRepository.findAllById(userIds).stream().collect(Collectors.toMap(User::getId, User::getFullName));
    }

    private static ShortageClaimResponse.ShortageClaimResponseBuilder toResponse(ShortageClaim claim, TankerDelivery delivery,
                                                                                UndergroundTank tank,
                                                                                Map<Long, String> userNameById) {
        return ShortageClaimResponse.builder()
                .id(claim.getId())
                .deliveryId(delivery.getId())
                .tankId(delivery.getTankId())
                .tankIdentifier(tank != null ? tank.getTankIdentifier() : null)
                .fuelType(delivery.getFuelType().name())
                .invoiceReference(delivery.getInvoiceReference())
                .deliveryDate(delivery.getDeliveryDate())
                .tankerName(delivery.getTankerName())
                .invoiceLitres(delivery.getQuantityDelivered())
                .receivedLitres(delivery.getReceivedLitres())
                .shortLitres(claim.getLitresShort())
                .shortPercent(ReceiptCheckCalculator.percentOfInvoice(claim.getLitresShort(), delivery.getQuantityDelivered()))
                .tolerancePercent(delivery.getReceiptTolerancePercent())
                .pricePerLitre(claim.getPricePerLitre())
                .claimAmount(claim.getClaimAmount())
                .dipBeforeLitres(delivery.getDipBeforeLitres())
                .dipAfterLitres(delivery.getDipAfterLitres())
                .soldBetweenLitres(delivery.getSoldBetweenLitres())
                .observedDensity(delivery.getObservedDensity())
                .invoiceDensity(delivery.getInvoiceDensity())
                .densityDifference(ReceiptCheckCalculator.densityDifference(delivery.getObservedDensity(), delivery.getInvoiceDensity()))
                .densityTolerance(delivery.getDensityTolerance())
                .densityFlagged(delivery.isDensityFlagged())
                .status(claim.getStatus().name())
                .raisedAt(claim.getCreatedAt())
                .raisedByUserName(userNameById.getOrDefault(claim.getRaisedByUserId(), "Unknown"))
                .creditNoteAmount(claim.getCreditNoteAmount())
                .creditNoteReference(claim.getCreditNoteReference())
                .creditNoteDate(claim.getCreditNoteDate())
                .rejectionReason(claim.getRejectionReason())
                .resolvedAt(claim.getResolvedAt())
                .resolvedByUserName(claim.getResolvedByUserId() != null
                        ? userNameById.get(claim.getResolvedByUserId()) : null);
    }

    /** Claims of the given deliveries, keyed by delivery id. */
    public Map<Long, ShortageClaim> claimsByDeliveryId(Set<Long> deliveryIds) {
        if (deliveryIds.isEmpty()) {
            return Map.of();
        }
        return claimRepository.findByTankerDeliveryIdIn(deliveryIds).stream()
                .collect(Collectors.toMap(ShortageClaim::getTankerDeliveryId, Function.identity()));
    }
}
