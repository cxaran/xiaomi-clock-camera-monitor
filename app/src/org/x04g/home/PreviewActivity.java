package org.x04g.home;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import org.x04g.home.ui.PageView;

/** Explicit ADB/DUMP-permission diagnostic, with no HOME or launcher registration. */
public final class PreviewActivity extends Activity {
    private AppSession session;private String selected;
    @Override public void onCreate(Bundle state){super.onCreate(state);getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,WindowManager.LayoutParams.FLAG_FULLSCREEN);getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);session=new AppSession(this);selected="gate".equals(getIntent().getStringExtra("page"))?AppSession.GATE_ID:AppSession.CAMERA_ID;PageView page=AppSession.GATE_ID.equals(selected)?session.gateView():session.cameraView();setContentView(page);}
    @Override protected void onResume(){super.onResume();if(session!=null)session.selected(selected,true);}
    @Override protected void onPause(){if(session!=null)session.selected(selected,false);super.onPause();}
    @Override protected void onDestroy(){if(session!=null)session.close();super.onDestroy();}
    @Override public void onTrimMemory(int level){if(session!=null)session.onTrimMemory(level);super.onTrimMemory(level);}
}
