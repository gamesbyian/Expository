package dev.expository.transfer;

/** Real system-picker destination, initially empty and never a user account. */
public final class RecursivePickerTargetProvider extends RecursivePickerBaseProvider {
    @Override boolean isSource(){return false;}
    @Override String fixtureTitle(){return "Expository Target Lab";}
}
