package com.ppms.fleet;

import com.ppms.common.exception.EntityType;
import com.ppms.common.exception.ResourceNotFoundException;
import com.ppms.credit.CreditClient;
import com.ppms.credit.CreditClientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** The credit account a client's vehicles belong to: the parent (or standalone client) and its sub-accounts. */
@Service
@RequiredArgsConstructor
public class FleetAccountService {

    private final CreditClientRepository clientRepository;

    /** @param clients the holder first, then its sub-accounts */
    public record Account(CreditClient holder, List<CreditClient> clients) {

        public List<Long> clientIds() {
            return clients.stream().map(CreditClient::getId).toList();
        }

        /** The holder's entries may use every vehicle of the group; a sub-account's, its own and the holder's. */
        public List<Long> vehicleOwnerIdsFor(CreditClient billedClient) {
            return billedClient.getId().equals(holder.getId())
                    ? clientIds()
                    : List.of(billedClient.getId(), holder.getId());
        }
    }

    public Account accountOf(CreditClient client) {
        CreditClient holder = client.getParentClientId() == null ? client
                : clientRepository.findById(client.getParentClientId())
                        .orElseThrow(() -> new ResourceNotFoundException(EntityType.CREDIT_CLIENT,
                                "Credit client not found: " + client.getParentClientId()));
        List<CreditClient> clients = new ArrayList<>();
        clients.add(holder);
        clients.addAll(clientRepository.findByParentClientId(holder.getId()));
        return new Account(holder, clients);
    }
}
