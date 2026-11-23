package com.ppms.attention;

import java.math.BigDecimal;

/**
 * One thing an owner should act on today.
 *
 * @param amount     money involved, or null when the item is not about money
 * @param targetPath the page that handles it, with the filters that show exactly these records
 */
public record AttentionItem(AttentionType type, AttentionSeverity severity, Long pumpId, String pumpName,
                            long count, BigDecimal amount, String reason, String targetPath) {
}
