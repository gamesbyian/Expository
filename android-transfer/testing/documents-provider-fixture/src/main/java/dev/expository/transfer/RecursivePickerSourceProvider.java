package dev.expository.transfer;

/** Real system-picker source with immutable synthetic nested file and empty folder. */
public final class RecursivePickerSourceProvider extends RecursivePickerBaseProvider {
    @Override boolean isSource(){return true;}
    @Override String fixtureTitle(){return "Expository Source Lab";}
}
