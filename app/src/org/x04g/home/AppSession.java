package org.x04g.home;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import android.widget.FrameLayout;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.x04g.home.camera.*;
import org.x04g.home.config.*;
import org.x04g.home.gate.*;
import org.x04g.home.ui.*;
import org.x04g.home.ui.admin.AdminPanel;

/** Owns every resource for one OEM Activity. Only the selected visible page may run IO. */
public final class AppSession implements CameraPage.Listener,GatePage.Listener,AutoCloseable {
    public static final String GATE_ID="x04g.home.gate.v1",CAMERA_ID="x04g.home.cameras.v1";
    private final Activity activity;private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=newIo();
    private final RtspCameraPlayer player;private final AtomicBoolean gateBusy=new AtomicBoolean();
    private final AudioManager audioManager;private final AudioFocusRequest audioFocus;
    private GatePage gateView;private CameraPage cameraView;private Surface cameraSurface;
    private volatile TuyaGateClient gate;private volatile GateProfile gateProfile;private ConfigRepository repository;private ConfigSnapshot snapshot;
    private String selectedCamera="",selectedPage="";private boolean cameraVisible,gateVisible,adminOpen,uncertain;
    private volatile boolean closed;private boolean resumed,memoryPaused,cameraSoundRequested,previewActive,focusRequested,focusGranted;
    private AlertDialog uncertainDialog;
    private volatile String cameraProvision="NOT_REQUESTED",gateProvision="NOT_REQUESTED";
    private volatile String lastPreviewCode="NOT_REQUESTED";
    private enum GateIntentState { NOT_REQUESTED, NOT_CONFIGURED, CLOSED, NOT_VISIBLE, UNCERTAIN,
        OPERATION_BUSY, STATUS_NOT_READY, ACCEPTED, IO_REJECTED }
    private static final class GateIntent {
        final long sequence;final GateIntentState state;
        GateIntent(long sequence,GateIntentState state){this.sequence=sequence;this.state=state;}
    }
    private static final class GateControlEvent {
        final long intent;final TuyaGateClient.ControlResult result;
        GateControlEvent(long intent,TuyaGateClient.ControlResult result){this.intent=intent;this.result=result;}
    }
    private long gateIntentSequence;
    private volatile GateIntent lastGateIntent;
    private volatile GateControlEvent lastGateControlResult;
    private volatile long cameraGeneration;private AdminPanel admin;private AdminPanel.PreviewResult previewResult;private volatile long previewGeneration;
    private AdminWebSession webAdmin;private boolean switchingAdmin;
    private boolean cameraPlaying;private Runnable taskStateListener;
    private RtspCameraPlayer.State streamState=RtspCameraPlayer.State.STOPPED;
    private RtspCameraPlayer.ErrorCode streamError=RtspCameraPlayer.ErrorCode.NONE;
    private final Object gateLock=new Object();
    private static ExecutorService newIo(){ThreadPoolExecutor executor=new ThreadPoolExecutor(1,1,10L,TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(6));executor.allowCoreThreadTimeOut(true);return executor;}
    /** Main-thread observer; it never creates a polling timer or keeps an inactive page alive. */
    public void setTaskStateListener(Runnable listener){taskStateListener=listener;taskStateChanged();}
    private void taskStateChanged(){if(taskStateListener!=null)taskStateListener.run();}
    public boolean keepsHomeVisible(){return !closed&&resumed&&CAMERA_ID.equals(selectedPage)&&(adminOpen&&(admin!=null||webAdmin!=null&&webAdmin.taskActive())||cameraVisible&&cameraPlaying&&!adminOpen&&!memoryPaused);}
    public void pageDestroyed(String id){if(id!=null&&id.equals(selectedPage))selected("",false);}
    private final Runnable poll=new Runnable(){public void run(){if(!closed&&gateVisible){queryGate();main.postDelayed(this,15000);}}};
    public AppSession(Activity a){activity=a;audioManager=(AudioManager)a.getSystemService(Context.AUDIO_SERVICE);audioFocus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).setAcceptsDelayedFocusGain(false).setWillPauseWhenDucked(true).setOnAudioFocusChangeListener(change->audioFocusChanged(change),main).build();player=new RtspCameraPlayer(new RtspCameraPlayer.Listener(){
        public void onState(RtspCameraPlayer.State s,RtspCameraPlayer.ErrorCode e){if(closed)return;streamState=s;streamError=e;cameraPlaying=!previewActive&&cameraVisible&&!adminOpen&&!memoryPaused&&s==RtspCameraPlayer.State.PLAYING;taskStateChanged();if(previewActive){if(s==RtspCameraPlayer.State.ERROR){finishPreview(false,e.name());endPreview();}return;}if(cameraView==null||!cameraVisible||adminOpen||memoryPaused)return;String text=s==RtspCameraPlayer.State.CONNECTING?"Conectando…":s==RtspCameraPlayer.State.BUFFERING?"Esperando imagen…":s==RtspCameraPlayer.State.ERROR?cameraError(e):"";cameraView.showStreamState(text,s==RtspCameraPlayer.State.PLAYING);if(s==RtspCameraPlayer.State.ERROR)releaseAudioFocus();}
        public void onVideoSize(int w,int h){if(cameraView!=null)cameraView.setVideoSize(w,h);}
        public void onAudioAvailability(boolean available,RtspCameraPlayer.ErrorCode e){}
        public void onStats(RtspCameraPlayer.Stats s){taskStateChanged();if(previewResult!=null&&s.framesRendered>0&&s.audioAvailable&&s.audioSamplesWritten>0)finishPreview(true,"VIDEO_AUDIO_OK");}
    });loadConfiguration();}
    public GatePage gateView(){if(gateView==null)gateView=new GatePage(activity,this);return gateView;}
    public CameraPage cameraView(){if(cameraView==null){cameraView=new CameraPage(activity,this);updateChoices();}return cameraView;}
    public void selected(String id,boolean value){if(closed)return;selectedPage=id==null?"":id;resumed=value;boolean adminVisible=value&&CAMERA_ID.equals(selectedPage);if(adminOpen&&!adminVisible){finishPreview(false,"PREVIEW_INTERRUPTED");endPreview();if(admin!=null)admin.setActive(false);}else if(adminOpen&&admin!=null)admin.setActive(true);if(webAdmin!=null)webAdmin.setActive(adminVisible);if(gateView!=null)gateView.setActive(value&&GATE_ID.equals(selectedPage)&&!adminOpen);if(cameraView!=null)cameraView.setActive(value&&CAMERA_ID.equals(selectedPage)&&!adminOpen);taskStateChanged();}
    private boolean submitIo(Runnable job){if(closed)return false;try{io.execute(job);return true;}catch(RejectedExecutionException e){return false;}}
    private void loadConfiguration(){submitIo(new Runnable(){public void run(){try{final ConfigRepository r=new ConfigRepository(activity);final ConfigSnapshot s=r.load();main.post(new Runnable(){public void run(){if(closed)return;repository=r;snapshot=s;updateChoices();if(cameraVisible)startSelected();}});}catch(Exception e){main.post(new Runnable(){public void run(){if(!closed&&cameraView!=null)cameraView.showStreamState("Configuración no disponible",false);}});}}});}
    private void updateChoices(){if(cameraView==null)return;List<CameraPage.Choice> list=new ArrayList<CameraPage.Choice>();if(snapshot!=null){for(CameraConfig c:snapshot.cameras)if(c.enabled&&!c.needsVerification)list.add(new CameraPage.Choice(c.id,c.label));}boolean found=false;for(CameraPage.Choice c:list)if(c.id.equals(selectedCamera))found=true;if(!found)selectedCamera=list.isEmpty()?"":list.get(0).id;cameraView.setCameras(list,selectedCamera);if(list.isEmpty()){boolean pending=false;if(snapshot!=null)for(CameraConfig c:snapshot.cameras)if(c.enabled&&c.needsVerification)pending=true;cameraView.showStreamState(pending?"Cámaras pendientes de probar\nMantén pulsado para administrar":"Agrega una cámara",false);}}
    @Override public void onCameraVisible(boolean value){cameraVisible=value;cameraGeneration++;cameraPlaying=false;if(!value){cameraSurface=null;player.stop();releaseAudioFocus();}else{memoryPaused=false;startSelected();}taskStateChanged();}
    @Override public void onSurfaceReady(Surface s){cameraSurface=s;startSelected();}
    @Override public void onSurfaceGone(){cameraSurface=null;cameraGeneration++;cameraPlaying=false;taskStateChanged();if(!adminOpen){player.stop();releaseAudioFocus();}}
    @Override public void onCameraChosen(String id){selectedCamera=id;cameraGeneration++;memoryPaused=false;startSelected();}
    @Override public void onAudioChanged(boolean enabled){cameraSoundRequested=enabled;if(enabled)requestAudioFocus();else releaseAudioFocus();applyAudio();}
    private void startSelected(){if(closed||!cameraVisible||adminOpen||memoryPaused||cameraSurface==null||!cameraSurface.isValid()||snapshot==null||repository==null)return;if(!memoryAvailable()){pauseForMemory();return;}final CameraConfig c=snapshot.find(selectedCamera);if(c==null||!c.enabled||c.needsVerification)return;final long generation=++cameraGeneration;final Surface target=cameraSurface;cameraPlaying=false;taskStateChanged();player.stop();releaseAudioFocus();cameraView.showStreamState("Conectando…",false);if(!submitIo(new Runnable(){public void run(){char[] password=null;try{if(closed||generation!=cameraGeneration)return;password=repository.readPassword(c.id);final CameraEndpoint endpoint=new CameraEndpoint(c.host,c.port,c.path,c.username,password);main.post(new Runnable(){public void run(){try{if(!closed&&generation==cameraGeneration&&cameraVisible&&!adminOpen&&!memoryPaused&&target.isValid()){if(cameraSoundRequested)requestAudioFocus();applyAudio();player.start(endpoint,target);}}finally{endpoint.close();}}});}catch(Exception e){main.post(new Runnable(){public void run(){if(!closed&&generation==cameraGeneration&&cameraView!=null)cameraView.showStreamState("Revisa la configuración de cámara",false);}});}finally{if(password!=null)Arrays.fill(password,'\0');}}}))cameraView.showStreamState("Espera a que termine la operación",false);}
    @Override public void onGateVisible(boolean value){gateVisible=value;main.removeCallbacks(poll);if(!value){if(uncertainDialog!=null)uncertainDialog.dismiss();uncertainDialog=null;}else if(uncertain)showUncertain();else{if(gate==null)loadGate();poll.run();}}
    private void loadGate(){submitIo(new Runnable(){public void run(){try{GateProfile p=new GateVault(activity).load();if(p==null){main.post(new Runnable(){public void run(){if(!closed&&gateView!=null)gateView.showState("L20 no configurado",false,false);}});return;}GateProfile old;TuyaGateClient oldClient;TuyaGateClient candidate=new TuyaGateClient(p);synchronized(gateLock){if(closed){candidate.close();p.close();return;}old=gateProfile;oldClient=gate;gateProfile=p;gate=candidate;}if(oldClient!=null)oldClient.close();if(old!=null)old.close();main.post(new Runnable(){public void run(){if(!closed&&gateVisible)queryGate();}});}catch(Exception e){main.post(new Runnable(){public void run(){if(!closed&&gateView!=null)gateView.showState("Revisa la configuración L20",false,false);}});}}});}
    private void queryGate(){
        final TuyaGateClient client=gate;
        if(client==null||closed||!gateVisible||uncertain||!gateBusy.compareAndSet(false,true))return;
        gateView.showState("Verificando L20…",false,false);
        if(!submitIo(new Runnable(){public void run(){
            final GateStatus s=client.queryStatus();gateBusy.set(false);
            main.post(new Runnable(){public void run(){
                if(closed||!gateVisible||gate!=client||uncertain)return;
                String text=s.keyConfirmed?"L20 disponible":s.state==GateStatus.State.OFFLINE?"L20 sin conexión":s.tcpConnected?"Revisar acceso L20":"Comprobando…";
                gateView.showState(text,s.keyConfirmed,client.canActuate(),s.state==GateStatus.State.OFFLINE);
            }});
        }})){
            gateBusy.set(false);
            if(!closed&&gateVisible&&gate==client&&!uncertain)gateView.showState("L20 ocupado; comprobación no iniciada.",false,false);
        }
    }
    @Override public void onGateTap(){
        final long intent=gateIntentSequence==Long.MAX_VALUE?Long.MAX_VALUE:++gateIntentSequence;
        final TuyaGateClient client=gate;
        if(client==null){blockedGateIntent(intent,GateIntentState.NOT_CONFIGURED);return;}
        if(closed){blockedGateIntent(intent,GateIntentState.CLOSED);return;}
        if(!gateVisible){blockedGateIntent(intent,GateIntentState.NOT_VISIBLE);return;}
        if(uncertain){blockedGateIntent(intent,GateIntentState.UNCERTAIN);return;}
        if(!client.canActuate()){blockedGateIntent(intent,gateBusy.get()?GateIntentState.OPERATION_BUSY:GateIntentState.STATUS_NOT_READY);return;}
        if(!gateBusy.compareAndSet(false,true)){blockedGateIntent(intent,GateIntentState.OPERATION_BUSY);return;}
        final TuyaGateClient.ManualRequest request=client.manualRequestFromUserTap();
        rememberGateIntent(intent,GateIntentState.ACCEPTED);
        gateView.showState("Enviando…",true,false);
        if(!submitIo(new Runnable(){public void run(){
            final TuyaGateClient.ControlResult result=client.actuateOnce(request);
            rememberGateControl(intent,result);gateBusy.set(false);
            main.post(new Runnable(){public void run(){
                if(closed||gate!=client)return;
                switch(result.outcome){case ACKNOWLEDGED:if(gateVisible)gateView.showState("Solicitud enviada",true,false);break;case UNCERTAIN:uncertain=true;if(gateVisible)showUncertain();break;default:if(gateVisible)gateView.showState("No se confirmó el envío",false,false);break;}
                main.postDelayed(new Runnable(){public void run(){queryGate();}},1000);
            }});
        }})){
            gateBusy.set(false);blockedGateIntent(intent,GateIntentState.IO_REJECTED);
        }
    }
    private static String gateIntentMessage(GateIntentState state){
        if(state==null)return "No se envió ninguna orden.";
        switch(state){
            case NOT_CONFIGURED:return "L20 no configurado; no se envió.";
            case CLOSED:return "Control L20 cerrado; no se envió.";
            case NOT_VISIBLE:return "Abre Portón para accionar; no se envió.";
            case UNCERTAIN:return "Comprueba el portón antes de volver a accionar.";
            case OPERATION_BUSY:case IO_REJECTED:return "L20 ocupado; no se envió.";
            case STATUS_NOT_READY:return "Verifica el enlace L20; no se envió.";
            case ACCEPTED:return "Enviando…";
            default:return "No se envió ninguna orden.";
        }
    }
    private void blockedGateIntent(long sequence,GateIntentState state){
        rememberGateIntent(sequence,state);
        if(!closed&&gateVisible&&gateView!=null)gateView.showState(gateIntentMessage(state),false,false);
    }
    private void rememberGateIntent(long sequence,GateIntentState state){
        GateIntent event=new GateIntent(sequence,state);lastGateIntent=event;
        try{android.util.Log.i("X04GGate",new JSONObject().put("event","INTENT").put("intent",event.sequence).put("state",event.state.name()).toString());}catch(Exception ignored){}
    }
    private void rememberGateControl(long intent,TuyaGateClient.ControlResult result){
        if(result==null)return;
        GateControlEvent event=new GateControlEvent(intent,result);lastGateControlResult=event;
        try{android.util.Log.i("X04GGate",gateControlDiagnostic(event).put("event","RESULT").toString());}catch(Exception ignored){}
    }
    private static JSONObject gateControlDiagnostic(GateControlEvent event)throws Exception{
        JSONObject o=new JSONObject();TuyaGateClient.ControlResult r=event==null?null:event.result;
        o.put("gate_control_last_intent",r==null?0:event.intent);
        o.put("gate_control_last_outcome",r==null?"NOT_REQUESTED":r.outcome.name());
        o.put("gate_control_last_problem",r==null?"NONE":r.problem.name());
        o.put("gate_control_last_profile_verified",r!=null&&r.physicalReplayVerified);
        return o;
    }
    private void showUncertain(){if(closed||!gateVisible||!uncertain||activity.isFinishing()||activity.isDestroyed())return;gateView.showState("Resultado no confirmado",false,false);if(uncertainDialog!=null&&uncertainDialog.isShowing())return;uncertainDialog=new AlertDialog.Builder(activity).setTitle("Resultado no confirmado").setMessage("Comprueba el portón antes de volver a accionar.").setPositiveButton("Ya revisé",(dialog,which)->{uncertainDialog=null;uncertain=false;queryGate();main.removeCallbacks(poll);if(gateVisible)main.postDelayed(poll,15000);}).setNegativeButton("Cerrar",null).create();uncertainDialog.setOnDismissListener(dialog->uncertainDialog=null);uncertainDialog.show();}
    @Override public void onAdmin(){if(adminOpen||repository==null||closed||!resumed)return;openNativeAdmin(null,0);}
    private void openNativeAdmin(String testId,long revision){
        if(repository==null||closed||!resumed)return;adminOpen=true;cameraGeneration++;player.stop();releaseAudioFocus();cameraView().setActive(false);
        admin=new AdminPanel(activity,repository,new AdminPanel.Listener(){
            public void onClose(){endPreview();if(admin!=null){cameraView.removeView(admin);admin=null;}if(!switchingAdmin){adminOpen=false;if(!closed){loadConfiguration();selected(selectedPage,resumed);}}taskStateChanged();}
            public void onPreview(CameraDraft d,char[] password,Surface surface,AdminPanel.PreviewResult callback){preview(d,password,surface,callback);}
            public void onEndPreview(){endPreview();}
            public void onWebAdminRequired(){openWebAdmin();}
        });
        cameraView.addView(admin,new FrameLayout.LayoutParams(-1,-1));
        if(testId==null)admin.open();else admin.openCameraForTest(testId,revision);taskStateChanged();
    }
    private void openWebAdmin(){
        if(closed||!resumed||webAdmin!=null)return;switchingAdmin=true;if(admin!=null)admin.close();switchingAdmin=false;
        endPreview();adminOpen=true;
        webAdmin=new AdminWebSession(activity,repository,new AdminWebSession.Listener(){
            public void onBack(){closeWebAdmin();if(!closed&&resumed)openNativeAdmin(null,0);}
            public void onManualTestApproved(String id,long revision){closeWebAdmin();if(!closed&&resumed)openNativeAdmin(id,revision);}
            public void onStateChanged(){taskStateChanged();}
            public boolean canStartNetwork(){return !closed&&!player.isRunning()&&!previewActive&&!cameraVisible;}
        });
        cameraView.addView(webAdmin.view(),new FrameLayout.LayoutParams(-1,-1));webAdmin.open();taskStateChanged();
    }
    private void closeWebAdmin(){AdminWebSession prior=webAdmin;webAdmin=null;if(prior!=null){prior.close();cameraView.removeView(prior.view());}}
    private void preview(final CameraDraft d,char[] candidate,final Surface surface,final AdminPanel.PreviewResult result){
        endPreview();previewResult=result;if(closed||!resumed||!memoryAvailable()){finishPreview(false,"PREVIEW_UNAVAILABLE");return;}
        final long generation=++previewGeneration;previewActive=true;
        // Total deadline includes queued work/Keystore; it never authorizes a late start.
        main.postDelayed(()->{if(generation==previewGeneration&&previewActive){finishPreview(false,"PREVIEW_TIMEOUT");endPreview();}},15000);
        final char[] copy=candidate==null?null:candidate.clone();
        if(!submitIo(()->{char[] secret=copy;try{
            if(closed||generation!=previewGeneration)return;
            if(secret==null)secret=repository.readPassword(d.id);
            final CameraEndpoint endpoint=new CameraEndpoint(d.host,d.port,d.path,d.username,secret);
            main.post(()->{try{
                if(!closed&&resumed&&adminOpen&&generation==previewGeneration&&surface.isValid()){
                    requestAudioFocus();if(!focusGranted){finishPreview(false,"AUDIO_BUSY");endPreview();return;}
                    applyAudio();player.start(endpoint,surface);
                }
            }finally{endpoint.close();}});
        }catch(Exception e){main.post(()->{if(generation==previewGeneration){finishPreview(false,"CONFIGURATION");endPreview();}});}
        finally{if(secret!=null)Arrays.fill(secret,'\0');}})){
            if(copy!=null)Arrays.fill(copy,'\0');finishPreview(false,"BUSY");endPreview();
        }
    }
    private static String safePreviewCode(String code){
        if(code==null)return "INTERNAL_ERROR";
        switch(code){
            case "NOT_REQUESTED":case "VIDEO_AUDIO_OK":case "PREVIEW_INTERRUPTED":case "PREVIEW_UNAVAILABLE":
            case "PREVIEW_TIMEOUT":case "AUDIO_BUSY":case "CONFIGURATION":case "BUSY":case "MEMORY_PRESSURE":
            case "NONE":case "INVALID_CONFIGURATION":case "CONNECTION":case "TIMEOUT":case "AUTHENTICATION":
            case "AUTH_UNSUPPORTED":case "PROTOCOL":case "UNSUPPORTED_STREAM":case "FRAME_TOO_LARGE":
            case "VIDEO_CODEC":case "AUDIO_OUTPUT":case "SURFACE_LOST":case "FIRST_FRAME_TIMEOUT":case "VIDEO_STALLED":
            case "INTERNAL_ERROR":return code;
            default:return "INTERNAL_ERROR";
        }
    }
    private void finishPreview(boolean ok,String code){AdminPanel.PreviewResult r=previewResult;previewResult=null;if(r!=null){String safe=safePreviewCode(code);lastPreviewCode=safe;android.util.Log.i("X04GPreview","{\"ok\":"+ok+",\"code\":\""+safe+"\"}");r.complete(ok,safe);}}
    private void endPreview(){previewGeneration++;previewResult=null;previewActive=false;player.stop();releaseAudioFocus();}
    private void requestAudioFocus(){if(closed||focusRequested||audioManager==null)return;try{focusGranted=audioManager.requestAudioFocus(audioFocus)==AudioManager.AUDIOFOCUS_REQUEST_GRANTED;focusRequested=focusGranted;}catch(RuntimeException e){focusGranted=false;}}
    private void releaseAudioFocus(){player.setAudioEnabled(false);if(focusRequested&&audioManager!=null)try{audioManager.abandonAudioFocusRequest(audioFocus);}catch(RuntimeException ignored){}focusRequested=focusGranted=false;}
    private void audioFocusChanged(int change){if(closed||!focusRequested)return;if(change==AudioManager.AUDIOFOCUS_GAIN)focusGranted=true;else{focusGranted=false;if(change==AudioManager.AUDIOFOCUS_LOSS){cameraSoundRequested=false;releaseAudioFocus();if(cameraView!=null)cameraView.setSoundSelected(false);}}applyAudio();}
    private void applyAudio(){player.setAudioEnabled(!closed&&resumed&&focusGranted&&(previewActive||cameraVisible&&!adminOpen&&cameraSoundRequested));}
    private boolean memoryAvailable(){try{ActivityManager manager=(ActivityManager)activity.getSystemService(Context.ACTIVITY_SERVICE);if(manager==null)return false;ActivityManager.MemoryInfo info=new ActivityManager.MemoryInfo();manager.getMemoryInfo(info);return !info.lowMemory;}catch(RuntimeException e){return false;}}
    private void pauseForMemory(){memoryPaused=true;cameraGeneration++;finishPreview(false,"MEMORY_PRESSURE");endPreview();if(webAdmin!=null)webAdmin.setActive(false);if(cameraView!=null&&cameraVisible)cameraView.showStreamState("Vídeo pausado por memoria. Vuelve a elegir la cámara.",false);}
    /** API29 still delivers running-low/critical notifications. Never forces GC or touches OEM resources. */
    public void onTrimMemory(int level){if(closed)return;if(level==ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW||level==ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL||level>=ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)pauseForMemory();}
    public void provisionCameras(final String json){cameraProvision="PENDING";if(!submitIo(new Runnable(){public void run(){byte[] bytes=null;try{if(json==null||json.length()>65536)throw new IllegalArgumentException();bytes=json.getBytes(StandardCharsets.UTF_8);ConfigRepository r=new ConfigRepository(activity);r.importProvision(new ByteArrayInputStream(bytes),0);cameraProvision="SAVED";main.post(new Runnable(){public void run(){if(!closed)loadConfiguration();}});}catch(Exception ignored){cameraProvision="REJECTED";}finally{if(bytes!=null)Arrays.fill(bytes,(byte)0);}}}))cameraProvision="BUSY";}
    public void provisionGate(final String json){gateProvision="PENDING";if(!submitIo(new Runnable(){public void run(){try{new GateVault(activity).provision(json);gateProvision="SAVED";if(!closed)loadGate();}catch(Exception ignored){gateProvision="REJECTED";}}}))gateProvision="BUSY";}
    public String status(){try{JSONObject o=new JSONObject();o.put("camera_visible",cameraVisible);o.put("gate_visible",gateVisible);o.put("admin_open",adminOpen);o.put("web_admin_state",webAdmin==null?"CLOSED":webAdmin.state());o.put("preview_active",previewActive);o.put("preview_last_code",lastPreviewCode);o.put("memory_paused",memoryPaused);o.put("audio_focus_granted",focusGranted);o.put("io_queue",((ThreadPoolExecutor)io).getQueue().size());o.put("io_active",((ThreadPoolExecutor)io).getActiveCount());o.put("config_loaded",snapshot!=null&&repository!=null);o.put("camera_surface_valid",cameraSurface!=null&&cameraSurface.isValid());o.put("stream_state",streamState.name());o.put("stream_error",streamError.name());o.put("home_task_active",keepsHomeVisible());o.put("camera_provision",cameraProvision);o.put("gate_provision",gateProvision);o.put("player_running",player.isRunning());o.put("camera_count",snapshot==null?0:snapshot.cameras.size());o.put("configuration_revision",snapshot==null?0:snapshot.revision);o.put("gate_configured",gate!=null);o.put("gate_control_physical_verified",gate!=null&&gate.physicalReplayVerified());GateIntent intent=lastGateIntent;o.put("gate_intent_count",intent==null?0:intent.sequence);o.put("gate_intent_last_state",intent==null?"NOT_REQUESTED":intent.state.name());JSONObject control=gateControlDiagnostic(lastGateControlResult);for(String key:new String[]{"gate_control_last_intent","gate_control_last_outcome","gate_control_last_problem","gate_control_last_profile_verified"})o.put(key,control.get(key));RtspCameraPlayer.Stats measured=player.stats();o.put("frames_rendered",measured.framesRendered);o.put("video_packets",measured.videoPackets);o.put("video_drops",measured.videoDrops);o.put("hardware_decoder",measured.hardwareDecoder);o.put("audio_samples_written",measured.audioSamplesWritten);return o.toString();}catch(Exception e){return "{}";}}
    @Override public void close(){if(closed)return;TuyaGateClient c;GateProfile p;synchronized(gateLock){closed=true;c=gate;p=gateProfile;gate=null;gateProfile=null;}taskStateChanged();taskStateListener=null;main.removeCallbacksAndMessages(null);if(uncertainDialog!=null)uncertainDialog.dismiss();uncertainDialog=null;closeWebAdmin();if(admin!=null)admin.close();if(gateView!=null)gateView.release();if(cameraView!=null)cameraView.release();endPreview();player.close();if(c!=null)c.close();if(p!=null)p.close();io.shutdown();}
    private static String cameraError(RtspCameraPlayer.ErrorCode e){switch(e){case AUTHENTICATION:return "Usuario o contraseña rechazados";case TIMEOUT:case CONNECTION:return "Cámara sin conexión";case UNSUPPORTED_STREAM:case VIDEO_CODEC:return "Formato no compatible";default:return "No se recibe vídeo";}}
}
