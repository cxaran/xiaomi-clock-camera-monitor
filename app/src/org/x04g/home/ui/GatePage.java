package org.x04g.home.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

public final class GatePage extends PageView {
    private static final int OFFLINE_COLOR=0xfff28b82;
    public interface Listener {void onGateTap();void onGateVisible(boolean visible);}
    private final Listener listener; private final TextView status; private final Button action;
    public GatePage(Context c,Listener listener){super(c);this.listener=listener;setClickable(true);
        put(OemTheme.label(c,"Portón",64,OemTheme.TEXT),56,166,370,88,64);
        status=OemTheme.label(c,"○ Comprobando…",32,OemTheme.SECONDARY);put(status,56,260,390,88,32);
        action=new Button(c);action.setText("\uf011");action.setTypeface(OemTheme.icons());action.setTextColor(OemTheme.TEXT);action.setTextSize(32);action.setPadding(0,0,0,0);action.setMinHeight(0);action.setMinWidth(0);action.setMinimumHeight(0);action.setMinimumWidth(0);action.setBackground(OemTheme.surface(OemTheme.CONTROL,1000));action.setContentDescription("Accionar portón");action.setEnabled(false);action.setOnClickListener(new OnClickListener(){public void onClick(View v){if(active&&action.isEnabled())GatePage.this.listener.onGateTap();}});put(action,570,163,120,120,48);
        TextView label=OemTheme.label(c,"Accionar portón",31.5f,OemTheme.SECONDARY);label.setGravity(Gravity.CENTER);put(label,456,302,344,58,31.5f);
    }
    public void showState(String text,boolean linked,boolean canActuate){showState(text,linked,canActuate,false);}
    public void showState(String text,boolean linked,boolean canActuate,boolean offline){status.setText((linked?"● ":"○ ")+text);status.setTextColor(offline?OFFLINE_COLOR:linked?OemTheme.ACTION:OemTheme.SECONDARY);action.setEnabled(canActuate&&active);action.setAlpha(canActuate?1:.55f);}
    @Override protected void onActiveChanged(boolean value){if(!value)action.setEnabled(false);listener.onGateVisible(value);}
}
