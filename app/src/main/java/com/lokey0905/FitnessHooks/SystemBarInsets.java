package com.lokey0905.FitnessHooks;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ScrollView;

import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.OneShotPreDrawListener;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.color.MaterialColors;

/** Shared edge-to-edge handling for the module's Views-based activities. */
final class SystemBarInsets {
    private SystemBarInsets() {}

    @SuppressWarnings("deprecation")
    static void enable(Activity activity) {
        Window window = activity.getWindow();
        WindowCompat.setDecorFitsSystemWindows(window, false);
        // These colors are still needed on older supported Android versions.
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) {
            window.setStatusBarContrastEnforced(false);
            // Keep the platform scrim for three-button navigation; gesture navigation
            // remains transparent, while our content receives navigation-bar insets.
            window.setNavigationBarContrastEnforced(true);
        }
        WindowManager.LayoutParams attributes = window.getAttributes();
        attributes.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= 30
                ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        window.setAttributes(attributes);
        int surface = MaterialColors.getColor(activity,
                com.google.android.material.R.attr.colorSurface, Color.BLACK);
        boolean light = MaterialColors.isColorLight(surface);
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(
                window, window.getDecorView());
        controller.setAppearanceLightStatusBars(light);
        controller.setAppearanceLightNavigationBars(light);
    }

    /** Header can be null for a scrolling screen without a toolbar. */
    static void apply(View root, @Nullable View header, View scroll) {
        Padding rootPadding = new Padding(root);
        Padding headerPadding = header == null ? null : new Padding(header);
        Padding scrollPadding = new Padding(scroll);
        KeyboardState keyboardState = new KeyboardState();
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            Insets keyboard = insets.getInsets(WindowInsetsCompat.Type.ime());
            // The root owns horizontal safety; the toolbar owns the top. A screen
            // without a toolbar instead keeps its scroll viewport below the top inset.
            view.setPadding(rootPadding.left + bars.left,
                    rootPadding.top + (header == null ? bars.top : 0),
                    rootPadding.right + bars.right, rootPadding.bottom);
            if (header != null) {
                header.setPadding(headerPadding.left, headerPadding.top + bars.top,
                        headerPadding.right, headerPadding.bottom);
            }
            // IME already includes the navigation area: taking the maximum avoids
            // adding that area twice and allows the last action to scroll above it.
            scroll.setPadding(scrollPadding.left, scrollPadding.top, scrollPadding.right,
                    scrollPadding.bottom + Math.max(bars.bottom, keyboard.bottom));
            revealFocusWhenKeyboardGrows(scroll, keyboard.bottom, keyboardState);
            // All regions are handled at this one root, so children must not add them again.
            return WindowInsetsCompat.CONSUMED;
        });
        ViewCompat.requestApplyInsets(root);
    }

    private static void revealFocusWhenKeyboardGrows(View view, int keyboardBottom,
            KeyboardState state) {
        int previousBottom = state.bottom;
        if (keyboardBottom == previousBottom) return;
        state.bottom = keyboardBottom;
        int generation = ++state.generation;
        if (keyboardBottom < previousBottom || !(view instanceof ScrollView)) return;
        ScrollView scroll = (ScrollView) view;
        View focused = scroll.findFocus();
        if (focused == null || focused == scroll) return;
        // Padding alone need not resize ScrollView or cause its existing focus to be
        // revealed. Run after layout, when both the viewport and focus bounds are valid.
        // The listener removes itself on detach, and stale keyboard/focus states are ignored.
        OneShotPreDrawListener.add(scroll, () -> {
            if (state.generation != generation || state.bottom != keyboardBottom
                    || !scroll.isAttachedToWindow() || !focused.isAttachedToWindow()
                    || scroll.findFocus() != focused) return;
            int visibleTop = scroll.getScrollY() + scroll.getPaddingTop();
            int visibleBottom = scroll.getScrollY() + scroll.getHeight() - scroll.getPaddingBottom();
            if (visibleBottom <= visibleTop) return;
            Rect bounds = new Rect();
            focused.getDrawingRect(bounds);
            scroll.offsetDescendantRectToMyCoords(focused, bounds);
            int delta = 0;
            if (bounds.height() > visibleBottom - visibleTop) {
                delta = bounds.top - visibleTop;
            } else if (bounds.bottom > visibleBottom) {
                delta = bounds.bottom - visibleBottom;
            } else if (bounds.top < visibleTop) {
                delta = bounds.top - visibleTop;
            }
            if (delta != 0) scroll.scrollBy(0, delta);
        });
    }

    private static final class KeyboardState {
        int bottom;
        int generation;
    }

    private static final class Padding {
        final int left;
        final int top;
        final int right;
        final int bottom;

        Padding(View view) {
            left = view.getPaddingLeft();
            top = view.getPaddingTop();
            right = view.getPaddingRight();
            bottom = view.getPaddingBottom();
        }
    }
}
