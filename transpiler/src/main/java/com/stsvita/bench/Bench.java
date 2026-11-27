package com.stsvita.bench;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.math.MathUtils;
import com.megacrit.cardcrawl.actions.common.EmptyDeckShuffleAction;
import com.megacrit.cardcrawl.actions.utility.NewQueueCardAction;
import com.megacrit.cardcrawl.actions.utility.UnlimboAction;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.helpers.CardLibrary;
import com.megacrit.cardcrawl.helpers.ModHelper;
import com.megacrit.cardcrawl.helpers.PotionHelper;
import com.megacrit.cardcrawl.helpers.TipTracker;
import com.megacrit.cardcrawl.map.MapRoomNode;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.potions.AbstractPotion;
import com.megacrit.cardcrawl.relics.AbstractRelic;
import com.megacrit.cardcrawl.rooms.AbstractRoom;
import com.megacrit.cardcrawl.rooms.MonsterRoom;
import com.megacrit.cardcrawl.ui.panels.EnergyPanel;
import com.megacrit.cardcrawl.vfx.AbstractGameEffect;
import com.megacrit.cardcrawl.vfx.BorderLongFlashEffect;
import com.megacrit.cardcrawl.vfx.CardTrailEffect;
import com.megacrit.cardcrawl.vfx.CollectorCurseEffect;
import com.megacrit.cardcrawl.vfx.cardManip.ShowCardAndAddToDiscardEffect;
import com.megacrit.cardcrawl.vfx.cardManip.ShowCardAndAddToDrawPileEffect;
import com.megacrit.cardcrawl.vfx.cardManip.ShowCardAndObtainEffect;
import com.megacrit.cardcrawl.vfx.combat.GhostIgniteEffect;
import com.megacrit.cardcrawl.vfx.combat.GiantFireEffect;
import com.megacrit.cardcrawl.vfx.combat.HeartBuffEffect;
import com.megacrit.cardcrawl.vfx.combat.HeartMegaDebuffEffect;
import com.megacrit.cardcrawl.vfx.combat.IntimidateEffect;
import com.megacrit.cardcrawl.vfx.combat.LaserBeamEffect;
import com.megacrit.cardcrawl.vfx.combat.MindblastEffect;
import com.megacrit.cardcrawl.vfx.combat.ScreenOnFireEffect;
import com.megacrit.cardcrawl.vfx.combat.ShockWaveEffect;
import com.megacrit.cardcrawl.vfx.combat.ViceCrushEffect;
import com.megacrit.cardcrawl.vfx.combat.WeightyImpactEffect;
import com.stsvita.backend.BenchGL20;
import com.stsvita.backend.VitaApplication;
import com.stsvita.backend.VitaGraphics;
import com.stsvita.backend.VitaNatives;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class Bench implements VitaApplication.FrameHook {

    private static final long SEED = 4242L;
    private static final int MAX_FRAMES = 4096;
    private static final int SETTLE_LIMIT = 600;

    private enum Stage { BOOT, RUN, COMBAT, SCENARIO, SETTLE, DONE }

    private abstract static class Scenario {
        final String name;
        final int frames;

        Scenario(String name, int frames) {
            this.name = name;
            this.frames = frames;
        }

        void begin() {
        }

        abstract void frame(int f);

        int stallUs(int f) {
            return 0;
        }
    }

    private static final class Flight {
        final AbstractCard card;
        final AbstractGameEffect show;
        float lastX;
        float lastY;
        float startX;
        float startY;
        boolean moving;
        int still;
        int movingFrames;
        boolean done;

        Flight(AbstractCard card, AbstractGameEffect show) {
            this.card = card;
            this.show = show;
            lastX = card.current_x;
            lastY = card.current_y;
        }
    }

    private final ArrayList<Scenario> scenarios = new ArrayList<>();
    private final ArrayList<Flight> flights = new ArrayList<>();
    private final int[] frameUs = new int[MAX_FRAMES];
    private final int[] gameUs = new int[MAX_FRAMES];
    private final int[] swapUs = new int[MAX_FRAMES];
    private final long[] glStart = new long[BenchGL20.COUNT];
    private Stage stage = Stage.BOOT;
    private int stageFrames;
    private int index = -1;
    private int frame;
    private int n;
    private long upUs;
    private long upCalls;
    private long stbUs;
    private long ioUs;
    private int fxMax;
    private long scenDrawUs;
    private long gcStart;
    private long gcFullStart;
    private long gcUsStart;
    private long allocStart;
    private long benchStart;
    private int slowLogged;
    private int lastPlay;
    private int queued;
    private boolean capture;
    private boolean dt30;

    public static void install() {
        Bench b = new Bench();
        b.capture = new File("bench_capture").exists();
        if (b.capture) {
            VitaGraphics.benchFixedDelta = 1f / 60f;
        }
        VitaGraphics.benchNoSubstep = new File("bench_nosubstep").exists();
        b.dt30 = new File("bench_dt30").exists();
        if (b.dt30) {
            VitaGraphics.benchFixedDelta = 1f / 30f;
        }
        b.buildScenarios();
        File only = new File("bench_only");
        if (only.exists()) {
            String name = "";
            try {
                BufferedReader r = new BufferedReader(new FileReader(only));
                name = r.readLine().trim();
                r.close();
            } catch (IOException e) {
                name = "";
            }
            List<Scenario> keep = new ArrayList<>();
            for (Scenario sc : b.scenarios) {
                if (sc.name.equals(name)) {
                    for (int i = 0; i < 8; i++) {
                        keep.add(sc);
                    }
                }
            }
            if (!keep.isEmpty()) {
                b.scenarios.clear();
                b.scenarios.addAll(keep);
            }
        }
        VitaApplication.frameHook = b;
        VitaNatives.log("[bench] installed seed=" + SEED + " scenarios=" + b.scenarios.size()
                + (b.capture ? " capture" : "") + (b.dt30 ? " dt30" : "")
                + (VitaGraphics.benchNoSubstep ? " nosubstep" : ""));
    }

    private static float w() {
        return Settings.WIDTH;
    }

    private static float h() {
        return Settings.HEIGHT;
    }

    private static AbstractCard card(String id) {
        AbstractCard c = CardLibrary.getCard(id);
        if (c == null) {
            VitaNatives.log("[bench] missing card " + id);
            return null;
        }
        return c.makeCopy();
    }

    private static AbstractMonster target() {
        for (AbstractMonster m : AbstractDungeon.getMonsters().monsters) {
            if (!m.isDeadOrEscaped()) {
                return m;
            }
        }
        return null;
    }

    private static boolean idle() {
        return AbstractDungeon.actionManager.actions.isEmpty()
                && AbstractDungeon.actionManager.cardQueue.isEmpty()
                && AbstractDungeon.actionManager.currentAction == null;
    }

    private static void play(String id) {
        AbstractCard c = card(id);
        if (c == null) {
            return;
        }
        AbstractPlayer p = AbstractDungeon.player;
        EnergyPanel.setEnergy(3);
        p.limbo.group.add(c);
        c.current_x = w() / 2f;
        c.current_y = -200f * Settings.scale;
        c.target_x = w() / 2f + 200f * Settings.scale;
        c.target_y = h() / 2f;
        c.targetAngle = 0f;
        c.lighten(false);
        c.drawScale = 0.12f;
        c.targetDrawScale = 0.75f;
        c.applyPowers();
        AbstractDungeon.actionManager.addToTop(new NewQueueCardAction(c, target(), false, true));
        AbstractDungeon.actionManager.addToTop(new UnlimboAction(c));
    }

    private static void godMode() {
        AbstractPlayer p = AbstractDungeon.player;
        p.maxHealth = 9999;
        p.currentHealth = 9999;
        for (AbstractMonster m : AbstractDungeon.getMonsters().monsters) {
            m.maxHealth = 999999;
            m.currentHealth = 999999;
            m.healthBarUpdatedEvent();
        }
    }

    private static void effect(AbstractGameEffect e) {
        AbstractDungeon.effectList.add(e);
    }

    private class CardSequence extends Scenario {
        private final String[] ids;

        CardSequence(String name, String... ids) {
            super(name, 2400);
            this.ids = ids;
        }

        @Override
        void begin() {
            queued = 0;
            lastPlay = -1000;
        }

        @Override
        void frame(int f) {
            if (queued >= ids.length) {
                if (idle() && f - lastPlay > 90) {
                    finishScenario();
                }
                return;
            }
            if (idle() && f - lastPlay >= 20) {
                play(ids[queued++]);
                lastPlay = f;
            }
        }
    }

    private final class Obtain extends Scenario {
        private final int count;
        private final int stall;

        Obtain(String name, int count, int stall) {
            super(name, count * 110 + 150);
            this.count = count;
            this.stall = stall;
        }

        @Override
        void frame(int f) {
            if (f % 110 == 0 && f / 110 < count) {
                AbstractCard c = card((f / 110) % 2 == 0 ? "Inflame" : "Pummel");
                float x = w() * (0.25f + 0.25f * ((f / 110) % 3));
                AbstractGameEffect e = new ShowCardAndObtainEffect(c, x, h() / 2f);
                flights.add(new Flight(c, e));
                effect(e);
            }
        }

        @Override
        int stallUs(int f) {
            if (stall == 0 || f % 2 != 0) {
                return 0;
            }
            for (Flight fl : flights) {
                if (fl.moving && !fl.done) {
                    return stall;
                }
            }
            return 0;
        }
    }

    private void buildScenarios() {
        scenarios.add(new Scenario("idle", 240) {
            @Override
            void frame(int f) {
            }
        });
        scenarios.add(new Obtain("obtain", 6, 0));
        scenarios.add(new Scenario("to_draw", 6 * 70 + 120) {
            @Override
            void frame(int f) {
                if (f % 70 == 0 && f / 70 < 6) {
                    float x = w() * (0.3f + 0.2f * ((f / 70) % 3));
                    effect(new ShowCardAndAddToDrawPileEffect(card("Wound"), x, h() / 2f, true, true, false));
                }
            }
        });
        scenarios.add(new Scenario("to_discard", 6 * 70 + 120) {
            @Override
            void frame(int f) {
                if (f % 70 == 0 && f / 70 < 6) {
                    float x = w() * (0.3f + 0.2f * ((f / 70) % 3));
                    effect(new ShowCardAndAddToDiscardEffect(card("Burn"), x, h() / 2f));
                }
            }
        });
        scenarios.add(new CardSequence("attacks", "Strike_R", "Bash", "Cleave", "Whirlwind", "Bludgeon",
                "Immolate", "Pummel", "Reaper", "Hemokinesis", "Carnage"));
        scenarios.add(new CardSequence("skills", "Defend_R", "Shrug It Off", "Inflame", "Demon Form",
                "Limit Break", "True Grit", "Second Wind", "Offering", "Fiend Fire"));
        scenarios.add(new Scenario("shuffle", 300) {
            @Override
            void begin() {
                for (int i = 0; i < 10; i++) {
                    AbstractDungeon.player.discardPile.addToTop(card(i % 2 == 0 ? "Strike_R" : "Defend_R"));
                }
                AbstractDungeon.actionManager.addToBottom(new EmptyDeckShuffleAction());
            }

            @Override
            void frame(int f) {
            }
        });
        scenarios.add(new Scenario("potions_relics", 480) {
            private final String[] potions = {"Fire Potion", "Block Potion", "Strength Potion", "Explosive Potion"};

            @Override
            void frame(int f) {
                if (f % 80 == 0 && f / 80 < potions.length) {
                    AbstractPotion p = PotionHelper.getPotion(potions[f / 80]);
                    if (p == null) {
                        VitaNatives.log("[bench] missing potion " + potions[f / 80]);
                    } else {
                        p.use(target());
                    }
                }
                if (f % 120 == 60) {
                    for (AbstractRelic r : AbstractDungeon.player.relics) {
                        r.flash();
                    }
                }
            }
        });
        scenarios.add(new CardSequence("silent", "Blade Dance", "Glass Knife", "Die Die Die",
                "Bouncing Flask", "Deadly Poison", "Catalyst", "Corpse Explosion"));
        scenarios.add(new CardSequence("defect", "Zap", "Cold Snap", "Darkness", "Fusion", "Electrodynamics",
                "Multi-Cast", "Hyperbeam", "Meteor Strike", "Defragment") {
            @Override
            void begin() {
                super.begin();
                AbstractDungeon.player.increaseMaxOrbSlots(3, false);
            }
        });
        scenarios.add(new CardSequence("watcher", "Eruption", "Vigilance", "Worship", "Worship", "Ragnarok",
                "Conclude", "Wallop", "Tantrum", "Judgement"));
        scenarios.add(new Scenario("boss_vfx", 13 * 40 + 120) {
            @Override
            void frame(int f) {
                if (f % 40 != 0) {
                    return;
                }
                AbstractMonster m = target();
                float mx = m != null ? m.hb.cX : w() * 0.7f;
                float my = m != null ? m.hb.cY : h() * 0.5f;
                float px = AbstractDungeon.player.hb.cX;
                float py = AbstractDungeon.player.hb.cY;
                switch (f / 40) {
                    case 0: effect(new GhostIgniteEffect(mx, my)); break;
                    case 1: effect(new ScreenOnFireEffect()); break;
                    case 2: effect(new HeartMegaDebuffEffect()); break;
                    case 3: effect(new HeartBuffEffect(mx, my)); break;
                    case 4: effect(new ViceCrushEffect(px, py)); break;
                    case 5: effect(new WeightyImpactEffect(px, py)); break;
                    case 6: effect(new CollectorCurseEffect(px, py)); break;
                    case 7: effect(new LaserBeamEffect(mx, my)); break;
                    case 8: effect(new IntimidateEffect(mx, my)); break;
                    case 9: effect(new MindblastEffect(mx, my, true)); break;
                    case 10: effect(new GiantFireEffect()); break;
                    case 11: effect(new ShockWaveEffect(mx, my, Color.RED,
                            ShockWaveEffect.ShockWaveType.CHAOTIC)); break;
                    case 12: effect(new BorderLongFlashEffect(Color.PURPLE)); break;
                    default: break;
                }
            }
        });
        scenarios.add(new Obtain("obtain_stall", 16, 50_000));
    }

    @Override
    public void beforeRender() {
        stageFrames++;
        switch (stage) {
            case BOOT:
                if (CardCrawlGame.mode == CardCrawlGame.GameMode.CHAR_SELECT
                        && CardCrawlGame.mainMenuScreen != null && stageFrames > 120) {
                    startRun();
                    next(Stage.RUN);
                }
                break;
            case RUN:
                if (CardCrawlGame.mode == CardCrawlGame.GameMode.GAMEPLAY && AbstractDungeon.player != null
                        && AbstractDungeon.currMapNode != null && AbstractDungeon.getCurrRoom() != null) {
                    if (stageFrames > 240 && !(AbstractDungeon.getCurrRoom() instanceof MonsterRoom)) {
                        enterCombat();
                        next(Stage.COMBAT);
                    }
                } else {
                    stageFrames = 0;
                }
                break;
            case COMBAT:
                AbstractRoom room = AbstractDungeon.getCurrRoom();
                if (!(room instanceof MonsterRoom) || room.phase != AbstractRoom.RoomPhase.COMBAT) {
                    MathUtils.random.setSeed(SEED);
                }
                if (room instanceof MonsterRoom && room.phase == AbstractRoom.RoomPhase.COMBAT && idle()
                        && !AbstractDungeon.actionManager.turnHasEnded) {
                    if (stageFrames > 600) {
                        godMode();
                        benchStart = VitaNatives.micros();
                        nextScenario();
                    }
                } else if (stageFrames > 3600) {
                    VitaNatives.log("[bench] FAIL no combat");
                    finish();
                } else {
                    stageFrames = Math.min(stageFrames, 1);
                }
                break;
            case SCENARIO:
                Scenario s = scenarios.get(index);
                if (capture && frame % 60 == 30 && frame < 400) {
                    VitaNatives.benchCapture(index * 10 + frame / 60);
                }
                BenchGL20.trace = frame == 120 && (s.name.equals("idle") || s.name.equals("attacks"));
                s.frame(frame);
                int stall = s.stallUs(frame);
                if (stall > 0) {
                    long until = VitaNatives.micros() + stall;
                    while (VitaNatives.micros() < until) {
                        stall--;
                    }
                }
                break;
            case SETTLE:
                if ((idle() && AbstractDungeon.effectList.isEmpty() && stageFrames > 30)
                        || stageFrames > SETTLE_LIMIT) {
                    godMode();
                    nextScenario();
                }
                break;
            default:
                break;
        }
    }

    private static String fxCounts() {
        int trails = 0;
        int fx = 0;
        int top = 0;
        if (AbstractDungeon.effectList != null) {
            fx = AbstractDungeon.effectList.size();
            for (AbstractGameEffect e : AbstractDungeon.effectList) {
                if (e instanceof CardTrailEffect) {
                    trails++;
                }
            }
        }
        if (AbstractDungeon.topLevelEffects != null) {
            top = AbstractDungeon.topLevelEffects.size();
            for (AbstractGameEffect e : AbstractDungeon.topLevelEffects) {
                if (e instanceof CardTrailEffect) {
                    trails++;
                }
            }
        }
        return " fx=" + fx + " top=" + top + " trails=" + trails;
    }

    @Override
    public void afterFrame(long fUs, long gUs, long sUs, long up, long stb, long io) {
        if (stage != Stage.SCENARIO) {
            return;
        }
        if (n < MAX_FRAMES) {
            frameUs[n] = (int) fUs;
            gameUs[n] = (int) gUs;
            swapUs[n] = (int) sUs;
            n++;
        }
        upUs += (up / 1000) * 1000;
        upCalls += up % 1000;
        stbUs += (stb / 1000) * 1000;
        ioUs += (io / 1000) * 1000;
        if (fUs > 50_000 && slowLogged < 12) {
            slowLogged++;
            VitaNatives.log("[bench-slow] scen=" + scenarios.get(index).name + " f=" + frame
                    + " ms=" + fUs / 1000 + " game=" + gUs / 1000 + " swap=" + sUs / 1000
                    + " up=" + up / 1000 + "/" + up % 1000 + " stb=" + stb / 1000 + " io=" + io / 1000
                    + " gc=" + (VitaNatives.benchGcStat(0) - gcStart) + " draw=" + BenchGL20.drawUs / 1000
                    + fxCounts());
        }
        int fxNow = (AbstractDungeon.effectList != null ? AbstractDungeon.effectList.size() : 0)
                + (AbstractDungeon.topLevelEffects != null ? AbstractDungeon.topLevelEffects.size() : 0);
        if (fxNow > fxMax) {
            fxMax = fxNow;
        }
        scenDrawUs += BenchGL20.drawUs;
        BenchGL20.drawUs = 0;
        trackFlights();
        frame++;
        if (stage == Stage.SCENARIO && frame >= scenarios.get(index).frames) {
            finishScenario();
        }
    }

    private void trackFlights() {
        for (Flight fl : flights) {
            if (fl.done) {
                continue;
            }
            if (!fl.show.isDone) {
                fl.lastX = fl.card.current_x;
                fl.lastY = fl.card.current_y;
                continue;
            }
            float dx = fl.card.current_x - fl.lastX;
            float dy = fl.card.current_y - fl.lastY;
            boolean moved = dx != 0f || dy != 0f;
            if (moved) {
                if (!fl.moving) {
                    fl.startX = fl.lastX;
                    fl.startY = fl.lastY;
                }
                fl.moving = true;
                fl.still = 0;
                fl.movingFrames++;
                fl.lastX = fl.card.current_x;
                fl.lastY = fl.card.current_y;
            } else if (fl.moving && ++fl.still >= 15) {
                fl.done = true;
            }
        }
    }

    private void next(Stage s) {
        stage = s;
        stageFrames = 0;
    }

    private void startRun() {
        VitaNatives.log("[bench] start run");
        MathUtils.random.setSeed(SEED);
        TipTracker.disableAllFtues();
        Settings.seed = SEED;
        Settings.seedSet = true;
        Settings.isDailyRun = false;
        Settings.isTrial = false;
        Settings.specialSeed = null;
        ModHelper.setModsFalse();
        AbstractDungeon.generateSeeds();
        AbstractDungeon.isAscensionMode = false;
        AbstractDungeon.ascensionLevel = 0;
        CardCrawlGame.chosenCharacter = AbstractPlayer.PlayerClass.IRONCLAD;
        CardCrawlGame.mainMenuScreen.isFadingOut = true;
        CardCrawlGame.mainMenuScreen.fadeOutMusic();
    }

    private void enterCombat() {
        MapRoomNode node = null;
        for (MapRoomNode m : AbstractDungeon.map.get(0)) {
            if (m.hasEdges()) {
                node = m;
                break;
            }
        }
        if (node == null) {
            node = new MapRoomNode(0, 0);
        }
        node.setRoom(new MonsterRoom());
        MathUtils.random.setSeed(SEED);
        VitaNatives.log("[bench] enter combat x=" + node.x);
        AbstractDungeon.nextRoom = node;
        AbstractDungeon.nextRoomTransitionStart();
    }

    private void nextScenario() {
        index++;
        if (index >= scenarios.size()) {
            finish();
            return;
        }
        Scenario s = scenarios.get(index);
        MathUtils.random.setSeed(SEED + index);
        n = 0;
        frame = 0;
        upUs = upCalls = stbUs = ioUs = 0;
        fxMax = 0;
        scenDrawUs = 0;
        slowLogged = 0;
        flights.clear();
        System.arraycopy(BenchGL20.counts, 0, glStart, 0, BenchGL20.COUNT);
        gcStart = VitaNatives.benchGcStat(0);
        gcFullStart = VitaNatives.benchGcStat(1);
        gcUsStart = VitaNatives.benchGcStat(2);
        allocStart = VitaNatives.benchGcStat(3);
        next(Stage.SCENARIO);
        s.begin();
    }

    private void finishScenario() {
        if (stage != Stage.SCENARIO) {
            return;
        }
        report(scenarios.get(index));
        next(Stage.SETTLE);
    }

    private static String ms(int[] sorted, int count, double q) {
        if (count == 0) {
            return "0";
        }
        int i = (int) Math.min(count - 1, Math.floor(q * (count - 1) + 0.5));
        return fmt(sorted[i] / 1000.0);
    }

    private static String fmt(double v) {
        long t = Math.round(v * 10);
        return (t / 10) + "." + Math.abs(t % 10);
    }

    private void report(Scenario s) {
        int count = n;
        int[] f = Arrays.copyOf(frameUs, count);
        int[] g = Arrays.copyOf(gameUs, count);
        int[] sw = Arrays.copyOf(swapUs, count);
        Arrays.sort(f);
        Arrays.sort(g);
        Arrays.sort(sw);
        int over17 = 0;
        int over34 = 0;
        long total = 0;
        for (int i = 0; i < count; i++) {
            if (frameUs[i] > 17_500) {
                over17++;
            }
            if (frameUs[i] > 34_000) {
                over34++;
            }
            total += frameUs[i];
        }
        long[] d = new long[BenchGL20.COUNT];
        for (int i = 0; i < BenchGL20.COUNT; i++) {
            d[i] = BenchGL20.counts[i] - glStart[i];
        }
        int div = Math.max(1, count);
        int shortFlights = 0;
        int flown = 0;
        double worst = 0;
        double minProgress = 1;
        float tx = Settings.WIDTH - 96f * Settings.scale;
        float ty = Settings.HEIGHT - 32f * Settings.scale;
        for (Flight fl : flights) {
            if (!fl.moving) {
                continue;
            }
            flown++;
            double dist = Math.hypot(fl.lastX - tx, fl.lastY - ty);
            double path = Math.max(1.0, Math.hypot(fl.startX - tx, fl.startY - ty));
            double progress = 1.0 - dist / path;
            worst = Math.max(worst, dist);
            minProgress = Math.min(minProgress, progress);
            if (progress < 0.8) {
                shortFlights++;
            }
        }
        StringBuilder b = new StringBuilder(512);
        b.append("[bench] scen=").append(s.name)
                .append(" frames=").append(count)
                .append(" fps=").append(fmt(total > 0 ? count * 1e6 / total : 0))
                .append(" ms_p50=").append(ms(f, count, 0.50))
                .append(" ms_p95=").append(ms(f, count, 0.95))
                .append(" ms_p99=").append(ms(f, count, 0.99))
                .append(" ms_max=").append(ms(f, count, 1.0))
                .append(" game_p50=").append(ms(g, count, 0.50))
                .append(" game_p95=").append(ms(g, count, 0.95))
                .append(" game_max=").append(ms(g, count, 1.0))
                .append(" swap_p50=").append(ms(sw, count, 0.50))
                .append(" swap_p95=").append(ms(sw, count, 0.95))
                .append(" over17=").append(over17)
                .append(" over34=").append(over34)
                .append(" draws=").append(fmt((double) d[BenchGL20.DRAWS] / div))
                .append(" verts=").append(d[BenchGL20.VERTS] / div)
                .append(" binds=").append(fmt((double) d[BenchGL20.BINDS] / div))
                .append(" binds_same=").append(fmt((double) d[BenchGL20.BINDS_SAME] / div))
                .append(" blends=").append(fmt((double) d[BenchGL20.BLEND_FUNCS] / div))
                .append(" blends_same=").append(fmt((double) d[BenchGL20.BLEND_SAME] / div))
                .append(" progs=").append(fmt((double) d[BenchGL20.PROGRAMS] / div))
                .append(" progs_same=").append(fmt((double) d[BenchGL20.PROGRAMS_SAME] / div))
                .append(" toggles=").append(fmt((double) d[BenchGL20.TOGGLES] / div))
                .append(" uniforms=").append(fmt((double) d[BenchGL20.UNIFORMS] / div))
                .append(" buf_kb=").append(d[BenchGL20.BUFFER_BYTES] / 1024 / div)
                .append(" tex_up=").append(d[BenchGL20.TEX_UPLOADS])
                .append(" sub_up=").append(d[BenchGL20.SUB_UPLOADS])
                .append(" shaders=").append(d[BenchGL20.SHADER_COMPILES])
                .append(" up_ms=").append(upUs / 1000)
                .append(" up_n=").append(upCalls)
                .append(" stb_ms=").append(stbUs / 1000)
                .append(" io_ms=").append(ioUs / 1000)
                .append(" fxmax=").append(fxMax)
                .append(" draw_ms=").append(scenDrawUs / 1000)
                .append(" gc=").append(VitaNatives.benchGcStat(0) - gcStart)
                .append(" gc_full=").append(VitaNatives.benchGcStat(1) - gcFullStart)
                .append(" gc_ms=").append((VitaNatives.benchGcStat(2) - gcUsStart) / 1000)
                .append(" alloc_kb=").append((VitaNatives.benchGcStat(3) - allocStart) / 1024)
                .append(" flights=").append(flown)
                .append(" short=").append(shortFlights)
                .append(" worst_px=").append((int) worst)
                .append(" min_progress=").append(flown > 0 ? (int) Math.round(minProgress * 100) : 100);
        VitaNatives.log(b.toString());
    }

    private void finish() {
        VitaNatives.log("[bench] done s=" + (VitaNatives.micros() - benchStart) / 1_000_000L);
        next(Stage.DONE);
        Gdx.app.exit();
    }
}
