package org.x04g.home.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.util.Calendar;

/** 800x480 reference coordinates, rendered as Android Views, never a WebView. */
public abstract class PageView extends FrameLayout {
    protected final Handler main=new Handler(Looper.getMainLooper());
    protected final TextView clock;
    protected boolean active;
    private final Runnable tick=new Runnable(){public void run(){if(!active)return;clock.setText(DateFormat.format(DateFormat.is24HourFormat(getContext())?"HH:mm":"h:mm",Calendar.getInstance()));main.postDelayed(this,60000-System.currentTimeMillis()%60000+20);}};
    public static final class Box extends FrameLayout.LayoutParams {
        public final float x,y,w,h,font;
        public Box(float x,float y,float w,float h,float font){super(1,1);this.x=x;this.y=y;this.w=w;this.h=h;this.font=font;}
    }
    protected PageView(Context c){super(c);OemTheme.load(c);setBackgroundColor(OemTheme.BACKGROUND);setClipChildren(true);clock=OemTheme.label(c,"",36,OemTheme.TEXT);clock.setGravity(Gravity.RIGHT|Gravity.TOP);put(clock,600,38,156,44,36);}
    protected final void put(View child,float x,float y,float w,float h,float font){addView(child,new Box(x,y,w,h,font));}
    protected final float sx(){return getWidth()/800f;} protected final float sy(){return getHeight()/480f;}
    @Override protected void onMeasure(int wm,int hm){int w=MeasureSpec.getSize(wm),h=MeasureSpec.getSize(hm);setMeasuredDimension(w,h);for(int i=0;i<getChildCount();i++){View v=getChildAt(i);if(v.getVisibility()==GONE)continue;ViewGroup.LayoutParams p=v.getLayoutParams();if(p instanceof Box){Box b=(Box)p;v.measure(MeasureSpec.makeMeasureSpec(Math.round(b.w*w/800f),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(Math.round(b.h*h/480f),MeasureSpec.EXACTLY));if(v instanceof TextView&&b.font>0)((TextView)v).setTextSize(TypedValue.COMPLEX_UNIT_PX,b.font*w/800f);}else v.measure(MeasureSpec.makeMeasureSpec(w,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(h,MeasureSpec.EXACTLY));}}
    @Override protected void onLayout(boolean changed,int l,int t,int r,int b){float x=(r-l)/800f,y=(b-t)/480f;for(int i=0;i<getChildCount();i++){View v=getChildAt(i);ViewGroup.LayoutParams p=v.getLayoutParams();if(p instanceof Box){Box z=(Box)p;int a=Math.round(z.x*x),q=Math.round(z.y*y);v.layout(a,q,a+v.getMeasuredWidth(),q+v.getMeasuredHeight());}else v.layout(0,0,r-l,b-t);}}
    /** Nested pages are modal editors/selectors; top-level pages leave the OEM pager free. */
    protected boolean capturesPagerTouch(float x,float edge){return getParent() instanceof PageView&&x>=edge&&x<=getWidth()-edge;}
    @Override public boolean dispatchTouchEvent(MotionEvent e){if(e.getActionMasked()==MotionEvent.ACTION_DOWN){float edge=24*getResources().getDisplayMetrics().density;if(capturesPagerTouch(e.getX(),edge)&&getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);}boolean result=super.dispatchTouchEvent(e);if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL){if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);}return result;}
    public void setActive(boolean value){if(active==value)return;active=value;main.removeCallbacks(tick);if(value)tick.run();onActiveChanged(value);}
    protected abstract void onActiveChanged(boolean value);
    public void release(){setActive(false);main.removeCallbacksAndMessages(null);}
    @Override protected void onDetachedFromWindow(){setActive(false);super.onDetachedFromWindow();}
}
