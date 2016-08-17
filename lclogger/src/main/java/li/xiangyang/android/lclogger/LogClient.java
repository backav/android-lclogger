package li.xiangyang.android.lclogger;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

public class LogClient {

    private String endpoint;
    private String endpointToken;
    private String userAgent;

    private HttpURLConnection connection;

    public LogClient(String endpoint, String token, String userAgent)
            throws InstantiationException, IllegalArgumentException {

        this.endpoint = endpoint;
        this.endpointToken = token;
        this.userAgent = userAgent;
    }

    public void connect() throws IOException, IllegalArgumentException {

    }

    public void write(String data) throws IOException {

        connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setDoOutput(true);
        connection.setUseCaches(false);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("X-LOGCENTRAL-TOKEN", endpointToken);
        connection.setRequestProperty("User-Agent", URLEncoder.encode(userAgent, "UTF8"));
        connection.setRequestProperty("Content-Type", "application/octet-stream");

        BufferedOutputStream bw = new BufferedOutputStream(connection.getOutputStream());
        bw.write(data.getBytes("UTF8"));
        bw.close();
        if (connection.getResponseCode() != 200) {
            throw new IOException("upload fail with state code:" + connection.getResponseCode());
        }
        connection.disconnect();
    }

    public void close() {
    }

}
