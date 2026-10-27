package org.x04g.home.ui.adminweb;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import org.x04g.home.qr.QrCode;
import org.x04g.home.qr.QrSegment;
import org.x04g.home.ui.OemTheme;
import org.x04g.home.ui.PageView;

/** Native800x480 UI only. The owner starts one HTTP listener only after Iniciar. */
public final class WebAdminPanel extends PageView implements AutoCloseable {
    public interface Listener {
        void onStartHttp();
        void onStop();
        void onBack();
        void onManualTestDecision(String cameraId,long revision,boolean approved);
    }
    private enum Step { NEW, STARTING, HTTP, PAIRED, STOPPED, FAILED }
    private final Listener listener;
    private Step step=Step.NEW;
    private boolean closed,sessionOwned,stopSent;
    private String publicUrl="",pairingCode="",manualId;
    private long expiry,manualRevision;
    private TextView ttl;
    private final Runnable countdown=new Runnable(){public void run(){
        if(closed || !active || !live() || expiry<=0)return;
        if(WebAdminRules.remainingSeconds(expiry,SystemClock.elapsedRealtime())==0){requestStop();step=Step.STOPPED;render();return;}
        if(ttl!=null)ttl.setText("Expira en "+WebAdminRules.countdown(expiry,SystemClock.elapsedRealtime()));
        main.postDelayed(this,1000);
    }};

