package dev.expository.transfer;

/** Isolated disposable OS picker root for >12 read-only collision differences. */
public final class RecursivePickerPagingSourceBProvider extends RecursivePickerBaseProvider {
    @Override boolean isSource() { return true; }
    @Override String fixtureTitle() { return "Expository Paging B Lab"; }
    @Override boolean seedPagingCollisions() { return true; }
}
