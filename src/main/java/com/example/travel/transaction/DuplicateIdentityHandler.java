package com.example.travel.transaction;

import com.example.travel.domain.ConflictException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * Duplicate identity: uniqueness of external_id lives in a DB constraint, so
 * two overlapping POSTs cannot insert two rows. The loser's INSERT blocks on
 * the unique index until the winner commits, then fails; a fresh (autocommit)
 * read by external_id therefore sees the winner and we answer 409 with its id.
 */
@Component
public class DuplicateIdentityHandler {

    private final TransactionRepository repository;

    public DuplicateIdentityHandler(TransactionRepository repository) {
        this.repository = repository;
    }

    public ConflictException conflict(DuplicateKeyException cause, String externalId) {
        return repository.findByExternalId(externalId)
                .map(existing -> ConflictException.duplicateExternalId(existing.id()))
                .orElseThrow(() -> new IllegalStateException(
                        "unique constraint on external_id=" + externalId + " failed but no committed row found", cause));
    }
}
