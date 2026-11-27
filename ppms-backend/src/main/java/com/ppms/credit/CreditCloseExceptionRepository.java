package com.ppms.credit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CreditCloseExceptionRepository extends JpaRepository<CreditCloseException, Long> {
}
