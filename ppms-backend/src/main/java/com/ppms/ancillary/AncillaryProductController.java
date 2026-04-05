package com.ppms.ancillary;

import com.ppms.audit.AuditAction;
import com.ppms.audit.AuditService;
import com.ppms.cash.CashEvent;
import com.ppms.cash.CashEventRepository;
import com.ppms.cash.CashEventType;
import com.ppms.common.dto.PagedResponse;
import com.ppms.common.exception.BusinessException;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.transaction.TransactionPaymentMode;
import com.ppms.user.User;
import com.ppms.user.UserRole;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * REST controller for the Ancillary Products feature.
 *
 * All endpoints are scoped to /{pumpId}/ancillary/... to keep them consistent
 * with ExpenseController and other pump-scoped controllers.
 *
 * Role rules:
 * - Product management (create/update/status/price): OWNER, ADMIN only
 * - Stock deliveries: OWNER, ADMIN, MANAGER
 * - Sales: OWNER, ADMIN, MANAGER
 * - Read endpoints: any authenticated user (guards applied per endpoint)
 */
@Slf4j
@RestController
@RequestMapping("/api/pumps")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'MANAGER', 'OPERATOR')")
public class AncillaryProductController {

    private final AncillaryProductRepository      productRepository;
    private final AncillaryProductPriceRepository  priceRepository;
    private final AncillaryStockDeliveryRepository deliveryRepository;
    private final AncillaryStockLotRepository      lotRepository;
    private final AncillaryLotConsumptionRepository consumptionRepository;
    private final AncillarySaleRepository          saleRepository;
    private final CashEventRepository              cashEventRepository;
    private final AuditService                     auditService;

    // ── Product catalog ───────────────────────────────────────────────────────

    /**
     * GET /api/pumps/{pumpId}/ancillary/products
     * Returns all products for this pump, all statuses.
     * Each product includes the current selling price (null if not set).
     */
    @GetMapping("/{pumpId}/ancillary/products")
    public ResponseEntity<List<AncillaryProductResponse>> getProducts(@PathVariable Long pumpId) {
        List<AncillaryProduct> products = productRepository.findByPumpId(pumpId);

        if (products.isEmpty()) {
            return ResponseEntity.ok(List.of());
        }

        List<Long> productIds = products.stream().map(AncillaryProduct::getId).toList();

        // Batch-load ALL active FIFO lots for all products in one query (no N+1).
        // Grouped by productId so each product gets its ordered lot list.
        Map<Long, List<AncillaryStockLot>> activeLotsByProductId = lotRepository
                .findAllActiveLotsByProductIds(productIds)
                .stream()
                .collect(Collectors.groupingBy(AncillaryStockLot::getProductId));

        List<AncillaryProductResponse> response = products.stream()
                .map(p -> toProductResponse(p, activeLotsByProductId.getOrDefault(p.getId(), List.of())))
                .toList();
        return ResponseEntity.ok(response);
    }

    /**
     * POST /api/pumps/{pumpId}/ancillary/products
     * Creates a new SKU in the catalog for this pump.
     * Initial stock is 0 — stock-in is recorded separately via the deliveries endpoint.
     * Owner/Admin only.
     */
    @PostMapping("/{pumpId}/ancillary/products")
    @Transactional
    public ResponseEntity<AncillaryProductResponse> createProduct(
            @PathVariable Long pumpId,
            @Valid @RequestBody CreateProductRequest request,
            @AuthenticationPrincipal User currentUser) {

        requireOwnerOrAdmin(currentUser);

        // Default GST to 18% (standard India slab for automotive products) if not specified
        BigDecimal gstRate = request.getGstRatePercent() != null
                ? request.getGstRatePercent().setScale(2, RoundingMode.HALF_UP)
                : new BigDecimal("18.00");

        AncillaryProduct product = AncillaryProduct.builder()
                .pumpId(pumpId)
                .name(request.getName().trim())
                .brand(request.getBrand() != null ? request.getBrand().trim() : null)
                .variant(request.getVariant() != null ? request.getVariant().trim() : null)
                .packageSize(request.getPackageSize().setScale(3, RoundingMode.HALF_UP))
                .unitOfMeasure(request.getUnitOfMeasure())
                .currentStockUnits(0)
                .lowStockThreshold(request.getLowStockThreshold())
                .gstRatePercent(gstRate)
                .status(AncillaryProductStatus.ACTIVE)
                .build();

        product = productRepository.save(product);

        log.info("Ancillary product created: pump={}, productId={}, name={}, by={}",
                pumpId, product.getId(), product.getName(), currentUser.getId());

        return ResponseEntity.status(HttpStatus.CREATED).body(toProductResponseWithPriceLookup(product));
    }

