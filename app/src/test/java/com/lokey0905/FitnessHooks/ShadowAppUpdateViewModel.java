package com.lokey0905.FitnessHooks;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

@Implements(AppUpdateViewModel.class)
public final class ShadowAppUpdateViewModel {
    public int checks;

    @Implementation
    protected void checkOnce() {
        checks++;
    }
}