    public WebAdminPanel(Context context,Listener listener){super(context);if(listener==null)throw new IllegalArgumentException("adminweb_listener_required");this.listener=listener;setSaveEnabled(false);setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);render();}
    public void open(){requireMain();if(closed)throw new IllegalStateException("adminweb_panel_closed");setActive(true);render();}

    /** Maps TemporaryAdminWeb.State.name(); no arbitrary backend text is shown. */
    public void showState(String name){
        requireMain();if(closed)return;Step next;
        try{next=Step.valueOf(name);}catch(Exception invalid){fail();return;}
        if(!active && next!=Step.NEW && next!=Step.STOPPED && next!=Step.FAILED){sessionOwned=true;requestStop();step=Step.STOPPED;render();return;}
        step=next;
        if(next==Step.STARTING){sessionOwned=true;stopSent=false;}
        else if(next==Step.STOPPED || next==Step.FAILED){sessionOwned=false;clearSession();}
        else if(next==Step.PAIRED)pairingCode="";
        else if(next==Step.HTTP&&!publicUrl.startsWith("http://"))publicUrl="";
        render();
    }
    /** The QR contains only the public LAN root URL, never the pairing code. */
    public void showReady(String url,long expiresAtElapsedRealtime){
        requireMain();if(closed)return;
        if(!active){sessionOwned=true;requestStop();step=Step.STOPPED;render();return;}
        try {
            String nextUrl=WebAdminRules.publicUrl(url);
            long now=SystemClock.elapsedRealtime();
            if(expiresAtElapsedRealtime<=now || expiresAtElapsedRealtime-now>300000L)throw new IllegalArgumentException();
            publicUrl=nextUrl;expiry=expiresAtElapsedRealtime;
            if(step!=Step.PAIRED)step=Step.HTTP;sessionOwned=true;stopSent=false;
            render();
        }catch(Exception invalid){fail();}
    }
    /** The code is shown only on this native display; it never enters the QR. */
    public void showPairingCode(String eightDigits){
        requireMain();if(closed)return;
        if(eightDigits==null || !eightDigits.matches("[0-9]{8}")){fail();return;}
        // Backend posts the code before onReady; retain it through that order.
        if(active){pairingCode=eightDigits;render();}else{sessionOwned=true;requestStop();}
    }
    public void showManualTestRequest(String id,long revision){
        requireMain();if(closed)return;
        if(!active || !(step==Step.HTTP || step==Step.PAIRED) || id==null || !id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}") || revision<0){
            if(id!=null && id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")&&revision>=0)listener.onManualTestDecision(id,revision,false);
            return;
        }
        if(manualId!=null){if(manualId.equals(id)&&manualRevision==revision)return;fail();return;}
        manualId=id;manualRevision=revision;render();
    }

    private boolean live(){return step==Step.HTTP || step==Step.PAIRED;}
    private void render(){
        main.removeCallbacks(countdown);removeAllViews();if(closed)return;clock.setGravity(Gravity.LEFT|Gravity.TOP);put(clock,40,38,156,44,36);clock.bringToFront();
        Button back=icon("\ue5c4","Volver");back.setOnClickListener(v->{requestStop();step=Step.STOPPED;render();listener.onBack();});put(back,696,22,64,64,40);
        put(label("Administrar",32,OemTheme.TEXT),224,36,264,48,32);
        if(sessionOwned){Button stop=button("Detener");stop.setOnClickListener(v->{requestStop();step=Step.STOPPED;render();});put(stop,514,24,142,60,24);}
        if(manualId!=null){renderManual();return;}
        if(step==Step.NEW || step==Step.STOPPED || step==Step.FAILED){
            put(label(step==Step.FAILED?"No se pudo abrir el acceso":step==Step.STOPPED?"Acceso temporal cerrado":"Configura desde tu teléfono",38,OemTheme.TEXT),44,138,712,64,38);
            TextView info=label("Pulsa Iniciar y escanea el QR desde tu misma red.\nEl acceso se cerrará al salir o cuando expire.",28,OemTheme.SECONDARY);put(info,44,218,712,116,28);
            Button start=button("Iniciar");start.setEnabled(active&&!closed);start.setOnClickListener(v->{if(!active||closed||!(step==Step.NEW||step==Step.STOPPED||step==Step.FAILED))return;clearSession();sessionOwned=true;stopSent=false;step=Step.STARTING;render();try{listener.onStartHttp();}catch(RuntimeException error){fail();}});put(start,220,354,360,72,30);return;
        }
        if(step==Step.STARTING || publicUrl.isEmpty()){
            put(label("Preparando acceso temporal…",36,OemTheme.SECONDARY),44,180,712,104,36);return;
        }
        QrView qr;
        try{qr=new QrView(getContext(),publicUrl);}catch(IllegalArgumentException invalid){fail();return;}
        put(qr,40,116,244,244,0);
        put(label(step==Step.PAIRED?"Teléfono emparejado":"Escanea el QR y escribe el código",26,OemTheme.TEXT),320,124,436,72,26);
        String code=step==Step.PAIRED?"Conectado":pairingCode.length()==8?pairingCode.substring(0,4)+" "+pairingCode.substring(4):"Esperando código…";
        TextView digits=label(code,pairingCode.length()==8?64:42,OemTheme.ACTION);digits.setTypeface(OemTheme.medium());put(digits,320,210,436,88,pairingCode.length()==8?64:42);
        put(label("El acceso termina al salir de esta pantalla.",23,OemTheme.SECONDARY),320,310,436,66,23);
        put(label(publicUrl,22,OemTheme.TEXT),40,398,716,32,22);
        addCountdown();clock.bringToFront();
    }
    private void renderManual(){
        put(label("¿Probar esta cámara?",38,OemTheme.TEXT),44,142,712,64,38);
        put(label("El teléfono solicita una prueba temporal\nde vídeo y sonido en este reloj.",28,OemTheme.SECONDARY),44,224,712,108,28);
        Button yes=button("Probar ahora"),no=button("Cancelar");yes.setOnClickListener(v->manualDecision(true));no.setOnClickListener(v->manualDecision(false));put(yes,44,350,340,72,30);put(no,416,350,340,72,30);addCountdown();clock.bringToFront();
    }
    private void manualDecision(boolean approve){String id=manualId;long revision=manualRevision;if(id==null)return;manualId=null;manualRevision=0;render();listener.onManualTestDecision(id,revision,approve&&!closed&&active&&sessionOwned&&live()&&expiry>SystemClock.elapsedRealtime());}
    private void addCountdown(){ttl=label("",21,OemTheme.SECONDARY);put(ttl,32,455,736,25,21);if(active&&live()&&expiry>0)countdown.run();}
    private void clearSession(){publicUrl="";pairingCode="";expiry=0;ttl=null;manualId=null;manualRevision=0;main.removeCallbacks(countdown);}
    private void requestStop(){
        main.removeCallbacks(countdown);String rejected=manualId;long revision=manualRevision;clearSession();
        if(rejected!=null)listener.onManualTestDecision(rejected,revision,false);
        if(sessionOwned&&!stopSent){stopSent=true;sessionOwned=false;listener.onStop();}
    }
    private void fail(){requestStop();step=Step.FAILED;render();}
    private TextView label(String text,float size,int color){return OemTheme.label(getContext(),text,size,color);}
    private Button button(String text){Button button=new Button(getContext());button.setText(text);button.setAllCaps(false);button.setTypeface(OemTheme.regular());button.setTextColor(OemTheme.ACTION);button.setBackground(OemTheme.surface(OemTheme.CONTROL,16));button.setMinHeight(0);button.setMinimumHeight(0);button.setMinWidth(0);button.setMinimumWidth(0);button.setPadding(8,0,8,0);return button;}
    private Button icon(String glyph,String description){Button result=button(glyph);result.setTypeface(OemTheme.icons());result.setContentDescription(description);result.setTextColor(OemTheme.TEXT);return result;}
    @Override protected void onActiveChanged(boolean value){if(!value){requestStop();step=Step.STOPPED;}render();}
    /** Programmatic close stops only; it never emits onBack or reopens anything. */
    @Override public void close(){requireMain();if(closed)return;closed=true;requestStop();super.release();clearSession();removeAllViews();}
    @Override public void release(){close();}
    private static void requireMain(){if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("adminweb_main_thread_required");}

    private static final class QrView extends View {
        private final QrCode qr;private final Paint paint=new Paint();
        QrView(Context context,String publicUrl){super(context);qr=QrCode.encodeSegments(QrSegment.makeSegments(publicUrl),QrCode.Ecc.MEDIUM,1,10,-1,true);paint.setAntiAlias(false);setContentDescription("QR con dirección pública local, sin código de emparejamiento");}
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);canvas.drawColor(Color.WHITE);int scale=Math.min(getWidth(),getHeight())/(qr.size+8);if(scale<1)return;
            int left=(getWidth()-(qr.size+8)*scale)/2+4*scale,top=(getHeight()-(qr.size+8)*scale)/2+4*scale;paint.setColor(Color.BLACK);
            for(int y=0;y<qr.size;y++)for(int x=0;x<qr.size;x++)if(qr.getModule(x,y))canvas.drawRect(left+x*scale,top+y*scale,left+(x+1)*scale,top+(y+1)*scale,paint);
        }
    }
}