    /**
     * PATCH /api/pumps/{pumpId}/ancillary/products/{id}
     * Updates brand, variant, and/or lowStockThreshold.
     * Name, packageSize, and unitOfMeasure cannot be changed after creation.
     * Owner/Admin only.
     */
    @PatchMapping("/{pumpId}/ancillary/products/{id}")
    @Transactional
    public ResponseEntity<AncillaryProductResponse> updateProduct(
            @PathVariable Long pumpId,
            @PathVariable Long id,
            @RequestBody UpdateProductRequest request,
            @AuthenticationPrincipal User currentUser) {

        requireOwnerOrAdmin(currentUser);

        AncillaryProduct product = findProductForPump(id, pumpId);

        if (request.getBrand() != null) {
            product.setBrand(request.getBrand().isBlank() ? null : request.getBrand().trim());
        }
        if (request.getVariant() != null) {
            product.setVariant(request.getVariant().isBlank() ? null : request.getVariant().trim());
        }
        // lowStockThreshold update: explicitly set (including null to clear the threshold)
        product.setLowStockThreshold(request.getLowStockThreshold());
        if (request.getGstRatePercent() != null) {
            product.setGstRatePercent(request.getGstRatePercent().setScale(2, RoundingMode.HALF_UP));
        }

        product = productRepository.save(product);

        log.info("Ancillary product updated: pump={}, productId={}, by={}", pumpId, id, currentUser.getId());

        return ResponseEntity.ok(toProductResponseWithPriceLookup(product));
    }

    /**
     * PATCH /api/pumps/{pumpId}/ancillary/products/{id}/status
     * Toggles product status between ACTIVE and INACTIVE.
     * Inactive products cannot be sold or restocked.
     * Owner/Admin only.
     */
    @PatchMapping("/{pumpId}/ancillary/products/{id}/status")
    @Transactional
    public ResponseEntity<AncillaryProductResponse> setProductStatus(
            @PathVariable Long pumpId,
            @PathVariable Long id,
            @RequestParam AncillaryProductStatus status,
            @AuthenticationPrincipal User currentUser) {

        requireOwnerOrAdmin(currentUser);

        AncillaryProduct product = findProductForPump(id, pumpId);
        product.setStatus(status);
        product = productRepository.save(product);

        log.info("Ancillary product status changed: pump={}, productId={}, newStatus={}, by={}",
                pumpId, id, status, currentUser.getId());

        return ResponseEntity.ok(toProductResponseWithPriceLookup(product));
    }

    // ── Selling price history ─────────────────────────────────────────────────

    /**
     * GET /api/pumps/{pumpId}/ancillary/products/{id}/prices
     * Returns full price history for a product, newest first.
     */
    @GetMapping("/{pumpId}/ancillary/products/{id}/prices")
    public ResponseEntity<List<AncillaryProductPrice>> getPriceHistory(
            @PathVariable Long pumpId,
            @PathVariable Long id) {
        findProductForPump(id, pumpId); // ownership check
        return ResponseEntity.ok(priceRepository.findByProductIdOrderByEffectiveFromDesc(id));
    }

    /**
     * POST /api/pumps/{pumpId}/ancillary/products/{id}/prices
     * Sets a new selling price. Does not modify previous price rows — immutable history.
     * Owner/Admin only.
     */
    @PostMapping("/{pumpId}/ancillary/products/{id}/prices")
    @Transactional
    public ResponseEntity<AncillaryProductPrice> setPrice(
            @PathVariable Long pumpId,
            @PathVariable Long id,
            @Valid @RequestBody SetProductPriceRequest request,
            @AuthenticationPrincipal User currentUser) {

        requireOwnerOrAdmin(currentUser);

        AncillaryProduct product = findProductForPump(id, pumpId);

        AncillaryProductPrice price = AncillaryProductPrice.builder()
                .productId(product.getId())
                .pumpId(pumpId)
                .pricePerUnit(request.getPricePerUnit().setScale(2, RoundingMode.HALF_UP))
                .effectiveFrom(OffsetDateTime.now())
                .setByUserId(currentUser.getId())
                .build();

        price = priceRepository.save(price);

        log.info("Ancillary product price set: pump={}, productId={}, price={}, by={}",
                pumpId, id, price.getPricePerUnit(), currentUser.getId());

        return ResponseEntity.status(HttpStatus.CREATED).body(price);
    }

