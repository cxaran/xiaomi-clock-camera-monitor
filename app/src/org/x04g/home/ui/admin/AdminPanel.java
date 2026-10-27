package org.x04g.home.ui.admin;

import android.content.Context;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.text.*;
import android.text.method.PasswordTransformationMethod;
import android.util.TypedValue;
import android.view.*;
import android.widget.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import org.x04g.home.config.*;
import org.x04g.home.ui.OemTheme;
import org.x04g.home.ui.PageView;

/** Private native editor. Root owns the single decoder and pauses the normal camera page first. */
public final class AdminPanel extends PageView implements AutoCloseable {
    public interface PreviewResult { void complete(boolean verified,String fixedCode); }
    public interface Listener {
        void onClose();
        void onPreview(CameraDraft draft,char[] passwordOrNull,Surface surface,PreviewResult callback);
        void onEndPreview();
        default void onWebAdminRequired(){}
    }
    private interface Job<T>{T run()throws Exception;}
    private interface Done<T>{void run(T result);}
    private final ConfigRepository repository;
    private final Listener listener;
    private final ExecutorService worker=new ThreadPoolExecutor(1,1,0L,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<Runnable>(2));
    private ConfigSnapshot snapshot;
    private boolean closed,busy,editor,verified,previewRunning,building,stopInProgress;
    private long generation,previewGeneration,verifiedAt;
    private TextView message;
    private Button closeButton,saveButton,previewButton;
    private EditText label,host,port,path,username,password,mac,uuid,focused;
    private CameraDraft original;
    private TextureView texture;
    private Surface surface;
    private FrameLayout previewBox;
    private View keyboard;
    private char[] previewSecret;

