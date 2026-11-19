package com.alexis.payment.adapter.in.web.security;

/** The two kinds of consumer applications, each restricted to its own URL space. */
public enum ConsumerChannel {
    /** Checkout front/back: may only authorize and read payments. */
    ECOMMERCE("/api/v1/checkout/"),
    /** Merchant back-office: may only capture, void, refund and read payments. */
    MERCHANT_BO("/api/v1/merchant/");

    private final String pathPrefix;

    ConsumerChannel(String pathPrefix) {
        this.pathPrefix = pathPrefix;
    }

    public boolean allows(String path) {
        return path.startsWith(pathPrefix);
    }
}
