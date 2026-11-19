package com.alexis.payment.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "psp_contract")
public class PspContractEntity {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    @Column(name = "psp_code", nullable = false)
    private String pspCode;

    @Column(name = "merchant_account_ref", nullable = false)
    private String merchantAccountRef;

    @Column(nullable = false)
    private int priority;

    @Column(nullable = false)
    private boolean active;

    protected PspContractEntity() {
    }

    String getMerchantId() { return merchantId; }
    String getPspCode() { return pspCode; }
    String getMerchantAccountRef() { return merchantAccountRef; }
}