    // ── Stock deliveries ──────────────────────────────────────────────────────

    /**
     * POST /api/pumps/{pumpId}/ancillary/products/{id}/deliveries
     * Records a stock-in batch. Creates an inventory lot for FIFO tracking.
     * Owner/Admin/Manager.
     */
    @PostMapping("/{pumpId}/ancillary/products/{id}/deliveries")
    @Transactional
    public ResponseEntity<AncillaryStockDelivery> recordDelivery(
            @PathVariable Long pumpId,
            @PathVariable Long id,
            @Valid @RequestBody RecordStockDeliveryRequest request,
            @AuthenticationPrincipal User currentUser) {

        requireManagerOrAbove(currentUser);

        AncillaryProduct product = findProductForPump(id, pumpId);

        if (product.getStatus() == AncillaryProductStatus.INACTIVE) {
            throw new BusinessException("Product '" + product.getName() + "' is INACTIVE. Re-activate it before recording a delivery.");
        }

        AncillaryStockDelivery delivery = AncillaryStockDelivery.builder()
                .productId(product.getId())
                .pumpId(pumpId)
                .quantityUnits(request.getQuantityUnits())
                .costPricePerUnit(request.getCostPricePerUnit().setScale(2, RoundingMode.HALF_UP))
                .deliveryDate(request.getDeliveryDate())
                .invoiceReference(request.getInvoiceReference() != null ? request.getInvoiceReference().trim() : null)
                .notes(request.getNotes())
                .loggedByUserId(currentUser.getId())
                .build();

        delivery = deliveryRepository.save(delivery);

        // Create FIFO lot — one lot per delivery batch
        AncillaryStockLot lot = AncillaryStockLot.builder()
                .deliveryId(delivery.getId())
                .productId(product.getId())
                .pumpId(pumpId)
                .originalQuantity(request.getQuantityUnits())
                .remainingQuantity(request.getQuantityUnits())
                .costPricePerUnit(delivery.getCostPricePerUnit())
                .deliveryDate(request.getDeliveryDate())
                .status(AncillaryLotStatus.ACTIVE)
                .build();

        lotRepository.save(lot);

        // Increment product stock count
        product.setCurrentStockUnits(product.getCurrentStockUnits() + request.getQuantityUnits());
        productRepository.save(product);

        log.info("Ancillary delivery recorded: pump={}, productId={}, units={}, cost={}, lot={}, by={}",
                pumpId, id, request.getQuantityUnits(), delivery.getCostPricePerUnit(),
                lot.getId(), currentUser.getId());

        auditService.log(pumpId, AuditAction.ANCILLARY_DELIVERY_RECORDED,
                "AncillaryStockDelivery", delivery.getId().toString(),
                "Stock delivery: " + product.getName() + " × " + request.getQuantityUnits() +
                " units at ₹" + delivery.getCostPricePerUnit() + "/unit",
                currentUser);

        return ResponseEntity.status(HttpStatus.CREATED).body(delivery);
    }

    /**
     * GET /api/pumps/{pumpId}/ancillary/deliveries?page=0&size=50
     * Returns paginated deliveries for a pump, newest first.
     */
    @GetMapping("/{pumpId}/ancillary/deliveries")
    public ResponseEntity<PagedResponse<AncillaryStockDelivery>> getDeliveries(
            @PathVariable Long pumpId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(PagedResponse.of(
                deliveryRepository.findByPumpIdOrderByDeliveryDateDescCreatedAtDesc(pumpId, PageRequest.of(page, size))));
    }

    // ── Counter sales ─────────────────────────────────────────────────────────

