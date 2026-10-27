package org.x04g.home.ui;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.os.SystemClock;
import android.text.SpannableString;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

public final class CameraPage extends PageView implements TextureView.SurfaceTextureListener {
    public static final class Choice {public final String id,label;public Choice(String id,String label){this.id=id;this.label=label;}}
    public interface Listener {void onSurfaceReady(Surface surface);void onSurfaceGone();void onCameraVisible(boolean visible);void onCameraChosen(String id);void onAudioChanged(boolean enabled);void onAdmin();}
    private final Listener listener;private TextureView video;private Surface surface;
    private final TextView name,message,zoomLabel;private final Button audio;
    private FrameLayout selector;private final List<Choice> choices=new ArrayList<Choice>();
    private String selected="";private boolean sound,hasVideo,menuOpen;private int videoW=16,videoH=9;
    private float zoom=1,tx,ty,downX,downY,originX,originY,lastX,lastY;private long lastUp;
    private boolean gesture,moved,longPressed;private int pointer=-1;
    private final Runnable longPress=new Runnable(){public void run(){if(gesture&&!moved){longPressed=true;cancelGesture();openSelector();}}};
    public CameraPage(Context c,Listener listener){super(c);this.listener=listener;
        name=OemTheme.label(c,"Cámaras",36,OemTheme.TEXT);name.setBackgroundColor(OemTheme.BACKGROUND);put(name,44,38,400,48,36);name.setVisibility(GONE);
        message=OemTheme.label(c,"Selecciona una cámara",40,OemTheme.SECONDARY);message.setGravity(Gravity.CENTER);put(message,80,150,640,160,40);
        audio=round(c,"\ue04f","Activar sonido");audio.setBackground(null);audio.setOnClickListener(new OnClickListener(){public void onClick(View v){sound=!sound;audio.setText(OemTheme.icon(sound?"\ue050":"\ue04f"));audio.setContentDescription(sound?"Silenciar sonido":"Activar sonido");CameraPage.this.listener.onAudioChanged(sound);}});put(audio,44,346,72,72,44);
        zoomLabel=OemTheme.label(c,"2×",30,OemTheme.ACTION);zoomLabel.setGravity(Gravity.CENTER);zoomLabel.setBackground(OemTheme.surface(OemTheme.CONTROL,30));put(zoomLabel,668,360,88,50,30);zoomLabel.setVisibility(GONE);
        clock.setBackgroundColor(OemTheme.BACKGROUND);clock.setVisibility(GONE);clock.bringToFront();setClickable(true);setContentDescription("Cámara. Doble toque para ampliar; mantener pulsado para seleccionar otra cámara.");
    }
    private static Button round(Context c,String glyph,String description){Button b=new Button(c);b.setPadding(0,0,0,0);b.setMinWidth(0);b.setMinHeight(0);b.setMinimumWidth(0);b.setMinimumHeight(0);b.setText(OemTheme.icon(glyph));b.setTypeface(OemTheme.icons());b.setTextColor(OemTheme.TEXT);b.setBackground(OemTheme.surface(OemTheme.CONTROL,1000));b.setContentDescription(description);return b;}
    public void setCameras(List<Choice> list,String id){choices.clear();choices.addAll(list);selected=id==null?"":id;for(Choice c:choices)if(c.id.equals(selected))name.setText(c.label);if(menuOpen){closeSelector();openSelector();}}
    public void showStreamState(String text,boolean picture){hasVideo=picture;message.setText(text==null?"":text);message.setVisibility(picture?GONE:VISIBLE);audio.setEnabled(active&&picture&&!menuOpen);if(video!=null)video.setAlpha(picture?1:0);if(!picture)resetZoom();}
    public void setVideoSize(int w,int h){if(w>0&&h>0){videoW=w;videoH=h;applyTransform();}}
    public void setSoundSelected(boolean enabled){sound=enabled;audio.setText(OemTheme.icon(sound?"\ue050":"\ue04f"));audio.setContentDescription(sound?"Silenciar sonido":"Activar sonido");}
    public void resetZoom(){zoom=1;tx=ty=0;zoomLabel.setVisibility(GONE);applyTransform();}
    private void applyTransform(){if(video==null||getWidth()==0)return;float w=getWidth(),h=getHeight(),fit=Math.min(w/videoW,h/videoH),iw=videoW*fit,ih=videoH*fit;tx=Math.max(-Math.max(0,(iw*zoom-w)/2),Math.min(Math.max(0,(iw*zoom-w)/2),tx));ty=Math.max(-Math.max(0,(ih*zoom-h)/2),Math.min(Math.max(0,(ih*zoom-h)/2),ty));Matrix matrix=new Matrix();matrix.setScale(iw/w,ih/h,w/2,h/2);matrix.postScale(zoom,zoom,w/2,h/2);matrix.postTranslate(tx,ty);video.setTransform(matrix);}
    @Override protected boolean capturesPagerTouch(float x,float edge){return menuOpen||(zoom>1&&x>=edge&&x<=getWidth()-edge)||super.capturesPagerTouch(x,edge);}
    @Override public boolean onTouchEvent(MotionEvent e){float edge=24*getResources().getDisplayMetrics().density,slop=8*getResources().getDisplayMetrics().density;int action=e.getActionMasked();
        if(menuOpen)return true;
        if(action==MotionEvent.ACTION_DOWN){if(e.getX()<edge||e.getX()>getWidth()-edge)return false;gesture=true;moved=longPressed=false;pointer=e.getPointerId(0);downX=e.getX();downY=e.getY();originX=tx;originY=ty;main.postDelayed(longPress,700);return true;}
        if(!gesture)return false;
        if(action==MotionEvent.ACTION_POINTER_DOWN||action==MotionEvent.ACTION_CANCEL){cancelGesture();lastUp=0;return true;}
        int i=e.findPointerIndex(pointer);if(i<0){cancelGesture();return true;}float x=e.getX(i),y=e.getY(i),dx=x-downX,dy=y-downY;
        if(action==MotionEvent.ACTION_MOVE){if(Math.hypot(dx,dy)>slop){moved=true;main.removeCallbacks(longPress);lastUp=0;}if(moved&&zoom>1){tx=originX+dx;ty=originY+dy;applyTransform();}return true;}
        if(action==MotionEvent.ACTION_UP){main.removeCallbacks(longPress);gesture=false;pointer=-1;if(!moved&&!longPressed&&hasVideo){long now=SystemClock.uptimeMillis();if(lastUp>0&&now-lastUp<=300&&Math.hypot(x-lastX,y-lastY)<=24*getResources().getDisplayMetrics().density){lastUp=0;if(zoom>1)resetZoom();else{zoom=2;tx=(getWidth()/2f-x)*2;ty=(getHeight()/2f-y)*2;zoomLabel.setVisibility(VISIBLE);applyTransform();}}else{lastUp=now;lastX=x;lastY=y;}}return true;}return true;
    }
    private void cancelGesture(){gesture=false;pointer=-1;main.removeCallbacks(longPress);}
    public void openSelector(){if(menuOpen)return;cancelGesture();lastUp=0;menuOpen=true;selector=new PageView(getContext()){@Override protected void onActiveChanged(boolean value){}};selector.setBackgroundColor(OemTheme.BACKGROUND);PageView panel=(PageView)selector;
        Button close=round(getContext(),"\ue5cd","Cerrar selector");close.setOnClickListener(new OnClickListener(){public void onClick(View v){closeSelector();}});panel.put(close,688,18,72,72,44);panel.put(OemTheme.label(getContext(),"Cámaras",36,OemTheme.TEXT),224,36,440,48,36);
        int count=choices.size();for(int n=0;n<count;n++){final Choice c=choices.get(n);Button b=new Button(getContext());SpannableString text=new SpannableString("\ue04b\n"+c.label);OemTheme.styleIcon(text,0,1);b.setText(text);b.setTypeface(OemTheme.regular());b.setTextColor(c.id.equals(selected)?OemTheme.ACTION:OemTheme.TEXT);b.setAllCaps(false);b.setBackground(OemTheme.surface(OemTheme.CONTROL,22));b.setContentDescription("Ver cámara "+c.label);b.setOnClickListener(new OnClickListener(){public void onClick(View v){selected=c.id;name.setText(c.label);resetZoom();closeSelector();listener.onCameraChosen(c.id);}});int columns=count<=6?3:4,rows=(count+columns-1)/columns;float cell=(720-16*(columns-1))/(float)columns,cellH=Math.min(146,(280-12*(rows-1))/(float)rows);panel.put(b,40+(n%columns)*(cell+16),124+(n/columns)*(cellH+12),cell,cellH,32);}
        Button admin=new Button(getContext());admin.setText("Administrar");admin.setAllCaps(false);admin.setTypeface(OemTheme.regular());admin.setTextColor(OemTheme.ACTION);admin.setBackgroundColor(OemTheme.BACKGROUND);admin.setOnClickListener(new OnClickListener(){public void onClick(View v){closeSelector();listener.onAdmin();}});panel.put(admin,280,414,240,58,30);
        addView(selector,new FrameLayout.LayoutParams(-1,-1));selectorClock(true);clock.bringToFront();audio.setEnabled(false);if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);selector.announceForAccessibility("Seleccionar cámara");
    }
    private void selectorClock(boolean selecting){clock.setVisibility(selecting?VISIBLE:GONE);clock.setGravity((selecting?Gravity.START:Gravity.RIGHT)|Gravity.TOP);clock.setLayoutParams(new Box(selecting?40:600,38,156,44,36));}
    public void closeSelector(){boolean wasOpen=menuOpen;menuOpen=false;if(selector!=null){removeView(selector);selector=null;}if(wasOpen)selectorClock(false);audio.setEnabled(active&&hasVideo);if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);}
    @Override protected void onActiveChanged(boolean value){cancelGesture();lastUp=0;if(!value){closeSelector();hasVideo=false;audio.setEnabled(false);resetZoom();listener.onCameraVisible(false);if(video!=null){removeView(video);video=null;}if(surface!=null){surface.release();surface=null;}}else{if(video==null){video=new TextureView(getContext());video.setOpaque(true);video.setSurfaceTextureListener(this);addView(video,0,new FrameLayout.LayoutParams(-1,-1));}listener.onCameraVisible(true);}}
    @Override public void onSurfaceTextureAvailable(SurfaceTexture texture,int w,int h){if(!active)return;surface=new Surface(texture);applyTransform();listener.onSurfaceReady(surface);}
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture,int w,int h){applyTransform();}
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture){listener.onSurfaceGone();if(surface!=null){surface.release();surface=null;}return true;}
    @Override public void onSurfaceTextureUpdated(SurfaceTexture texture){}
    @Override public void release(){super.release();closeSelector();cancelGesture();}
}
