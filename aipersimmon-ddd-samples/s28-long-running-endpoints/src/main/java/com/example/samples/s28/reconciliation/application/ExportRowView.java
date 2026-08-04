package com.example.samples.s28.reconciliation.application;

/**
 * One source row on its way to the file.
 *
 * <p>Not a value object of the domain and not an entity: the reconciliation context reads these rows and never
 * decides anything about one. It is a read projection with a lifetime of one line of CSV — which is the point.
 * The streaming export holds one of these at a time; the buffered counterexample holds every one of them.
 */
public record ExportRowView(long id, String orderRef, long amountCents, String note) {}
