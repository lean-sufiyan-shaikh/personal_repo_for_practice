package com.ppms.inventory;

import com.ppms.pump.UndergroundTank;
import com.ppms.pump.UndergroundTankRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;

/**
 * The fuel already in a tank when the pump starts using PPMS. It becomes the tank's first FIFO lot at the given cost,
 * with no tanker delivery behind it, so shifts draw from it and profit uses its cost like any delivered fuel.
 */
@Service
@RequiredArgsConstructor
public class TankOpeningStockService {

    private final UndergroundTankRepository tankRepository;
    private final InventoryLotRepository inventoryLotRepository;

    /** A tank that holds stock or has ever had a lot already has its opening stock. */
    @Transactional(readOnly = true)
    public boolean hasStock(UndergroundTank tank) {
        return (tank.getCurrentStock() != null && tank.getCurrentStock().signum() > 0)
                || inventoryLotRepository.existsByTankId(tank.getId());
    }

    @Transactional
    public InventoryLot addOpeningStock(UndergroundTank tank, BigDecimal litres, BigDecimal costPerLitre,
                                        OffsetDateTime asOf) {
        BigDecimal quantity = litres.setScale(3, RoundingMode.HALF_UP);
        InventoryLot lot = inventoryLotRepository.save(InventoryLot.builder()
                .tankId(tank.getId())
                .fuelType(tank.getFuelType())
                .pumpId(tank.getPumpId())
                .originalQuantity(quantity)
                .remainingQuantity(quantity)
                .costPricePerUnit(costPerLitre.setScale(3, RoundingMode.HALF_UP))
                .deliveryDate(asOf)
                .isDipAdjustment(false)
                .status(LotStatus.ACTIVE)
                .build());
        BigDecimal stock = tank.getCurrentStock() != null ? tank.getCurrentStock() : BigDecimal.ZERO;
        tank.setCurrentStock(stock.add(quantity).setScale(3, RoundingMode.HALF_UP));
        tankRepository.save(tank);
        return lot;
    }
}