    public AdminPanel(Context context,ConfigRepository repository,Listener listener) {
        super(context);if(repository==null||listener==null)throw new IllegalArgumentException("admin_dependencies");
        this.repository=repository;this.listener=listener;setSaveEnabled(false);setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
    }
    public void open(){if(closed)return;setActive(true);reload();}
    /** Only called after the user approves a specific phone request on this clock. */
    public void openCameraForTest(final String id,final long expectedRevision){
        if(closed)return;setActive(true);shell("Probar cámara");
        run(()->repository.load(),value->{snapshot=value;CameraConfig camera=value.find(id);
            if(value.revision!=expectedRevision||camera==null){showList();say("La configuración cambió. Vuelve a solicitar la prueba.");return;}
            edit(camera);preview();
        });
    }
    @Override public void close(){finish(true);}
    private void finish(boolean notify){
        if(closed)return;closed=true;generation++;stopPreview();worker.shutdownNow();hideKeyboard();building=true;if(password!=null)password.setText("");setActive(false);
        if(notify)listener.onClose();
    }
    @Override protected void onActiveChanged(boolean value){if(!value)stopPreview();}
    @Override protected void onDetachedFromWindow(){finish(false);super.onDetachedFromWindow();}
    @Override public boolean dispatchTouchEvent(MotionEvent event){
        boolean handled=super.dispatchTouchEvent(event);
        if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(event.getActionMasked()!=MotionEvent.ACTION_UP&&event.getActionMasked()!=MotionEvent.ACTION_CANCEL);
        return handled;
    }
    private void shell(String title){
        hideKeyboard();stopPreview();removeAllViews();clock.setGravity(Gravity.START|Gravity.TOP);put(clock,40,38,156,44,36);
        // Keep the entire 48dp close target outside the OEM-owned 36px right edge on X04G.
        closeButton=button("\ue5cd");closeButton.setTypeface(OemTheme.icons());closeButton.setTextColor(OemTheme.TEXT);closeButton.setBackground(OemTheme.surface(OemTheme.CONTROL,1000));closeButton.setIncludeFontPadding(true);closeButton.setMinWidth(0);closeButton.setMinimumWidth(0);closeButton.setContentDescription("Cerrar administración");closeButton.setOnClickListener(v->close());put(closeButton,688,18,72,72,44);
        put(OemTheme.label(getContext(),title,32,OemTheme.TEXT),224,36,440,48,32);
        message=OemTheme.label(getContext(),"",21,OemTheme.SECONDARY);message.setMaxLines(2);put(message,28,432,744,44,21);clock.bringToFront();
    }
    private void reload(){
        editor=false;shell("Administrar cámaras");run(()->repository.load(),value->{snapshot=value;showList();});
    }
    private void showList(){
        editor=false;shell("Administrar cámaras");
        Button add=button("Añadir");add.setOnClickListener(v->{if(!busy)edit(null);});put(add,28,94,180,54,26);add.setEnabled(snapshot.cameras.size()<ConfigRules.MAX_CAMERAS);
        put(OemTheme.label(getContext(),snapshot.cameras.size()+" / "+ConfigRules.MAX_CAMERAS,24,OemTheme.SECONDARY),228,107,180,36,24);
        Button phone=button("Desde el teléfono");phone.setOnClickListener(v->{if(!busy)listener.onWebAdminRequired();});put(phone,454,94,318,54,25);
        ScrollView scroll=new ScrollView(getContext());LinearLayout rows=column();scroll.addView(rows);put(scroll,28,158,744,264,0);
        if(snapshot.cameras.isEmpty())rows.addView(OemTheme.label(getContext(),"Añade una cámara o importa una configuración local.",25,OemTheme.SECONDARY));
        for(int n=0;n<snapshot.cameras.size();n++){
            final CameraConfig camera=snapshot.cameras.get(n);final int index=n;LinearLayout row=new LinearLayout(getContext());row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(8,6,8,6);
            LinearLayout text=column();TextView name=OemTheme.label(getContext(),camera.label,28,OemTheme.TEXT);text.addView(name);
            text.addView(OemTheme.label(getContext(),!camera.enabled?"Oculta":camera.needsVerification?"Pendiente de verificar":"Probada anteriormente",19,OemTheme.SECONDARY));
            row.addView(text,new LinearLayout.LayoutParams(0,80,1));
            Button up=button("↑"),down=button("↓"),edit=button("Editar"),remove=button("Quitar");
            up.setEnabled(n>0);down.setEnabled(n+1<snapshot.cameras.size());up.setContentDescription("Subir "+camera.label);down.setContentDescription("Bajar "+camera.label);
            up.setOnClickListener(v->move(index,-1));down.setOnClickListener(v->move(index,1));edit.setOnClickListener(v->{if(!busy)edit(camera);});remove.setOnClickListener(v->{if(!busy)confirmRemove(camera);});
            row.addView(up,new LinearLayout.LayoutParams(56,60));row.addView(down,new LinearLayout.LayoutParams(56,60));row.addView(edit,new LinearLayout.LayoutParams(120,60));row.addView(remove,new LinearLayout.LayoutParams(120,60));rows.addView(row);
        }
        say("La disponibilidad se comprueba al reproducir; la lista no inicia cámaras.");
    }
    private void move(int index,int delta){if(busy)return;List<String> ids=new ArrayList<String>();for(CameraConfig c:snapshot.cameras)ids.add(c.id);Collections.swap(ids,index,index+delta);
        run(()->repository.reorder(ids,snapshot.revision),value->{snapshot=value;showList();});}
    private void confirmRemove(final CameraConfig camera){
        shell("Quitar cámara");put(OemTheme.label(getContext(),"¿Quitar "+camera.label+" de este reloj?",32,OemTheme.TEXT),48,160,704,72,32);
        put(OemTheme.label(getContext(),"No modifica la cámara ni su cuenta Tapo.",25,OemTheme.SECONDARY),48,242,704,56,25);
        Button no=button("Cancelar"),yes=button("Quitar");no.setOnClickListener(v->{if(!busy)showList();});yes.setOnClickListener(v->{if(!busy)run(()->repository.remove(camera.id,snapshot.revision),value->{snapshot=value;showList();});});put(no,160,338,220,64,28);put(yes,420,338,220,64,28);
    }
    private void edit(CameraConfig camera){
        focused=null;original=camera==null?new CameraDraft():new CameraDraft(camera);editor=true;verified=false;verifiedAt=0;
        shell(camera==null?"Añadir cámara":"Editar cámara");building=true;
        ScrollView scroll=new ScrollView(getContext());LinearLayout form=column();scroll.addView(form);put(scroll,396,160,376,262,0);
        label=field(form,"Nombre",original.label,InputType.TYPE_CLASS_TEXT,false);
        host=field(form,"IP o nombre local",original.host,InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,false);
        port=field(form,"Puerto",Integer.toString(original.port),InputType.TYPE_CLASS_NUMBER,false);
        path=field(form,"Ruta RTSP",original.path,InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,false);
        username=field(form,"Usuario local",original.username,InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,false);
        password=field(form,camera==null?"Contraseña local":"Nueva contraseña (vacío conserva)","",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD,true);
        mac=field(form,"MAC (opcional)",original.mac,InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,false);
        uuid=field(form,"UUID ONVIF (opcional)",original.onvifUuid,InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,false);
        CheckBox enabled=new CheckBox(getContext());enabled.setText("Mostrar en el selector");enabled.setTextColor(OemTheme.TEXT);enabled.setTypeface(OemTheme.regular());enabled.setChecked(original.enabled);enabled.setOnCheckedChangeListener((b,value)->{original.enabled=value;invalidatePreview();});form.addView(enabled);
        Button keys=button("Teclado"),discover=button("Buscar en LAN");keys.setOnClickListener(v->{if(!busy)showKeyboard(focused==null?host:focused);});discover.setOnClickListener(v->{if(!busy)discover();});put(keys,396,100,178,50,24);put(discover,590,100,182,50,24);
        previewBox=new FrameLayout(getContext());previewBox.setBackground(OemTheme.surface(OemTheme.CONTROL,16));TextView hint=OemTheme.label(getContext(),"Vista previa al probar",23,OemTheme.SECONDARY);hint.setGravity(Gravity.CENTER);previewBox.addView(hint,new FrameLayout.LayoutParams(-1,-1));put(previewBox,28,154,336,189,0);
        previewButton=button("Probar vídeo y sonido");previewButton.setOnClickListener(v->{if(!busy)preview();});put(previewButton,28,352,336,54,24);
        saveButton=button("Guardar");saveButton.setEnabled(false);saveButton.setOnClickListener(v->{if(!busy)save(false);});put(saveButton,28,420,146,48,24);
        Button pending=button("Guardar pendiente");pending.setOnClickListener(v->{if(!busy)save(true);});put(pending,186,420,178,48,20);
        // Editor status stays above the preview, leaving bottom buttons unobstructed.
        removeView(message);put(message,28,100,336,48,17);building=false;
        say("Prueba antes de guardar, o guarda explícitamente pendiente.");
    }
    private EditText field(LinearLayout form,String title,String value,int type,boolean secret){
        TextView titleView=OemTheme.label(getContext(),title,20,OemTheme.SECONDARY);titleView.setPadding(0,8,0,0);form.addView(titleView);
        EditText input=new EditText(getContext());input.setSingleLine(true);input.setInputType(type);input.setShowSoftInputOnFocus(false);input.setTextColor(OemTheme.TEXT);input.setHintTextColor(OemTheme.SECONDARY);input.setTextSize(TypedValue.COMPLEX_UNIT_PX,24);input.setTypeface(OemTheme.regular());input.setIncludeFontPadding(false);input.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);input.setPadding(12,0,12,0);input.setSaveEnabled(false);input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);input.setSelectAllOnFocus(false);
        if(secret)input.setTransformationMethod(PasswordTransformationMethod.getInstance());input.setText(value);input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(secret?512:512)});
        input.setOnFocusChangeListener((v,has)->{if(has)focused=input;});input.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){if(!building)invalidatePreview();}public void afterTextChanged(Editable e){}});
        form.addView(input,new LinearLayout.LayoutParams(-1,56));return input;
    }
    private CameraDraft candidate()throws ConfigException {
        CameraDraft d=new CameraDraft();d.id=original.id;d.label=label.getText().toString();d.host=host.getText().toString();d.path=path.getText().toString();d.username=username.getText().toString();d.mac=mac.getText().toString();d.onvifUuid=uuid.getText().toString();d.model=original.model;d.enabled=original.enabled;
        try{d.port=Integer.parseInt(port.getText().toString());}catch(NumberFormatException e){throw new ConfigException(ConfigException.Code.INVALID_INPUT);}
        d.needsVerification=!verified;d.verifiedAtMillis=verified?verifiedAt:0;
        CameraConfig normalized=ConfigRules.validate(d,true);d=new CameraDraft(normalized);original.id=d.id;return d;
    }
    private char[] typedPassword(){Editable text=password.getText();if(text.length()==0)return null;char[] result=new char[text.length()];text.getChars(0,text.length(),result,0);return result;}
    private char[] checkedPassword(CameraDraft draft)throws ConfigException{char[] value=typedPassword();try{if(value!=null)ConfigRules.password(value);else if(snapshot.find(draft.id)==null)throw new ConfigException(ConfigException.Code.INVALID_INPUT);return value;}catch(ConfigException e){if(value!=null)Arrays.fill(value,'\0');throw e;}}
    private void invalidatePreview(){if(building)return;verified=false;verifiedAt=0;if(saveButton!=null)saveButton.setEnabled(false);stopPreview();say("Cambios sin verificar.");}
    private void preview(){
        stopPreview();final CameraDraft d;final char[] secret;
        try{d=candidate();secret=checkedPassword(d);}catch(ConfigException e){say(error(e));return;}
        final long ticket=++previewGeneration;previewSecret=secret;previewRunning=true;verified=false;saveButton.setEnabled(false);say("Preparando vista previa…");
        previewBox.removeAllViews();texture=new TextureView(getContext());previewBox.addView(texture,new FrameLayout.LayoutParams(-1,-1));
        texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener(){
            public void onSurfaceTextureAvailable(SurfaceTexture image,int w,int h){if(closed||ticket!=previewGeneration)return;surface=new Surface(image);say("Conectando a la cámara…");
                try{listener.onPreview(d,secret,surface,(ok,code)->main.post(()->{
                    if(ticket!=previewGeneration||closed)return;wipePreviewSecret();verified=ok;verifiedAt=ok?System.currentTimeMillis():0;saveButton.setEnabled(ok);say(ok?"Vista previa verificada. Puedes guardar.":previewError(code));
                }));}catch(RuntimeException e){stopPreview();say("No se pudo iniciar la vista previa.");}
            }
            public void onSurfaceTextureSizeChanged(SurfaceTexture image,int w,int h){}
            public boolean onSurfaceTextureDestroyed(SurfaceTexture image){stopPreview();return true;}
            public void onSurfaceTextureUpdated(SurfaceTexture image){}
        });
    }
    private void stopPreview(){
        if(stopInProgress)return;stopInProgress=true;previewGeneration++;
        try{if(previewRunning){previewRunning=false;try{listener.onEndPreview();}catch(RuntimeException ignored){say("Vista previa detenida sin confirmación del reproductor.");}}if(surface!=null){surface.release();surface=null;}if(texture!=null){TextureView old=texture;texture=null;old.setSurfaceTextureListener(null);if(old.getParent() instanceof android.view.ViewGroup)((android.view.ViewGroup)old.getParent()).removeView(old);}wipePreviewSecret();}
        finally{stopInProgress=false;}
    }
    private void wipePreviewSecret(){if(previewSecret!=null){Arrays.fill(previewSecret,'\0');previewSecret=null;}}
    private void save(final boolean pending){
        final CameraDraft d;final char[] secret;
        try{if(!pending&&!verified){say("Primero prueba la cámara.");return;}d=candidate();if(pending){d.needsVerification=true;d.verifiedAtMillis=0;}secret=checkedPassword(d);}
        catch(ConfigException e){say(error(e));return;}
        stopPreview();password.setText("");final long revision=snapshot.revision;
        run(()->{try{return repository.upsert(d,secret,revision);}finally{if(secret!=null)Arrays.fill(secret,'\0');}},value->{snapshot=value;showList();say(pending?"Guardada pendiente de verificar.":"Configuración guardada.");});
    }
    private void discover(){
        invalidatePreview();say("Buscando una interfaz LAN…");run(()->{
            List<Inet4Address> addresses=new ArrayList<Inet4Address>();Enumeration<NetworkInterface> nics=NetworkInterface.getNetworkInterfaces();
            while(nics!=null&&nics.hasMoreElements()){NetworkInterface nic=nics.nextElement();if(!nic.isUp()||nic.isLoopback())continue;Enumeration<InetAddress> all=nic.getInetAddresses();while(all.hasMoreElements()){InetAddress a=all.nextElement();if(a instanceof Inet4Address&&a.isSiteLocalAddress())addresses.add((Inet4Address)a);}}return addresses;
        },addresses->{if(addresses.isEmpty()){say("Sin interfaz LAN disponible.");return;}if(addresses.size()==1)discoverOn(addresses.get(0));else chooseInterface(addresses);});
    }
    private void chooseInterface(List<Inet4Address> addresses){
        final LinearLayout list=column();final ScrollView overlay=overlay("Selecciona la red local",list);for(Inet4Address address:addresses){Button b=button(address.getHostAddress());b.setOnClickListener(v->{removeView(overlay);discoverOn(address);});list.addView(b,new LinearLayout.LayoutParams(-1,62));}
    }
    private void discoverOn(final Inet4Address local){
        final long epoch=generation+1;say("Buscando ONVIF, hasta 3 segundos…");run(()->OnvifDiscovery.discover(local,()->closed||epoch!=generation),found->{
            if(found.isEmpty()){say("Sin candidatos ONVIF. Puedes configurar la IP manualmente.");return;}
            LinearLayout list=column();final ScrollView overlay=overlay("Candidatas: identidad aún sin verificar",list);
            for(final OnvifDiscovery.Candidate c:found){Button b=button(c.host+"  ·  "+c.onvifUuid.substring(9,17));b.setOnClickListener(v->{removeView(overlay);host.setText(c.host);uuid.setText(c.onvifUuid);if(!c.advertisedMac.isEmpty())mac.setText(c.advertisedMac);say("Candidata elegida; prueba vídeo y sonido antes de guardar.");});list.addView(b,new LinearLayout.LayoutParams(-1,66));}
        });
    }
    private ScrollView overlay(String title,LinearLayout content){
        ScrollView overlay=new ScrollView(getContext());overlay.setBackgroundColor(OemTheme.BACKGROUND);overlay.setElevation(16*getResources().getDisplayMetrics().density);LinearLayout all=column();all.setPadding(24,24,24,24);all.addView(OemTheme.label(getContext(),title,26,OemTheme.TEXT));Button cancel=button("Cerrar");cancel.setOnClickListener(v->removeView(overlay));all.addView(cancel,new LinearLayout.LayoutParams(-1,56));all.addView(content);overlay.addView(all);put(overlay,24,96,752,326,0);overlay.bringToFront();return overlay;
    }
    private void showKeyboard(final EditText target){
        hideKeyboard();stopPreview();focused=target;target.requestFocus();target.setCursorVisible(false);
        final LinearLayout keys=column();keys.setPadding(8,4,8,4);keys.setBackgroundColor(OemTheme.CONTROL);keys.setElevation(16*getResources().getDisplayMetrics().density);keyboard=keys;put(keys,20,100,760,320,0);keys.bringToFront();
        final boolean[] upper={false};final String[] mode={"abc"};final Runnable[] render=new Runnable[1];final TextView[] display=new TextView[1];
        final Runnable refresh=()->{if(display[0]!=null){if(target==password){char[] dots=new char[Math.min(32,target.length())];Arrays.fill(dots,'•');display[0].setText(new String(dots));}else display[0].setText(target.getText().toString());}};
        render[0]=()->{keys.removeAllViews();TextView title=OemTheme.label(getContext(),"Teclado local · "+(target==password?"contraseña oculta":"edición"),22,OemTheme.TEXT);keys.addView(title);display[0]=OemTheme.label(getContext(),"",24,OemTheme.TEXT);display[0].setSingleLine(true);keys.addView(display[0],new LinearLayout.LayoutParams(-1,36));refresh.run();
            String[] rows=mode[0].equals("123")?new String[]{"1234567890",".-_:/@%+?=","!#$&*()[]{}"}:mode[0].equals("símbolos")?new String[]{"<>;,'\"\\|~`","áéíóúüñ"}:new String[]{"qwertyuiop","asdfghjklñ","zxcvbnm"};
            for(String row:rows){LinearLayout line=new LinearLayout(getContext());for(int i=0;i<row.length();i++){final String character=upper[0]?row.substring(i,i+1).toUpperCase(Locale.ROOT):row.substring(i,i+1);Button key=button(character);key.setTextSize(TypedValue.COMPLEX_UNIT_PX,20);key.setMinWidth(0);key.setPadding(0,0,0,0);key.setOnClickListener(v->{insert(target,character);refresh.run();});line.addView(key,new LinearLayout.LayoutParams(0,52,1));}keys.addView(line);}
            LinearLayout controls=new LinearLayout(getContext());String[] labels={"ABC","123","símbolos","⇧","Espacio","←","Listo"};
            for(final String caption:labels){Button b=button(caption);b.setTextSize(TypedValue.COMPLEX_UNIT_PX,15);b.setMinWidth(0);b.setPadding(0,0,0,0);b.setOnClickListener(v->{if(caption.equals("Listo"))hideKeyboard();else if(caption.equals("←")){int a=target.getSelectionStart(),z=target.getSelectionEnd();if(a<0||z<0)a=z=target.length();int start=Math.min(a,z),end=Math.max(a,z);if(start==end&&start>0)start--;target.getText().delete(start,end);}else if(caption.equals("Espacio"))insert(target," ");else if(caption.equals("⇧")){upper[0]=!upper[0];render[0].run();}else{mode[0]=caption.equals("ABC")?"abc":caption;render[0].run();}refresh.run();});controls.addView(b,new LinearLayout.LayoutParams(0,56,1));}keys.addView(controls);
        };render[0].run();
    }
    private static void insert(EditText input,String text){int a=input.getSelectionStart(),b=input.getSelectionEnd();if(a<0)a=b=input.length();input.getText().replace(Math.min(a,b),Math.max(a,b),text);}
    private void hideKeyboard(){if(keyboard!=null){removeView(keyboard);keyboard=null;if(focused!=null)focused.setCursorVisible(true);}}
    private <T> void run(final Job<T> job,final Done<T> done){
        if(closed||busy)return;busy=true;final long epoch=++generation;
        try{worker.execute(()->{try{final T value=job.run();main.post(()->{if(closed||epoch!=generation)return;busy=false;done.run(value);});}catch(final Exception e){main.post(()->{if(closed||epoch!=generation)return;busy=false;say(e instanceof ConfigException?error((ConfigException)e):"No se pudo completar la operación.");});}});}
        catch(RejectedExecutionException e){busy=false;say("Espera a que termine la operación actual.");}
    }
    private void say(String text){if(message!=null)message.setText(text);}
    private static String error(ConfigException e){switch(e.code){case INVALID_INPUT:return "Revisa nombre, IP local, puerto, ruta y credenciales.";case CONFLICT:return "La configuración cambió. Cierra y vuelve a abrir.";case LIMIT:return "Se alcanzó el límite de cámaras.";case SECRET_UNAVAILABLE:return "No se puede abrir el almacén cifrado.";case CORRUPT:return "La configuración no es válida; no se sobrescribió.";case NOT_FOUND:return "La cámara ya no existe.";case CANCELLED:return "Operación cancelada.";default:return "No se pudo guardar o leer la configuración.";}}
    private static String previewError(String code){
        if(code==null)return "Vista previa no verificada. Puedes guardar pendiente.";
        switch(code){
            case "AUTHENTICATION":return "Revisa el usuario y la contraseña locales.";
            case "AUTH_UNSUPPORTED":return "Autenticación RTSP no compatible.";
            case "INVALID_CONFIGURATION":case "CONFIGURATION":return "Revisa los datos locales de la cámara.";
            case "CONNECTION":return "No se pudo conectar con la cámara.";
            case "PREVIEW_TIMEOUT":case "TIMEOUT":return "Tiempo de espera agotado. Puedes guardar pendiente.";
            case "UNSUPPORTED_STREAM":return "Formato de vídeo o audio no compatible.";
            case "VIDEO_CODEC":return "No se pudo iniciar el decodificador de vídeo.";
            case "AUDIO_OUTPUT":return "No se pudo iniciar la salida de audio.";
            case "SURFACE_LOST":return "La superficie de vídeo dejó de estar disponible.";
            case "FIRST_FRAME_TIMEOUT":return "La cámara no entregó el primer fotograma.";
            case "VIDEO_STALLED":return "El vídeo dejó de avanzar.";
            case "FRAME_TOO_LARGE":return "La cámara envió un fotograma demasiado grande.";
            case "PROTOCOL":return "La respuesta RTSP no es compatible.";
            case "AUDIO_BUSY":return "El audio está ocupado por otra aplicación.";
            case "MEMORY_PRESSURE":return "Vista previa pausada por memoria insuficiente.";
            case "BUSY":return "Hay otra operación pendiente. Vuelve a probar.";
            case "PREVIEW_INTERRUPTED":return "La vista previa se interrumpió.";
            case "PREVIEW_UNAVAILABLE":return "Vista previa no disponible. Vuelve a abrir la cámara.";
            case "INTERNAL_ERROR":return "No se pudo completar la vista previa.";
            default:return "Vista previa no verificada. Puedes guardar pendiente.";
        }
    }
    private LinearLayout column(){LinearLayout l=new LinearLayout(getContext());l.setOrientation(LinearLayout.VERTICAL);return l;}
    private Button button(String text){Button b=new Button(getContext());b.setText(text);b.setAllCaps(false);b.setTypeface(OemTheme.regular());b.setTextColor(OemTheme.ACTION);b.setBackground(OemTheme.surface(OemTheme.CONTROL,16));b.setMinHeight(0);b.setMinimumHeight(0);b.setIncludeFontPadding(false);b.setGravity(Gravity.CENTER);b.setPadding(0,0,0,0);return b;}
}
