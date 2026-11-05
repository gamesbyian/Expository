package dev.expository.transfer;

/** Isolated disposable OS picker root for >12 read-only collision differences. */
public final class RecursivePickerPagingSourceAProvider extends RecursivePickerBaseProvider {
    @Override boolean isSource() { return true; }
    @Override String fixtureTitle() { return "Expository Paging A Lab"; }
    @Override boolean seedPagingCollisions() { return true; }
}
