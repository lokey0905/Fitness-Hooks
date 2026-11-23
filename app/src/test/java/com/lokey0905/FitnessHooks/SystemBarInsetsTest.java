package com.lokey0905.FitnessHooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class SystemBarInsetsTest {
    @Test
    public void keyboardInsetsUseMaximumAndRepeatedDispatchDoesNotAccumulatePadding() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            LinearLayout root = new LinearLayout(activity);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(2, 3, 4, 5);
            View toolbar = new View(activity);
            toolbar.setPadding(8, 9, 10, 11);
            ScrollView scroll = new ScrollView(activity);
            scroll.setPadding(12, 13, 14, 15);
            root.addView(toolbar, new LinearLayout.LayoutParams(-1, 72));
            root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
            activity.setContentView(root);
            SystemBarInsets.apply(root, toolbar, scroll);

            WindowInsetsCompat keyboardOpen = insets(Insets.of(0, 24, 0, 24),
                    Insets.of(44, 0, 0, 0), 300);
            ViewCompat.dispatchApplyWindowInsets(root, keyboardOpen);
            assertPadding(root, 46, 3, 4, 5);
            assertPadding(toolbar, 8, 33, 10, 11);
            assertPadding(scroll, 12, 13, 14, 315);

            ViewCompat.dispatchApplyWindowInsets(root, keyboardOpen);
            assertPadding(root, 46, 3, 4, 5);
            assertPadding(toolbar, 8, 33, 10, 11);
            assertPadding(scroll, 12, 13, 14, 315);

            // Rotation / keyboard close must remove the old sides and IME space.
            ViewCompat.dispatchApplyWindowInsets(root,
                    insets(Insets.of(0, 32, 48, 0), Insets.NONE, 0));
            assertPadding(root, 2, 3, 52, 5);
            assertPadding(toolbar, 8, 41, 10, 11);
            assertPadding(scroll, 12, 13, 14, 15);
        }
    }

    @Test
    public void screenWithoutToolbarKeepsItsViewportBelowTopInsets() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            FrameLayout root = new FrameLayout(activity);
            root.setPadding(1, 2, 3, 4);
            ScrollView scroll = new ScrollView(activity);
            scroll.setPadding(5, 6, 7, 8);
            root.addView(scroll, new ViewGroup.LayoutParams(-1, -1));
            activity.setContentView(root);
            SystemBarInsets.apply(root, null, scroll);

            ViewCompat.dispatchApplyWindowInsets(root,
                    insets(Insets.of(0, 30, 0, 24), Insets.NONE, 0));
            assertPadding(root, 1, 32, 3, 4);
            assertPadding(scroll, 5, 6, 7, 32);
        }
    }

    @Test
    public void growingKeyboardRevealsExistingFocusInThePaddedViewport() {
        try (ScrollingFixture fixture = new ScrollingFixture()) {
            fixture.focusInput();
            assertEquals(340, fixture.scroll.getScrollY());
            fixture.dispatchKeyboard(220);
            // Insets only schedule the adjustment; geometry must finish updating first.
            assertEquals(340, fixture.scroll.getScrollY());
            fixture.layoutAndPreDraw();
            assertEquals(480, fixture.scroll.getScrollY());
            assertTrue(fixture.input.isFocused());
            Rect bounds = new Rect();
            fixture.input.getDrawingRect(bounds);
            fixture.scroll.offsetDescendantRectToMyCoords(fixture.input, bounds);
            assertTrue(bounds.bottom <= fixture.scroll.getScrollY() + fixture.scroll.getHeight()
                    - fixture.scroll.getPaddingBottom());
        }
    }

    @Test
    public void unchangedOrShrinkingKeyboardDoesNotOverrideUserScrolling() {
        try (ScrollingFixture fixture = new ScrollingFixture()) {
            fixture.focusInput();
            fixture.dispatchKeyboard(220);
            fixture.layoutAndPreDraw();
            fixture.scroll.scrollTo(0, 140);
            fixture.dispatchKeyboard(220);
            fixture.layoutAndPreDraw();
            assertEquals(140, fixture.scroll.getScrollY());
            fixture.dispatchKeyboard(0);
            fixture.layoutAndPreDraw();
            assertEquals(140, fixture.scroll.getScrollY());
        }
    }

    @Test
    public void keyboardClosingBeforeLayoutInvalidatesThePendingReveal() {
        try (ScrollingFixture fixture = new ScrollingFixture()) {
            fixture.focusInput();
            fixture.dispatchKeyboard(220);
            fixture.dispatchKeyboard(0);
            fixture.layoutAndPreDraw();
            assertEquals(340, fixture.scroll.getScrollY());
        }
    }

    @Test
    public void changedOrAbsentFocusDoesNotTriggerAStaleReveal() {
        try (ScrollingFixture fixture = new ScrollingFixture()) {
            fixture.focusInput();
            fixture.dispatchKeyboard(220);
            assertTrue(fixture.root.requestFocus());
            fixture.layoutAndPreDraw();
            assertEquals(340, fixture.scroll.getScrollY());
            fixture.dispatchKeyboard(260);
            fixture.layoutAndPreDraw();
            assertEquals(340, fixture.scroll.getScrollY());
        }
    }

    @Test
    @Config(sdk = {28, 33, 36}, qualifiers = "notnight")
    public void lightSurfaceUsesDarkSystemBarIconsOnSupportedAndroidVersions() {
        assertSystemBars(true);
    }

    @Test
    @Config(sdk = {28, 33, 36}, qualifiers = "night")
    public void darkSurfaceUsesLightSystemBarIconsOnSupportedAndroidVersions() {
        assertSystemBars(false);
    }

    @SuppressWarnings("deprecation")
    private void assertSystemBars(boolean lightSurface) {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = controller.get();
            activity.setTheme(R.style.Theme_FitnessHooks);
            SystemBarInsets.enable(activity);
            assertEquals(lightSurface, WindowCompat.getInsetsController(activity.getWindow(),
                    activity.getWindow().getDecorView()).isAppearanceLightStatusBars());
            assertEquals(lightSurface, WindowCompat.getInsetsController(activity.getWindow(),
                    activity.getWindow().getDecorView()).isAppearanceLightNavigationBars());
            if (android.os.Build.VERSION.SDK_INT < 35) {
                assertEquals(Color.TRANSPARENT, activity.getWindow().getStatusBarColor());
                assertEquals(Color.TRANSPARENT, activity.getWindow().getNavigationBarColor());
            }
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                assertFalse(activity.getWindow().isStatusBarContrastEnforced());
                assertTrue(activity.getWindow().isNavigationBarContrastEnforced());
            }
            assertEquals(android.os.Build.VERSION.SDK_INT >= 30
                            ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                            : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES,
                    activity.getWindow().getAttributes().layoutInDisplayCutoutMode);
        }
    }

    private static WindowInsetsCompat insets(Insets bars, Insets cutout, int imeBottom) {
        return new WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.systemBars(), bars)
                .setInsets(WindowInsetsCompat.Type.displayCutout(), cutout)
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imeBottom))
                .build();
    }

    private static void assertPadding(View view, int left, int top, int right, int bottom) {
        assertEquals(left, view.getPaddingLeft());
        assertEquals(top, view.getPaddingTop());
        assertEquals(right, view.getPaddingRight());
        assertEquals(bottom, view.getPaddingBottom());
    }

    private static final class ScrollingFixture implements AutoCloseable {
        final ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup();
        final FrameLayout root = new FrameLayout(controller.get());
        final ScrollView scroll = new ScrollView(controller.get());
        final EditText input = new EditText(controller.get());

        ScrollingFixture() {
            root.setFocusableInTouchMode(true);
            LinearLayout content = new LinearLayout(controller.get());
            content.setOrientation(LinearLayout.VERTICAL);
            content.addView(new View(controller.get()), new LinearLayout.LayoutParams(-1, 600));
            content.addView(input, new LinearLayout.LayoutParams(-1, 60));
            content.addView(new View(controller.get()), new LinearLayout.LayoutParams(-1, 340));
            scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
            root.addView(scroll, new ViewGroup.LayoutParams(-1, -1));
            controller.get().setContentView(root);
            SystemBarInsets.apply(root, null, scroll);
            dispatchKeyboard(0);
            layoutAndPreDraw();
        }

        void focusInput() {
            assertTrue(scroll.isAttachedToWindow());
            assertTrue(input.requestFocus());
            scroll.scrollTo(0, 340);
        }

        void dispatchKeyboard(int bottom) {
            ViewCompat.dispatchApplyWindowInsets(root, insets(Insets.of(0, 0, 0, 24), Insets.NONE, bottom));
        }

        void layoutAndPreDraw() {
            root.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, 320, 400);
            root.getViewTreeObserver().dispatchOnPreDraw();
        }

        @Override public void close() { controller.close(); }
    }
}
