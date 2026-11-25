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
 * negotiation - the Vita is one known machine at 960x544, which the game
 * scales to by itself (Settings.scale = HEIGHT/1080, the same path desktop
 * uses for 1280x720 displays). DisplayConfig still reads/writes
 * info.displayconfig relative to cwd, which main.c chdir()s to DATA.
 */
public final class VitaLauncher {

    public static void main(String[] args) {
        // Build-time-generated gson registrations (TypeTokens, factories,
        // generic field elements) - must precede any game code.
        com.stsvita.gen.TypeManifest.install();

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
        // config.preferencesDirectory), config) - with the Vita equivalents.
        final ApplicationListener game =
                (ApplicationListener) new CardCrawlGame(VitaFiles.DATA + ".prefs/");
        new VitaApplication(game, cfg);
    }

    private VitaLauncher() {}
}
