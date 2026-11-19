package com.alexis.payment.adapter.out.psp;

import com.alexis.payment.domain.port.out.PspAdapterPort;

/** One implementation per PSP (Stripe, Adyen, ...). {@link RoutingPspAdapter} dispatches by {@link #pspCode()}. */
public interface PspConnector extends PspAdapterPort {

    String pspCode();
}
