package uz.tinglaymiz.app;

import android.app.Application;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        ErrorLog.install(this);
    }
}
