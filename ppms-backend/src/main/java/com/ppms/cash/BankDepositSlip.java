package com.ppms.cash;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * The photo or PDF of a deposit slip, kept in the database so the nightly database backup covers it and the
 * container needs no extra volume. Kept in its own table so listing deposits never reads the bytes.
 */
@Entity
@Table(name = "bank_deposit_slips")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BankDepositSlip {

    @Id
    @Column(name = "bank_deposit_id")
    private Long bankDepositId;

    @Column(name = "content_type", nullable = false, length = 50)
    private String contentType;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "size_bytes", nullable = false)
    private Integer sizeBytes;

    @Column(name = "content", nullable = false)
    private byte[] content;

    @Column(name = "uploaded_by_user_id", nullable = false)
    private Long uploadedByUserId;

    @Column(name = "uploaded_at", nullable = false)
    private OffsetDateTime uploadedAt;
}
