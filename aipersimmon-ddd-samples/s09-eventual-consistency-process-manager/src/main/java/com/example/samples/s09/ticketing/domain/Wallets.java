package com.example.samples.s09.ticketing.domain;

import com.aipersimmon.ddd.core.annotation.Repository;
import java.util.Optional;

/** The write port for wallets. */
@Repository
public interface Wallets {

  void save(Wallet wallet);

  Optional<Wallet> find(WalletId id);
}
