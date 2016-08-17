package li.xiangyang.android.lclogger;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;
import android.util.Log;


import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class AsyncLoggingWorker {

    private static final String TAG = "Android-LCLogger";

    private static final int RECONNECT_WAIT = 5000; // milliseconds.
    private static final int MAX_QUEUE_POLL_TIME = 1000; // milliseconds.
    private static final int QUEUE_SIZE = 32768;
    public static final int LOG_LENGTH_LIMIT = 65536;

    private static final int MAX_NETWORK_FAILURES_ALLOWED = 1;
    private static final int MAX_RECONNECT_ATTEMPTS = 1;

    private static final String QUEUE_OVERFLOW = "Log Buffer Queue Overflow. Message Dropped!";

    private boolean started = false;

    private SocketAppender appender;

    private ArrayBlockingQueue<String> queue;

    private LogStorage localStorage;

    private ExecutorService executorService = Executors.newFixedThreadPool(1);

    public AsyncLoggingWorker(Context context, String endpoint, String logToken, String userAgent) throws IOException {

        queue = new ArrayBlockingQueue<String>(QUEUE_SIZE);
        localStorage = new LogStorage(context);
        appender = new SocketAppender(endpoint, logToken, userAgent);
        executorService.submit(appender);
        started = true;
    }

    public void addLineToQueue(String line) {

        if (line.length() > LOG_LENGTH_LIMIT) {
            for (String logChunk : Utils.splitStringToChunks(line, LOG_LENGTH_LIMIT)) {
                tryOfferToQueue(logChunk);
            }

        } else {
            tryOfferToQueue(line);
        }

        // Check that we have all parameters set and socket appender running.
        if (!this.started) {
            executorService.submit(appender);
            started = true;
        }
    }

    /**
     * Stops the socket appender. queueFlushTimeout (if greater than 0) sets the maximum timeout in milliseconds for
     * the message queue to be flushed by the socket appender, before it is stopped. If queueFlushTimeout
     * is equal to zero - the method will wait until the queue is empty (which may be dangerous if the
     * queue is constantly populated by another thread mantime.
     *
     * @param queueFlushTimeout - max. wait time in milliseconds for the message queue to be flushed.
     */
    public void close(long queueFlushTimeout) {
        if (queueFlushTimeout < 0) {
            throw new IllegalArgumentException("queueFlushTimeout must be greater or equal to zero");
        }

        long now = System.currentTimeMillis();

        while (!queue.isEmpty()) {
            if (queueFlushTimeout != 0) {
                if (System.currentTimeMillis() - now >= queueFlushTimeout) {
                    // The timeout expired - need to stop the appender.
                    break;
                }
            }
        }
        executorService.shutdown();
        started = false;
    }

    public void close() {
        close(0);
    }

    private void tryOfferToQueue(String line) throws RuntimeException {
        if (!queue.offer(line)) {
            Log.e(TAG, "The queue is full - will try to drop the oldest message in it.");
            queue.poll();
            /*
            FIXME: This code migrated from LE Java Library; currently, there is no a simple
            way to backup the queue in case of overflow due to requirements to max.
            memory consumption and max. possible size of the local logs storage. If use
            the local storage - the we have three problems: 1) Correct joining of logs from
            the queue and from the local storage (and we need some right event to trigger this joining);
            2) Correct order of logs after joining; 3) Data consistence problem, because we're
            accessing the storage from different threads, so sync. logic will increase overall
            complexity of the code. So, for now this logic is left AS IS, due to relatively
            rareness of the case with queue overflow.
             */

            if (!queue.offer(line)) {
                throw new RuntimeException(QUEUE_OVERFLOW);
            }
        }
    }

    private class SocketAppender implements Runnable {

        private LogClient leClient;

        private String token;
        private String endpoint;
        private String userAgent;

        public SocketAppender(String endpoint, String token, String userAgent) {
            super();

            this.endpoint = endpoint;
            this.token = token;
            this.userAgent = userAgent;
        }

        private void openConnection() throws IOException, InstantiationException {
            if (leClient == null) {
                leClient = new LogClient(endpoint, token, userAgent);
            }
            leClient.connect();
        }

        private boolean reopenConnection(int maxReConnectAttempts) throws InterruptedException, InstantiationException {
            if (maxReConnectAttempts < 0) {
                throw new IllegalArgumentException("maxReConnectAttempts value must be greater or equal to zero");
            }

            // Close the previous connection
            closeConnection();

            for (int attempt = 0; attempt < maxReConnectAttempts; ++attempt) {
                try {

                    openConnection();
                    return true;

                } catch (IOException e) {
                    // Ignore the exception and go for the next
                    // iteration.
                }

                Thread.sleep(RECONNECT_WAIT);
            }

            return false;
        }


        private void closeConnection() {
            if (this.leClient != null) {
                this.leClient.close();
            }
        }

        @TargetApi(Build.VERSION_CODES.GINGERBREAD)
        private boolean tryUploadSavedLogs() {
            Queue<String> logs = new ArrayDeque<String>();

            try {

                logs = localStorage.getAllLogsFromStorage(false);
                for (String msg = logs.peek(); msg != null; msg = logs.peek()) {
                    leClient.write(msg);
                    logs.poll(); // Remove the message after successful sending.
                }

                // All logs have been uploaded - remove the storage file and create the blank one.
                try {
                    localStorage.reCreateStorageFile();
                } catch (IOException ex) {
                    Log.e(TAG, ex.getMessage());
                }

                return true;

            } catch (IOException ioEx) {
                Log.e(TAG, "Cannot upload logs to the server. Error: " + ioEx.getMessage());

                // Try to save back all messages, that haven't been sent yet.
                try {
                    localStorage.reCreateStorageFile();
                    for (String msg : logs) {
                        localStorage.putLogToStorage(msg);
                    }
                } catch (IOException ioEx2) {
                    Log.e(TAG, "Cannot save logs to the local storage - part of messages will be " +
                            "dropped! Error: " + ioEx2.getMessage());
                }
            }

            return false;
        }

        @Override
        public void run() {
            try {

                // Open connection
                reopenConnection(MAX_RECONNECT_ATTEMPTS);

                Queue<String> prevSavedLogs = localStorage.getAllLogsFromStorage(true);

                int numFailures = 0;
                boolean connectionIsBroken = false;
                String message = null;

                // Send data in queue
                while (!prevSavedLogs.isEmpty() || !queue.isEmpty()) {

                    // First we need to send the logs from the local storage -
                    // they haven't been sent during the last session, so need to
                    // come first.
                    if (prevSavedLogs.isEmpty()) {

                        // Try to take data from the queue if there are no logs from
                        // the local storage left to send.
                        message = queue.poll(MAX_QUEUE_POLL_TIME, TimeUnit.MILLISECONDS);

                    } else {

                        // Getting messages from the previous session one by one.
                        message = prevSavedLogs.poll();
                    }

                    // Send data, reconnect if needed.
                    while (true) {

                        try {

                            if (message != null) {
                                this.leClient.write(message);
                                message = null;
                            }

                            // If we have broken connection, then try to re-connect and send
                            // all logs from the local storage. If succeeded - reset numFailures.
                            if (connectionIsBroken && reopenConnection(MAX_RECONNECT_ATTEMPTS)) {
                                if (tryUploadSavedLogs()) {
                                    connectionIsBroken = false;
                                    numFailures = 0;
                                }
                            }

                        } catch (IOException e) {

                            if (numFailures >= MAX_NETWORK_FAILURES_ALLOWED) {
                                connectionIsBroken = true; // Have tried to reconnect for MAX_NETWORK_FAILURES_ALLOWED
                                // times and failed, so assume, that we have no link to the
                                // server at all...
                                try {
                                    // ... and put the current message to the local storage.
                                    localStorage.putLogToStorage(message);
                                    message = null;
                                } catch (IOException ex) {
                                    Log.e(TAG, "Cannot save the log message to the local storage! Error: " +
                                            ex.getMessage());
                                }


                                finish();
                                return;
                            } else {
                                ++numFailures;

                                // Try to re-open the lost connection.
                                reopenConnection(MAX_RECONNECT_ATTEMPTS);
                            }

                            continue;
                        }

                        break;
                    }
                }
            } catch (InterruptedException e) {
                // We got interrupted, stop.

            } catch (InstantiationException e) {
                Log.e(TAG, "Cannot instantiate LogClient due to improper configuration. Error: " + e.getMessage());

                // Save all existing logs to the local storage.
                // There is nothing we can do else in this case.
                String message = queue.poll();
                try {
                    while (message != null) {
                        localStorage.putLogToStorage(message);
                        message = queue.poll();
                    }
                } catch (IOException ex) {
                    Log.e(TAG, "Cannot save logs queue to the local storage - all log messages will be dropped! Error: " +
                            e.getMessage());
                }
            }

            finish();
        }

        private void finish() {
            closeConnection();
            started = false;
        }
    }


}
