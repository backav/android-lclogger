package li.xiangyang.android.lclogger;

import android.app.Activity;
import android.os.Bundle;

import java.io.IOException;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        try {
            Logger.createInstance(this, "http://logcentral.iriding.cc/log", "bac", "LCLoggerSamples/0.1");
            Logger.getInstance().log("bac XXX");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
