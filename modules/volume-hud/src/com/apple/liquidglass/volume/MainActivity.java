package com.apple.liquidglass.volume;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * iOS 27 Liquid Glass Control Center & Live Interactive Preview Activity.
 * Allows configuring the rootless Apple Liquid Glass vertical volume bar:
 * - 1-click Accessibility Service activation (no root required)
 * - Live interactive vertical Liquid Glass HUD preview (touch drag, D-Pad, or volume keys)
 * - iOS 27 Material presets: Regular Dark, Regular Light, Ultra Clear
 * - Edge position: Right (tvOS/iPad) or Left (iPhone)
 */
public class MainActivity extends Activity {

    private LiquidGlassVolumeView previewHudView;
    private TextView statusBadgeView;
    private TextView volumePercentLabel;
    private AudioManager audioManager;
    private LiquidGlassOverlayManager overlayManager;

    private Button btnStyleDark;
    private Button btnStyleLight;
    private Button btnStyleClear;
    private Button btnSideLeft;
    private Button btnSideRight;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window window = getWindow();
        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(0xFF070B14);
            window.setNavigationBarColor(0xFF070B14);
        }

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        overlayManager = LiquidGlassOverlayManager.getInstance(this);

        setContentView(buildContentView());
        syncFromSystemVolume();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateServiceStatusUI();
        syncFromSystemVolume();
    }

    private View buildContentView() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);

        // Deep Apple midnight backdrop gradient to highlight Liquid Glass specular refraction
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF070B14, 0xFF101B33, 0xFF06080E}
        );
        scrollView.setBackground(bg);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padH = dp(20);
        int padV = dp(24);
        root.setPadding(padH, padV, padH, padV);

        // 1. Header (Apple HIG Typography: Large Title 30sp Bold + Subheadline)
        TextView eyebrow = new TextView(this);
        eyebrow.setText("APPLE DESIGN SYSTEM • iOS 27 LIQUID GLASS");
        eyebrow.setTextColor(0xFF64D2FF);
        eyebrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        eyebrow.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        eyebrow.setLetterSpacing(0.08f);
        root.addView(eyebrow);

        TextView title = new TextView(this);
        title.setText("Liquid Glass Volume");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        titleLp.topMargin = dp(4);
        root.addView(title, titleLp);

        TextView subtitle = new TextView(this);
        subtitle.setText("Barra de volume vertical em vidro líquido com física de molas (SwiftUI → Android Nativo, Sem Root).");
        subtitle.setTextColor(0xB3EBEBF5);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        subLp.topMargin = dp(6);
        subLp.bottomMargin = dp(20);
        root.addView(subtitle, subLp);

        // 2. Service Activation Card (iOS 27 Radius 34 Glass Platter)
        LinearLayout statusCard = createGlassCard();
        LinearLayout statusHeader = new LinearLayout(this);
        statusHeader.setOrientation(LinearLayout.HORIZONTAL);
        statusHeader.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout statusTextCol = new LinearLayout(this);
        statusTextCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);

        TextView statusTitle = new TextView(this);
        statusTitle.setText("Interceptador Sem Root");
        statusTitle.setTextColor(Color.WHITE);
        statusTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        statusTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        statusTextCol.addView(statusTitle);

        statusBadgeView = new TextView(this);
        statusBadgeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        badgeLp.topMargin = dp(4);
        statusTextCol.addView(statusBadgeView, badgeLp);

        statusHeader.addView(statusTextCol, colLp);
        statusCard.addView(statusHeader);

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        actionRowLp.topMargin = dp(14);

        Button btnEnableA11y = createPillButton("Ativar em Acessibilidade", true);
        btnEnableA11y.setOnClickListener(v -> openAccessibilitySettings());
        LinearLayout.LayoutParams btn1Lp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        btn1Lp.rightMargin = dp(8);
        actionRow.addView(btnEnableA11y, btn1Lp);

        Button btnOverlayPerm = createPillButton("Permissão Overlay", false);
        btnOverlayPerm.setOnClickListener(v -> openOverlaySettings());
        LinearLayout.LayoutParams btn2Lp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        actionRow.addView(btnOverlayPerm, btn2Lp);

        statusCard.addView(actionRow, actionRowLp);
        root.addView(statusCard, createCardMarginParams());

        // 3. Interactive Live Preview & Controls Card
        LinearLayout previewCard = createGlassCard();

        TextView previewTitle = new TextView(this);
        previewTitle.setText("Preview Interativo • Liquid Glass HUD");
        previewTitle.setTextColor(Color.WHITE);
        previewTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        previewTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        previewCard.addView(previewTitle);

        volumePercentLabel = new TextView(this);
        volumePercentLabel.setText("Volume: 65%");
        volumePercentLabel.setTextColor(0xFF64D2FF);
        volumePercentLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams vpLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        vpLp.topMargin = dp(2);
        vpLp.bottomMargin = dp(14);
        previewCard.addView(volumePercentLabel, vpLp);

        // Horizontal showcase: Left = Test Buttons, Right = Live Vertical LiquidGlassVolumeView
        LinearLayout showcaseRow = new LinearLayout(this);
        showcaseRow.setOrientation(LinearLayout.HORIZONTAL);
        showcaseRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout controlsCol = new LinearLayout(this);
        controlsCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams controlsColLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        );
        controlsColLp.rightMargin = dp(16);

        Button btnVolUp = createPillButton("Volume +  (Subir)", true);
        btnVolUp.setOnClickListener(v -> {
            overlayManager.stepVolume(1);
            syncFromSystemVolume();
        });
        controlsCol.addView(btnVolUp, createButtonVertParams());

        Button btnVolDown = createPillButton("Volume −  (Descer)", false);
        btnVolDown.setOnClickListener(v -> {
            overlayManager.stepVolume(-1);
            syncFromSystemVolume();
        });
        controlsCol.addView(btnVolDown, createButtonVertParams());

        Button btnMute = createPillButton("Silenciar / Som (SF Symbol)", false);
        btnMute.setOnClickListener(v -> {
            overlayManager.toggleMute();
            syncFromSystemVolume();
        });
        controlsCol.addView(btnMute, createButtonVertParams());

        Button btnMorph = createPillButton("Morph: Expandido (52pt) ↔ Slim (16pt)", false);
        btnMorph.setOnClickListener(v -> {
            if (previewHudView != null) {
                previewHudView.setExpanded(!previewHudView.isExpanded(), true);
            }
        });
        controlsCol.addView(btnMorph, createButtonVertParams());

        showcaseRow.addView(controlsCol, controlsColLp);

        // Embedded Live LiquidGlassVolumeView
        previewHudView = new LiquidGlassVolumeView(this);
        previewHudView.setGlassStyle(overlayManager.getSavedGlassStyle());
        previewHudView.setAlignLeft(false);
        previewHudView.setOnVolumeScrubListener(new LiquidGlassVolumeView.OnVolumeScrubListener() {
            @Override
            public void onVolumeScrubbed(float normalizedLevel) {
                int max = Math.max(1, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
                int newVol = Math.round(normalizedLevel * max);
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0);
                updatePercentLabel(normalizedLevel, newVol == 0);
            }

            @Override
            public void onInteractionActive() {
            }
        });

        showcaseRow.addView(previewHudView);
        previewCard.addView(showcaseRow);
        root.addView(previewCard, createCardMarginParams());

        // 4. iOS 27 Glass Material & Position Customization Card
        LinearLayout styleCard = createGlassCard();

        TextView styleTitle = new TextView(this);
        styleTitle.setText("Material Liquid Glass (iOS 27 Kit 27.0.2)");
        styleTitle.setTextColor(Color.WHITE);
        styleTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        styleTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        styleCard.addView(styleTitle);

        LinearLayout styleRow = new LinearLayout(this);
        styleRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams styleRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        styleRowLp.topMargin = dp(12);
        styleRowLp.bottomMargin = dp(16);

        btnStyleDark = createPillButton("Regular Dark", false);
        btnStyleLight = createPillButton("Regular Light", false);
        btnStyleClear = createPillButton("Ultra Clear", false);

        btnStyleDark.setOnClickListener(v -> applyGlassStyle(LiquidGlassVolumeView.STYLE_REGULAR_DARK));
        btnStyleLight.setOnClickListener(v -> applyGlassStyle(LiquidGlassVolumeView.STYLE_REGULAR_LIGHT));
        btnStyleClear.setOnClickListener(v -> applyGlassStyle(LiquidGlassVolumeView.STYLE_ULTRA_CLEAR));

        LinearLayout.LayoutParams segLp1 = new LinearLayout.LayoutParams(0, dp(42), 1f);
        segLp1.rightMargin = dp(6);
        LinearLayout.LayoutParams segLp2 = new LinearLayout.LayoutParams(0, dp(42), 1f);
        segLp2.rightMargin = dp(6);
        LinearLayout.LayoutParams segLp3 = new LinearLayout.LayoutParams(0, dp(42), 1f);

        styleRow.addView(btnStyleDark, segLp1);
        styleRow.addView(btnStyleLight, segLp2);
        styleRow.addView(btnStyleClear, segLp3);
        styleCard.addView(styleRow, styleRowLp);

        TextView sideTitle = new TextView(this);
        sideTitle.setText("Posição na Tela");
        sideTitle.setTextColor(Color.WHITE);
        sideTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        sideTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        styleCard.addView(sideTitle);

        LinearLayout sideRow = new LinearLayout(this);
        sideRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams sideRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        sideRowLp.topMargin = dp(10);

        btnSideLeft = createPillButton("Esquerda (iPhone)", false);
        btnSideRight = createPillButton("Direita (tvOS / iPad)", false);

        btnSideLeft.setOnClickListener(v -> applySideAlignment(true));
        btnSideRight.setOnClickListener(v -> applySideAlignment(false));

        LinearLayout.LayoutParams sideLp1 = new LinearLayout.LayoutParams(0, dp(42), 1f);
        sideLp1.rightMargin = dp(8);
        LinearLayout.LayoutParams sideLp2 = new LinearLayout.LayoutParams(0, dp(42), 1f);

        sideRow.addView(btnSideLeft, sideLp1);
        sideRow.addView(btnSideRight, sideLp2);
        styleCard.addView(sideRow, sideRowLp);

        root.addView(styleCard, createCardMarginParams());

        refreshSegmentedButtons();
        updateServiceStatusUI();

        scrollView.addView(root);
        return scrollView;
    }

    private void applyGlassStyle(int style) {
        overlayManager.setSavedGlassStyle(style);
        if (previewHudView != null) {
            previewHudView.setGlassStyle(style);
        }
        refreshSegmentedButtons();
        overlayManager.showCurrentVolumePreview(true);
    }

    private void applySideAlignment(boolean alignLeft) {
        overlayManager.setSavedAlignLeft(alignLeft);
        refreshSegmentedButtons();
        overlayManager.showCurrentVolumePreview(true);
    }

    private void refreshSegmentedButtons() {
        int style = overlayManager.getSavedGlassStyle();
        styleSegmentButton(btnStyleDark, style == LiquidGlassVolumeView.STYLE_REGULAR_DARK);
        styleSegmentButton(btnStyleLight, style == LiquidGlassVolumeView.STYLE_REGULAR_LIGHT);
        styleSegmentButton(btnStyleClear, style == LiquidGlassVolumeView.STYLE_ULTRA_CLEAR);

        boolean left = overlayManager.isSavedAlignLeft();
        styleSegmentButton(btnSideLeft, left);
        styleSegmentButton(btnSideRight, !left);
    }

    private void styleSegmentButton(Button btn, boolean active) {
        if (btn == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(999));
        if (active) {
            bg.setColor(0xFF0A84FF); // Apple systemBlue
            bg.setStroke(dp(1), 0x88FFFFFF);
            btn.setTextColor(Color.WHITE);
        } else {
            bg.setColor(0x26FFFFFF);
            bg.setStroke(dp(1), 0x33FFFFFF);
            btn.setTextColor(0xCCFFFFFF);
        }
        btn.setBackground(bg);
    }

    private void syncFromSystemVolume() {
        int max = Math.max(1, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        int current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        float norm = current / (float) max;
        boolean muted = current == 0 || audioManager.isStreamMute(AudioManager.STREAM_MUSIC);

        if (previewHudView != null) {
            previewHudView.setMuted(muted);
            previewHudView.setVolumeLevel(norm, true);
        }
        updatePercentLabel(norm, muted);
    }

    private void updatePercentLabel(float norm, boolean muted) {
        if (volumePercentLabel == null) return;
        int pct = Math.round(norm * 100f);
        String symbolName;
        if (muted || pct == 0) {
            symbolName = "speaker.slash.fill (Silenciado)";
        } else if (pct < 33) {
            symbolName = "speaker.wave.1.fill";
        } else if (pct < 66) {
            symbolName = "speaker.wave.2.fill";
        } else {
            symbolName = "speaker.wave.3.fill";
        }
        volumePercentLabel.setText("Volume Atual: " + pct + "%  •  SF Symbol: " + symbolName);
    }

    private void updateServiceStatusUI() {
        if (statusBadgeView == null) return;
        boolean enabled = isAccessibilityServiceEnabled();
        if (enabled) {
            statusBadgeView.setText("● Ativo — Botões físicos de volume substituídos pelo Apple Liquid Glass");
            statusBadgeView.setTextColor(0xFF30D158); // Apple systemGreen
        } else {
            statusBadgeView.setText("○ Toque em 'Ativar em Acessibilidade' para interceptar os botões de volume em todo o sistema");
            statusBadgeView.setTextColor(0xFFFF9F0A); // Apple systemOrange
        }
    }

    private boolean isAccessibilityServiceEnabled() {
        if (LiquidGlassVolumeService.isServiceRunning()) return true;
        String enabledServices = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        );
        return enabledServices != null && enabledServices.contains(getPackageName());
    }

    private void openAccessibilitySettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Throwable ignored) {
        }
    }

    private void openOverlaySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                Intent intent = new Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())
                );
                startActivity(intent);
                return;
            } catch (Throwable ignored) {
            }
        }
        openAccessibilitySettings();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        int action = event.getAction();
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (action == KeyEvent.ACTION_DOWN) {
                int max = Math.max(1, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
                int current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                if (current >= max && previewHudView != null) {
                    previewHudView.triggerOvershoot(1.0f);
                }
                overlayManager.stepVolume(1);
                syncFromSystemVolume();
            }
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (action == KeyEvent.ACTION_DOWN) {
                int current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                if (current <= 0 && previewHudView != null) {
                    previewHudView.triggerOvershoot(-1.0f);
                }
                overlayManager.stepVolume(-1);
                syncFromSystemVolume();
            }
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
            if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                overlayManager.toggleMute();
                syncFromSystemVolume();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private LinearLayout createGlassCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        card.setPadding(pad, pad, pad, pad);

        // iOS 27 Kit 27.0.2 Regular Dark Glass Card (radius 34, fill #1A1A1A @ 70%, rim #A6A6A6)
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(28));
        bg.setColor(0xB31A1D26);
        bg.setStroke(dp(1), 0x40FFFFFF);
        card.setBackground(bg);
        return card;
    }

    private Button createPillButton(String text, boolean prominent) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        btn.setTextColor(Color.WHITE);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(999));
        if (prominent) {
            bg.setColor(0xFF0A84FF); // iOS 27 .glassProminent systemBlue
            bg.setStroke(dp(1), 0x99FFFFFF);
        } else {
            bg.setColor(0x28FFFFFF); // iOS 27 .glass regular pill
            bg.setStroke(dp(1), 0x40FFFFFF);
        }
        btn.setBackground(bg);
        return btn;
    }

    private LinearLayout.LayoutParams createCardMarginParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.bottomMargin = dp(16);
        return lp;
    }

    private LinearLayout.LayoutParams createButtonVertParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(44)
        );
        lp.bottomMargin = dp(10);
        return lp;
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                getResources().getDisplayMetrics()
        ));
    }
}
