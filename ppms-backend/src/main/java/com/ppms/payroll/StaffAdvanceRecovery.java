package com.ppms.payroll;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/** The part of an advance one payroll took back; removed with its payroll when a draft is deleted. */
@Entity
@Table(name = "staff_advance_recoveries")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StaffAdvanceRecovery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "advance_id", nullable = false)
    private Long advanceId;

    @Column(name = "payroll_id", nullable = false)
    private Long payrollId;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;
}
