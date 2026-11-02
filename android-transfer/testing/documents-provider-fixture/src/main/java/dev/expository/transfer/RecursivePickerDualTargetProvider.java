package dev.expository.transfer;

/** Disposable Dual E2 picker destination, independent of the Single test. */
public final class RecursivePickerDualTargetProvider extends RecursivePickerBaseProvider {
    @Override boolean isSource(){return false;}
    @Override String fixtureTitle(){return "Expository Dual Target Lab";}
}
