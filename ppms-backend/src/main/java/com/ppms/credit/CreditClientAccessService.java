package com.ppms.credit;

import com.ppms.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CreditClientAccessService {

    private final CreditClientRepository clientRepository;

    public CreditClient requireClientForPump(Long pumpId, Long clientId) {
        return clientRepository.findById(clientId)
                .filter(client -> client.getPumpId().equals(pumpId))
                .orElseThrow(() -> new ResourceNotFoundException("Credit client not found"));
    }

    public List<CreditClient> loadPumpClients(Long pumpId) {
        return clientRepository.findByPumpIdOrderByNameAsc(pumpId);
    }
}
