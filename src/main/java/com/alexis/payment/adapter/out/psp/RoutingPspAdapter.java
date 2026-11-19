package com.alexis.payment.adapter.out.psp;

import com.alexis.payment.domain.model.Money;
import com.alexis.payment.domain.model.PspContract;
import com.alexis.payment.domain.model.PspReference;
import com.alexis.payment.domain.port.out.PspAdapterPort;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Single {@link PspAdapterPort} seen by the application; routes each call to the connector of the contract's PSP. */
public class RoutingPspAdapter implements PspAdapterPort {

    private final Map<String, PspConnector> connectors;

    public RoutingPspAdapter(List<PspConnector> connectors) {
        this.connectors = connectors.stream()
                .collect(Collectors.toUnmodifiableMap(PspConnector::pspCode, Function.identity()));
    }

    @Override
    public PspResponse authorize(AuthorizationRequest request) {
        return connector(request.contract()).authorize(request);
    }

    @Override
    public PspResponse capture(PspContract contract, PspReference authorization, Money amount, String key) {
        return connector(contract).capture(contract, authorization, amount, key);
    }

    @Override
    public PspResponse voidAuthorization(PspContract contract, PspReference authorization, String key) {
        return connector(contract).voidAuthorization(contract, authorization, key);
    }

    @Override
    public PspResponse refund(PspContract contract, PspReference authorization, Money amount, String key) {
        return connector(contract).refund(contract, authorization, amount, key);
    }

    private PspConnector connector(PspContract contract) {
        PspConnector connector = connectors.get(contract.pspCode());
        if (connector == null) {
            throw new IllegalStateException("No connector deployed for PSP " + contract.pspCode());
        }
        return connector;
    }
}
