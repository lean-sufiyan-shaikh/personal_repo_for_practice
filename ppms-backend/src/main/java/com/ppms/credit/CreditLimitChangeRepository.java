package com.ppms.credit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CreditLimitChangeRepository extends JpaRepository<CreditLimitChange, Long> {

    List<CreditLimitChange> findByClientIdOrderByChangedAtDescIdDesc(Long clientId);
}
