package com.example.samples.s09.ticketing.infrastructure;

import com.aipersimmon.ddd.application.DomainEvents;
import com.aipersimmon.ddd.persistence.mybatisplus.MybatisPlusAggregateRepository;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.samples.s09.ticketing.domain.Wallet;
import com.example.samples.s09.ticketing.domain.WalletId;
import com.example.samples.s09.ticketing.domain.Wallets;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** The wallet and its ledger, saved as one aggregate. Same rewrite-the-children caveat as the seats. */
@Repository
class MyBatisWallets extends MybatisPlusAggregateRepository<Wallet, WalletRow> implements Wallets {

  private final WalletMapper mapper;
  private final WalletEntryMapper entryMapper;

  MyBatisWallets(WalletMapper mapper, WalletEntryMapper entryMapper, DomainEvents domainEvents) {
    super(mapper, domainEvents);
    this.mapper = mapper;
    this.entryMapper = entryMapper;
  }

  @Override
  public void save(Wallet wallet) {
    saveAggregate(wallet);
  }

  @Override
  public Optional<Wallet> find(WalletId id) {
    WalletRow row = mapper.selectById(id.value());
    if (row == null) {
      return Optional.empty();
    }
    List<Wallet.Entry> entries =
        entryMapper
            .selectList(
                new LambdaQueryWrapper<WalletEntryRow>()
                    .eq(WalletEntryRow::getCustomerId, id.value())
                    .orderByAsc(WalletEntryRow::getRecordedAt))
            .stream()
            .map(
                entry ->
                    new Wallet.Entry(
                        entry.getReference(),
                        Wallet.EntryKind.valueOf(entry.getKind()),
                        entry.getAmountMinor(),
                        entry.getNote(),
                        entry.getRecordedAt()))
            .toList();
    return Optional.of(Wallet.reconstitute(id, row.getBalanceMinor(), entries, row.getVersion()));
  }

  @Override
  protected WalletRow toRow(Wallet wallet) {
    WalletRow row = new WalletRow();
    row.setCustomerId(wallet.id().value());
    row.setBalanceMinor(wallet.balanceMinor());
    return row;
  }

  @Override
  protected void saveChildren(Wallet wallet) {
    entryMapper.delete(
        new LambdaQueryWrapper<WalletEntryRow>()
            .eq(WalletEntryRow::getCustomerId, wallet.id().value()));
    for (Wallet.Entry entry : wallet.entries()) {
      WalletEntryRow row = new WalletEntryRow();
      row.setReference(entry.reference());
      row.setCustomerId(wallet.id().value());
      row.setKind(entry.kind().name());
      row.setAmountMinor(entry.amountMinor());
      row.setNote(entry.note());
      row.setRecordedAt(entry.recordedAt());
      entryMapper.insert(row);
    }
  }
}
