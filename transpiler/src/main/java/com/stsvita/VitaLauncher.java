package com.stsvita;

import com.badlogic.gdx.ApplicationListener;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.stsvita.backend.VitaApplication;
import com.stsvita.backend.VitaFiles;

/**
 * Vita replacement for com.megacrit.cardcrawl.desktop.DesktopLauncher.
 *
 * Compiled against the user's own jar (compileOnly); becomes the TeaVM main
 * class. The desktop launcher's other duties don't exist here: no native-lib
 * extraction, no log4j bootstrap (LogManager is stubbed), no display-mode
 * negotiation — the Vita is one known machine at 960x544, which the game
 * scales to by itself (Settings.scale = HEIGHT/1080, the same path desktop
 * uses for 1280x720 displays). DisplayConfig still reads/writes
 * info.displayconfig relative to cwd, which main.c chdir()s to DATA.
 */
public final class VitaLauncher {

    public static void main(String[] args) {
        // Build-time-generated gson registrations (TypeTokens, factories,
        // generic field elements) — must precede any game code.
        com.stsvita.gen.TypeManifest.install();

        formatSelfTest();

        // Auto-open the on-screen keyboard whenever the game focuses a text
        // field (it installs a TypeHelper input processor for the save-name
        // popup). TypeHelper.keyTyped can only append letters/digits (no
        // backspace), so the result is written straight into the popup's
        // public text field instead of being typed.
        com.stsvita.backend.VitaInput.textFieldListener = new com.stsvita.backend.VitaInput.TextFieldListener() {
            @Override
            public void opened() {
                String current = com.megacrit.cardcrawl.ui.panels.RenamePopup.textField;
                com.badlogic.gdx.Gdx.input.getTextInput(new com.badlogic.gdx.Input.TextInputListener() {
                    @Override
                    public void input(String text) {
                        StringBuilder clean = new StringBuilder();
                        for (int i = 0; i < text.length() && clean.length() < 16; i++) {
                            char c = text.charAt(i);
                            if (Character.isLetter(c) || Character.isDigit(c)) {
                                clean.append(c);
                            }
                        }
                        if (clean.length() > 0) {
                            com.megacrit.cardcrawl.ui.panels.RenamePopup.textField = clean.toString();
                        }
                    }

                    @Override
                    public void canceled() {
                    }
                }, "Name", current == null ? "" : current, "");
            }
        };

        VitaApplication.Config cfg = new VitaApplication.Config();
        cfg.width = 960;
        cfg.height = 544;

        // Mirrors DesktopLauncher: new LwjglApplication(new CardCrawlGame(
        // config.preferencesDirectory), config) — with the Vita equivalents.
        final ApplicationListener game =
                (ApplicationListener) new CardCrawlGame(VitaFiles.DATA + ".prefs/");
        // Diagnostic wrapper ([DIAG font]): first frame after
        // FontHelper exists, exercise dungeonTitleFont exactly like the act
        // transition does. On device the title renders as just "i" — "i" is
        // the only glyph "The City" shares with "Exordium", so the incremental
        // FreeType path fails for glyphs that need a NEW 256px packer page
        // mid-run (maxTextureSize is patched 1024->256 on Vita).
        ApplicationListener probed = new ApplicationListener() {
            private boolean done;
            @Override public void create() { game.create(); }
            @Override public void resize(int w, int h) { game.resize(w, h); }
            @Override public void pause() { game.pause(); }
            @Override public void resume() { game.resume(); }
            @Override public void dispose() { game.dispose(); }
            @Override public void render() {
                game.render();
                if (!done && com.megacrit.cardcrawl.helpers.FontHelper.dungeonTitleFont != null) {
                    done = true;
                    fontTitleProbe();
                }
            }
        };
        new VitaApplication(probed, cfg);
    }

