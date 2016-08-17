package li.xiangyang.android.lclogger;

import android.content.Context;

import java.io.IOException;

public class Logger {

    private static Logger instance;

    private AsyncLoggingWorker loggingWorker;

    private Logger(Context context, String endpoint, String token, String userAgent) throws IOException {
        loggingWorker = new AsyncLoggingWorker(context, endpoint, token, userAgent);
    }

    public static synchronized Logger createInstance(Context context, String endpoint, String token, String userAgent)
            throws IOException {
        if (instance != null) {
            instance.loggingWorker.close();
        }

        instance = new Logger(context, endpoint, token, userAgent);
        return instance;
    }

    public static synchronized Logger getInstance() {
        if (instance != null) {
            return instance;
        } else {
            throw new IllegalArgumentException("Logger instance is not initialized. Call createInstance() first!");
        }
    }

    public void log(String message) {
        loggingWorker.addLineToQueue(message);
    }

}
