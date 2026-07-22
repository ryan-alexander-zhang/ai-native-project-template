/**
 * Interface layer of the payment context: the inbound adapter that translates ordering's
 * PaymentRequested integration event into an authorize-payment use-case call. Depends inward on the
 * application layer; it reads only ordering's published contract.
 */
@InterfaceLayer
package com.example.payment.adapter;

import com.aipersimmon.ddd.core.architecture.InterfaceLayer;