    /** One-shot incremental-title-font probe; output tagged [DIAG font]. */
    private static void fontTitleProbe() {
        try {
            // Device 2026-07-17: glyph data was proven healthy but the title
            // still draws as "i". Two suspects left: the localized string
            // itself, or GPU-side glyph sub-uploads. Dump the exact strings
            // DungeonTransitionScreen will draw (TEXT[3/5/7/9] = act names).
            String[] txt = com.megacrit.cardcrawl.screens.DungeonTransitionScreen.TEXT;
            for (int i = 0; i < txt.length; i++) {
                System.out.println("[DIAG font] TEXT[" + i + "]='" + txt[i] + "'");
            }
            com.badlogic.gdx.graphics.g2d.BitmapFont f =
                    com.megacrit.cardcrawl.helpers.FontHelper.dungeonTitleFont;
            probeText(f, "Exordium");
            probeText(f, "The City");
            probeText(f, "The Beyond");
            com.badlogic.gdx.utils.Array<com.badlogic.gdx.graphics.g2d.TextureRegion> regs =
                    f.getRegions();
            for (int i = 0; i < regs.size; i++) {
                com.badlogic.gdx.graphics.Texture t = regs.get(i).getTexture();
                System.out.println("[DIAG font] region " + i + " tex="
                        + (t == null ? "null"
                           : t.getWidth() + "x" + t.getHeight()
                             + " handle=" + t.getTextureObjectHandle()));
            }
        } catch (Throwable t) {
            System.out.println("[DIAG font] probe threw: " + t);
        }
    }

    private static void probeText(com.badlogic.gdx.graphics.g2d.BitmapFont f, String text) {
        com.badlogic.gdx.graphics.g2d.GlyphLayout gl =
                new com.badlogic.gdx.graphics.g2d.GlyphLayout(f, text);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ') continue;
            com.badlogic.gdx.graphics.g2d.BitmapFont.Glyph g = f.getData().getGlyph(c);
            sb.append(c).append(g == null ? "=NULL " : "=p" + g.page
                    + ":" + g.width + "x" + g.height + " ");
        }
        System.out.println("[DIAG font] '" + text + "' layout.w=" + gl.width
                + " glyphs: " + sb);
    }

    /**
     * Number-formatting probe for the on-device digits-become-zero bug
     *"). StS's volume label is new DecimalFormat("#").format(double);
     * the same values format correctly in 64-bit and 32-bit x86 host builds,
     * so this prints the whole chain on hardware: DecimalFormat, the
     * Double.toString path, and DoubleAnalyzer's raw mantissa/exponent
     * (TDecimalFormat's digit source). Diagnostic — strip for release (M5).
     */
    private static void formatSelfTest() {
        java.text.DecimalFormat df = new java.text.DecimalFormat("#");
        java.text.DecimalFormat d1 = new java.text.DecimalFormat("#.#");
        System.out.println("[DIAG fmt] df#(76.56069)=" + df.format(76.56069) + " want 77");
        System.out.println("[DIAG fmt] df#(36.30891)=" + df.format(36.30891) + " want 36");
        System.out.println("[DIAG fmt] df#.#(12.5)=" + d1.format(12.5) + " want 12.5");
        System.out.println("[DIAG fmt] df#.#(5.36)=" + d1.format(5.36) + " want 5.4");
        System.out.println("[DIAG fmt] df#(30L)=" + df.format(30L) + " want 30");
        System.out.println("[DIAG fmt] D.toString(12.5)=" + Double.toString(12.5));
        System.out.println("[DIAG fmt] F.toString(0.7656069f)=" + Float.toString(0.7656069f));
        System.out.println("[DIAG fmt] I.toString(76)=" + Integer.toString(76));
        org.teavm.classlib.impl.text.DoubleAnalyzer.Result r =
                new org.teavm.classlib.impl.text.DoubleAnalyzer.Result();
        org.teavm.classlib.impl.text.DoubleAnalyzer.analyze(12.5, r);
        System.out.println("[DIAG fmt] analyzer(12.5) mantissa=" + r.mantissa
                + " exp=" + r.exponent + " sign=" + r.sign);
        org.teavm.classlib.impl.text.DoubleAnalyzer.analyze(76.56069, r);
        System.out.println("[DIAG fmt] analyzer(76.56069) mantissa=" + r.mantissa
                + " exp=" + r.exponent + " sign=" + r.sign);

        // Regression check for the array-data-offset GC bug (patched in
        // vita_patch_runtime.py): force full compacting collections, then
        // format again. Pre-patch, the analyzer's long[] tables read 4 bytes
        // shifted after a move and every value below printed zeros/garbage.
        Object sink;
        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < 20000; i++) {
                sink = new byte[1024];
                sink = "gc-churn-" + i;
            }
            System.gc();
        }
        System.out.println("[DIAG fmt] post-gc df#(76.56069)="
                + df.format(76.56069) + " want 77");
        System.out.println("[DIAG fmt] post-gc fmt%.0f(5.36)="
                + String.format("%.0f", 5.36f) + " want 5");
        System.out.println("[DIAG fmt] post-gc D.toString(12.5)="
                + Double.toString(12.5) + " want 12.5");
    }

    private VitaLauncher() {}
}
