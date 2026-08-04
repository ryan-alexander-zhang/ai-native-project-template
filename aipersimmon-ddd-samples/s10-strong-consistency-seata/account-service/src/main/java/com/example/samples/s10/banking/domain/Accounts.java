package com.example.samples.s10.banking.domain;

import java.util.Optional;

/** The account aggregate's port. */
public interface Accounts {

  Optional<Account> find(AccountId id);

  void save(Account account);
}
