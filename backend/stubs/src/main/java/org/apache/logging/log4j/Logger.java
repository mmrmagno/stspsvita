package org.apache.logging.log4j;

/**
 * Shadow of the log4j-api Logger interface, declaring only the members the
 * game's bytecode invokes (the TeaVM linker reports any we miss). Shadowing
 * log4j entirely keeps log4j-core's reflection/JMX/ConcurrentSkipListMap
 * machinery out of the reachability graph - it alone caused ~170 of the 260
 * errors in the first transpile.
 */
public interface Logger {

    void trace(String message);

    void debug(String message);

    void info(String message);

    void info(Object message);

    void info(String message, Object param);

    void info(String message, Object... params);

    void info(String message, Throwable t);

    void warn(String message);

    void warn(String message, Throwable t);

    void error(String message);

    void error(String message, Object... params);

    void error(String message, Throwable t);

    void fatal(String message);

    void catching(Throwable t);
}
