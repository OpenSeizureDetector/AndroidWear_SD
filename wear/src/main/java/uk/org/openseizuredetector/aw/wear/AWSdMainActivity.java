package uk.org.openseizuredetector.aw.wear;

import android.app.Activity;
import android.os.Bundle;
import android.view.ViewStub;
import android.widget.TextView;

import uk.org.openseizuredetector.aw.R;

public class AWSdMainActivity extends Activity {

    private final static String TAG = "AWSdMainActivity";

    private TextView mTextView;
    //private ToggleButton mToggleButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wear_app_main);
        final ViewStub stub = findViewById(R.id.watch_view_stub);

        mTextView = stub.findViewById(R.id.text);


    }

    @Override
    protected void onStart() {
        super.onStart();
    }

    @Override
    protected void onRestart() {
        super.onRestart();
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }
}
