package org.apache.logging.log4j;

import com.stsvita.backend.VitaNatives;

/** Shadow of log4j-api LogManager: loggers print straight to stdout. */
public final class LogManager {

    public static Logger getLogger(String name) {
        return new StdoutLogger(shortName(name));
    }

    public static Logger getLogger(Class<?> clazz) {
        return new StdoutLogger(clazz.getSimpleName());
    }

    public static Logger getLogger() {
        return new StdoutLogger("?");
    }

    public static void shutdown() {
        System.out.flush();
        System.err.flush();
    }

    private static String shortName(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : name;
    }

    private LogManager() {}
}

class StdoutLogger implements Logger {

    private final String tag;
    private static boolean cityTransition;

    StdoutLogger(String tag) {
        this.tag = tag;
    }

    private void out(String level, String message) {
        // TipHelper emits the same hover marker every frame while browsing
        // potion/relic tooltips. Even with batched stdout, formatting and
        // copying this diagnostic spam every render frame is wasted work.
        if ("TipHelper".equals(tag)
                && ("TRACE".equals(level) || "DEBUG".equals(level) || "INFO".equals(level))) {
            return;
        }
        String line = level + " " + tag + "> " + message;
        transitionBoundary(message);
        if ("WARN".equals(level) || "ERROR".equals(level) || "FATAL".equals(level)) {
            System.out.flush();
            System.err.println(line);
        } else {
            System.out.println(line);
        }
    }

    /** Keep only the boundaries needed to reconstruct Act 1 -> City. */
    private void transitionBoundary(String message) {
        if ("MusicMaster".equals(tag)
                && message.startsWith("Playing STS_BossVictoryStinger")) {
            VitaNatives.transitionDiag("act1-complete boss-victory");
            return;
        }
        if ("AbstractDungeon".equals(tag) && message.startsWith("[BOSS] Automaton")) {
            cityTransition = true;
            VitaNatives.transitionDiag("city-constructor boss=Automaton");
            return;
        }
        if (!cityTransition || !"AbstractDungeon".equals(tag)) {
            return;
        }
        if (message.startsWith("Content generation time:")) {
            VitaNatives.transitionDiag("city-content-generation-complete");
        } else if (message.startsWith("Generating Room Types!")) {
            VitaNatives.transitionDiag("city-map-generation-begin");
        } else if (message.startsWith("Map generation time:")) {
            VitaNatives.transitionDiag("city-map-generation-complete");
        }
    }

    @Override
    public void trace(String message) {
        out("TRACE", message);
    }

    @Override
    public void debug(String message) {
        out("DEBUG", message);
    }

    @Override
    public void info(String message) {
        out("INFO", message);
    }

    @Override
    public void info(Object message) {
        out("INFO", String.valueOf(message));
    }

    @Override
    public void info(String message, Object param) {
        out("INFO", message + " " + param);
    }

    @Override
    public void info(String message, Object... params) {
        out("INFO", message);
    }

    @Override
    public void info(String message, Throwable t) {
        out("INFO", message + " :: " + t);
    }

    @Override
    public void warn(String message) {
        out("WARN", message);
    }

    @Override
    public void warn(String message, Throwable t) {
        out("WARN", message + " :: " + t);
    }

    @Override
    public void error(String message) {
        out("ERROR", message);
    }

    @Override
    public void error(String message, Object... params) {
        out("ERROR", message);
    }

    @Override
    public void error(String message, Throwable t) {
        out("ERROR", message + " :: " + t);
        if (t != null) {
            t.printStackTrace();
        }
    }

    @Override
    public void fatal(String message) {
        out("FATAL", message);
    }

    @Override
    public void catching(Throwable t) {
        out("ERROR", "caught: " + t);
        if (t != null) {
            t.printStackTrace();
        }
    }
}