    /**
     * POST /api/pumps/{pumpId}/ancillary/sales
     * Records a counter sale. Performs FIFO lot deduction and snapshots the current price.
     *
     * Business rules enforced:
     * 1. Product must be ACTIVE and belong to this pump.
     * 2. A selling price must be set — sale is rejected if no price exists.
     * 3. Stock must be sufficient — rejected if not enough units available.
     * 4. CREDIT sales require clientName.
     * 5. Only OWNER, ADMIN, MANAGER can record sales.
     */
    @PostMapping("/{pumpId}/ancillary/sales")
    @Transactional
    public ResponseEntity<AncillarySaleResponse> recordSale(
            @PathVariable Long pumpId,
            @Valid @RequestBody RecordAncillarySaleRequest request,
            @AuthenticationPrincipal User currentUser) {

        requireManagerOrAbove(currentUser);

        // 1. Validate product
        AncillaryProduct product = findProductForPump(request.getProductId(), pumpId);
        if (product.getStatus() == AncillaryProductStatus.INACTIVE) {
            throw new BusinessException("Product '" + product.getName() + "' is INACTIVE and cannot be sold.");
        }

        // 2. Credit sales require a client name
        if (request.getPaymentMode() == TransactionPaymentMode.CREDIT
                && (request.getClientName() == null || request.getClientName().isBlank())) {
            throw new BusinessException("Client name is required for CREDIT payment mode.");
        }

        // 3. Check available stock
        if (product.getCurrentStockUnits() < request.getQuantityUnits()) {
            throw new BusinessException(
                    "Insufficient stock for '" + product.getName() + "'. Available: "
                    + product.getCurrentStockUnits() + " units, requested: " + request.getQuantityUnits() + " units.");
        }

        // 4. Use the MRP entered by the operator at point of sale.
        //    Products are sold at MRP which is inclusive of all taxes — no GST is added on top.
        BigDecimal sellingPrice = request.getSellingPricePerUnit().setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalAmount  = sellingPrice
                .multiply(new BigDecimal(request.getQuantityUnits()))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal gstAmount    = BigDecimal.ZERO.setScale(2);
        BigDecimal totalWithGst = totalAmount;

        // 6. Persist the sale
        AncillarySale sale = AncillarySale.builder()
                .pumpId(pumpId)
                .productId(product.getId())
                .quantityUnits(request.getQuantityUnits())
                .sellingPricePerUnit(sellingPrice)
                .totalAmount(totalAmount)
                .gstAmount(gstAmount)
                .totalWithGst(totalWithGst)
                .paymentMode(request.getPaymentMode())
                .clientId(request.getClientId())
                .clientName(request.getClientName() != null ? request.getClientName().trim() : null)
                .billNo(request.getBillNo() != null ? request.getBillNo().trim() : null)
                .notes(request.getNotes())
                .soldByUserId(currentUser.getId())
                .saleDate(LocalDate.now())
                .build();

        sale = saleRepository.save(sale);

        // 7. FIFO lot deduction — oldest lots consumed first
        deductFromLots(product.getId(), sale.getId(), request.getQuantityUnits());

        // 8. Decrement product stock
        product.setCurrentStockUnits(product.getCurrentStockUnits() - request.getQuantityUnits());
        productRepository.save(product);

        log.info("Ancillary sale recorded: pump={}, productId={}, saleId={}, units={}, total={}, mode={}, by={}",
                pumpId, product.getId(), sale.getId(), request.getQuantityUnits(),
                totalAmount, request.getPaymentMode(), currentUser.getId());

        auditService.log(pumpId, AuditAction.ANCILLARY_SALE_RECORDED,
                "AncillarySale", sale.getId().toString(),
                "Sale: " + product.getName() + " × " + request.getQuantityUnits() + " units, total ₹" +
                totalAmount + " (" + request.getPaymentMode() + ")",
                currentUser);

        // Auto-record a CASH_IN event in the cash drawer for cash payments.
        // This keeps the drawer balance accurate without requiring manual entry.
        if (request.getPaymentMode() == TransactionPaymentMode.CASH) {
            CashEvent cashEvent = CashEvent.builder()
                    .pumpId(pumpId)
                    .eventType(CashEventType.CASH_IN)
                    .amount(totalAmount)
                    .description("Counter sale: " + product.getName() + " × " + request.getQuantityUnits() + " units (Sale #" + sale.getId() + ")")
                    .eventDate(LocalDate.now())
                    .recordedByUserId(currentUser.getId())
                    .build();
            cashEventRepository.save(cashEvent);
            log.info("Cash event recorded for ancillary sale: pump={}, saleId={}, amount={}", pumpId, sale.getId(), totalWithGst);
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(toSaleResponse(sale, product));
    }

    /**
     * GET /api/pumps/{pumpId}/ancillary/sales?page=0&size=50
     * Returns paginated sales for a pump, newest first.
     */
    @GetMapping("/{pumpId}/ancillary/sales")
    public ResponseEntity<PagedResponse<AncillarySaleResponse>> getSales(
            @PathVariable Long pumpId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        var salesPage = saleRepository.findByPumpIdOrderBySaleDateDescCreatedAtDesc(pumpId, PageRequest.of(page, size));

        // Batch-load all unique products referenced by this page's sales in a single query.
        Map<Long, AncillaryProduct> productById = productRepository
                .findAllById(salesPage.getContent().stream().map(AncillarySale::getProductId).collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(AncillaryProduct::getId, Function.identity()));

        var mapped = salesPage.map(sale -> toSaleResponse(sale, productById.get(sale.getProductId())));
        return ResponseEntity.ok(PagedResponse.of(mapped));
    }

    // ── Stock lots ────────────────────────────────────────────────────────────

    /**
     * GET /api/pumps/{pumpId}/ancillary/products/{id}/lots
     * Returns all ACTIVE FIFO lots for the product in consumption order (oldest first).
     * Exhausted lots are excluded — the dialog only shows remaining stock.
     * Any authenticated user can view this.
     */
    @GetMapping("/{pumpId}/ancillary/products/{id}/lots")
    public ResponseEntity<List<AncillaryLotDetailResponse>> getActiveLots(
            @PathVariable Long pumpId,
            @PathVariable Long id) {

        findProductForPump(id, pumpId); // ownership check

        List<AncillaryStockLot> lots = lotRepository
                .findByProductIdAndStatusOrderByDeliveryDateAscIdAsc(id, AncillaryLotStatus.ACTIVE);

        if (lots.isEmpty()) {
            return ResponseEntity.ok(List.of());
        }

        // Batch-load deliveries to resolve invoiceReference — avoids N+1 query.
        List<Long> deliveryIds = lots.stream().map(AncillaryStockLot::getDeliveryId).distinct().toList();
        Map<Long, AncillaryStockDelivery> deliveryById = deliveryRepository.findAllById(deliveryIds)
                .stream()
                .collect(Collectors.toMap(AncillaryStockDelivery::getId, Function.identity()));

        List<AncillaryLotDetailResponse> response = lots.stream()
                .map(l -> {
                    AncillaryStockDelivery delivery = deliveryById.get(l.getDeliveryId());
                    return AncillaryLotDetailResponse.builder()
                            .id(l.getId())
                            .deliveryId(l.getDeliveryId())
                            .deliveryDate(l.getDeliveryDate())
                            .invoiceReference(delivery != null ? delivery.getInvoiceReference() : null)
                            .costPricePerUnit(l.getCostPricePerUnit())
                            .remainingQuantity(l.getRemainingQuantity())
                            .originalQuantity(l.getOriginalQuantity())
                            .status(l.getStatus().name())
                            .createdAt(l.getCreatedAt())
                            .build();
                })
                .toList();

        return ResponseEntity.ok(response);
    }

    /**
     * PATCH /api/pumps/{pumpId}/ancillary/lots/{lotId}
     * Adjusts a lot's cost price and/or remaining quantity (stock correction).
     * Owner/Admin only.
     *
     * When remainingQuantity changes, product.currentStockUnits is adjusted by the delta
     * so the product's total stock stays consistent.
     */
    @PatchMapping("/{pumpId}/ancillary/lots/{lotId}")
    @Transactional
    public ResponseEntity<AncillaryLotDetailResponse> updateLot(
            @PathVariable Long pumpId,
            @PathVariable Long lotId,
            @RequestBody UpdateLotRequest request,
            @AuthenticationPrincipal User currentUser) {

        requireOwnerOrAdmin(currentUser);

        AncillaryStockLot lot = lotRepository.findById(lotId)
                .orElseThrow(() -> new ResourceNotFoundException("Stock lot not found: " + lotId));

        if (!lot.getPumpId().equals(pumpId)) {
            throw new BusinessException("Lot does not belong to this pump.");
        }

        AncillaryProduct product = productRepository.findById(lot.getProductId())
                .orElseThrow(() -> new ResourceNotFoundException("Product not found for lot: " + lotId));

        if (request.getRemainingQuantity() != null) {
            int newQty = request.getRemainingQuantity();
            if (newQty < 0) {
                throw new BusinessException("Remaining quantity cannot be negative.");
            }
            if (newQty > lot.getOriginalQuantity()) {
                throw new BusinessException(
                        "Remaining quantity cannot exceed original quantity (" + lot.getOriginalQuantity() + ").");
            }
            // Sync product stock: positive delta adds stock, negative removes it
            int delta = newQty - lot.getRemainingQuantity();
            lot.setRemainingQuantity(newQty);
            lot.setStatus(newQty == 0 ? AncillaryLotStatus.EXHAUSTED : AncillaryLotStatus.ACTIVE);
            product.setCurrentStockUnits(product.getCurrentStockUnits() + delta);
            productRepository.save(product);
        }

        if (request.getCostPricePerUnit() != null) {
            if (request.getCostPricePerUnit().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException("Cost price must be greater than 0.");
            }
            lot.setCostPricePerUnit(request.getCostPricePerUnit().setScale(2, RoundingMode.HALF_UP));
        }

        lot = lotRepository.save(lot);

        log.info("Ancillary lot corrected: pump={}, lotId={}, remainingQty={}, costPrice={}, by={}",
                pumpId, lotId, lot.getRemainingQuantity(), lot.getCostPricePerUnit(), currentUser.getId());

        AncillaryStockDelivery delivery = deliveryRepository.findById(lot.getDeliveryId()).orElse(null);

        return ResponseEntity.ok(AncillaryLotDetailResponse.builder()
                .id(lot.getId())
                .deliveryId(lot.getDeliveryId())
                .deliveryDate(lot.getDeliveryDate())
                .invoiceReference(delivery != null ? delivery.getInvoiceReference() : null)
                .costPricePerUnit(lot.getCostPricePerUnit())
                .remainingQuantity(lot.getRemainingQuantity())
                .originalQuantity(lot.getOriginalQuantity())
                .status(lot.getStatus().name())
                .createdAt(lot.getCreatedAt())
                .build());
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * FIFO lot deduction for a sale.
     *
     * Iterates active lots in FIFO order (oldest delivery date first, then lot ID as tiebreaker).
     * Consumes as many units as possible from each lot until the full quantity is covered.
     * Creates one AncillaryLotConsumption record per lot touched.
     *
     * If there is an unexpected shortage (stock count drifted from lots — should not happen
     * in normal operation), a WARN log is emitted rather than throwing, to avoid leaving
     * the sale in a partial state.
     */
    private void deductFromLots(Long productId, Long saleId, int unitsToDeduct) {
        List<AncillaryStockLot> lots = lotRepository.findActiveLotsByProductFifo(productId);
        int remaining = unitsToDeduct;

        for (AncillaryStockLot lot : lots) {
            if (remaining <= 0) break;

            int consume = Math.min(remaining, lot.getRemainingQuantity());
            lot.setRemainingQuantity(lot.getRemainingQuantity() - consume);

            if (lot.getRemainingQuantity() == 0) {
                lot.setStatus(AncillaryLotStatus.EXHAUSTED);
            }

            lotRepository.save(lot);

            consumptionRepository.save(AncillaryLotConsumption.builder()
                    .lotId(lot.getId())
                    .saleId(saleId)
                    .quantityConsumed(consume)
                    .costPricePerUnit(lot.getCostPricePerUnit())
                    .build());

            remaining -= consume;
        }

        if (remaining > 0) {
            log.warn("Ancillary stock lot shortage: productId={}, saleId={}, {} units not covered by any lot. " +
                     "This indicates a stock count vs lot quantity drift — investigate.",
                    productId, saleId, remaining);
        }
    }

    /**
     * Loads a product by ID and verifies it belongs to the given pump.
     * Throws ResourceNotFoundException if not found, BusinessException if it belongs to another pump.
     */
    private AncillaryProduct findProductForPump(Long productId, Long pumpId) {
        AncillaryProduct product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Ancillary product not found: " + productId));
        if (!product.getPumpId().equals(pumpId)) {
            throw new BusinessException("Product does not belong to this pump.");
        }
        return product;
    }

    /**
     * Builds the human-readable display name from brand + name + variant + size + unit.
     * Example: "Castrol GTX 10W-40 — 1.000 L"
     */
    private String buildDisplayName(AncillaryProduct p) {
        StringBuilder sb = new StringBuilder();
        if (p.getBrand() != null && !p.getBrand().isBlank()) {
            sb.append(p.getBrand()).append(' ');
        }
        sb.append(p.getName());
        if (p.getVariant() != null && !p.getVariant().isBlank()) {
            sb.append(' ').append(p.getVariant());
        }
        sb.append(" — ")
          .append(p.getPackageSize().stripTrailingZeros().toPlainString())
          .append(' ')
          .append(p.getUnitOfMeasure().name());
        return sb.toString();
    }

    /**
     * Single-product convenience wrapper — loads all active FIFO lots for one product.
     * Used after create/update/status-change where only one product needs to be returned.
     * Use toProductResponse(product, lots) when mapping a list to avoid N+1.
     */
    private AncillaryProductResponse toProductResponseWithPriceLookup(AncillaryProduct p) {
        List<AncillaryStockLot> lots = lotRepository.findAllActiveLotsByProductIds(List.of(p.getId()));
        return toProductResponse(p, lots);
    }

    /**
     * Builds the product response from a pre-loaded list of active FIFO lots.
     * The list must already be sorted in FIFO order (oldest delivery date first, then id).
     * Always prefer this overload over querying the DB inside the mapping to avoid N+1.
     *
     * @param activeLots active lots in FIFO order; empty when product is out of stock
     */
    private AncillaryProductResponse toProductResponse(AncillaryProduct p, List<AncillaryStockLot> activeLots) {
        // Derive the oldest-lot cost price from the pre-fetched list (no extra DB query).
        BigDecimal fifoCostPricePerUnit = activeLots.isEmpty() ? null : activeLots.get(0).getCostPricePerUnit();

        List<AncillaryProductResponse.FifoLotSummary> lotSummaries = activeLots.stream()
                .map(l -> AncillaryProductResponse.FifoLotSummary.builder()
                        .remainingQuantity(l.getRemainingQuantity())
                        .costPricePerUnit(l.getCostPricePerUnit())
                        .build())
                .toList();

        return AncillaryProductResponse.builder()
                .id(p.getId())
                .pumpId(p.getPumpId())
                .name(p.getName())
                .brand(p.getBrand())
                .variant(p.getVariant())
                .packageSize(p.getPackageSize())
                .unitOfMeasure(p.getUnitOfMeasure().name())
                .currentStockUnits(p.getCurrentStockUnits())
                .lowStockThreshold(p.getLowStockThreshold())
                .status(p.getStatus().name())
                .createdAt(p.getCreatedAt())
                .updatedAt(p.getUpdatedAt())
                .fifoCostPricePerUnit(fifoCostPricePerUnit)
                .activeFifoLots(lotSummaries)
                .displayName(buildDisplayName(p))
                .build();
    }

    private AncillarySaleResponse toSaleResponse(AncillarySale s, AncillaryProduct product) {
        String displayName = product != null ? buildDisplayName(product) : "Unknown Product";
        return AncillarySaleResponse.builder()
                .id(s.getId())
                .pumpId(s.getPumpId())
                .productId(s.getProductId())
                .productDisplayName(displayName)
                .quantityUnits(s.getQuantityUnits())
                .sellingPricePerUnit(s.getSellingPricePerUnit())
                .totalAmount(s.getTotalAmount())
                .gstAmount(s.getGstAmount())
                .totalWithGst(s.getTotalWithGst())
                .paymentMode(s.getPaymentMode().name())
                .clientId(s.getClientId())
                .clientName(s.getClientName())
                .billNo(s.getBillNo())
                .notes(s.getNotes())
                .soldByUserId(s.getSoldByUserId())
                .saleDate(s.getSaleDate())
                .createdAt(s.getCreatedAt())
                .build();
    }

    private void requireOwnerOrAdmin(User user) {
        if (user.getRole() != UserRole.OWNER && user.getRole() != UserRole.ADMIN
                && user.getRole() != UserRole.SUPER_ADMIN) {
            throw new BusinessException("Only Owner or Admin can manage ancillary products and prices.");
        }
    }

    private void requireManagerOrAbove(User user) {
        if (user.getRole() == UserRole.OPERATOR || user.getRole() == UserRole.ACCOUNTANT) {
            throw new BusinessException("Operators and Accountants cannot record ancillary sales or deliveries.");
        }
    }
}
