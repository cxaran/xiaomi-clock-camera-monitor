package org.x04g.home.config;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Blocking service API: call on a worker, never the touch/UI thread. No network or logging. */
public final class ConfigRepository {
    private final AtomicConfigFile file;
    private final VaultCipher cipher;
    private static final class Entry {
        final CameraConfig camera;final char[] password;
        Entry(CameraConfig camera,char[] password){this.camera=camera;this.password=password;}
    }
    private static final class State implements AutoCloseable {
        long revision;final List<Entry> entries=new ArrayList<Entry>();
        State(long revision){this.revision=revision;}
        @Override public void close(){for(Entry e:entries)Arrays.fill(e.password,'\0');}
    }
    public ConfigRepository(Context context) throws ConfigException {
        if(context==null)throw new ConfigException(ConfigException.Code.INVALID_INPUT);
        file=new AtomicConfigFile(new File(context.getNoBackupFilesDir(),"x04g-home"));
        cipher=new VaultCipher(new AndroidVaultKeys());
    }
    // Package-private dependency seam for offline fixtures; production always uses AndroidKeyStore.
    ConfigRepository(AtomicConfigFile file,VaultCipher cipher) {
        if(file==null||cipher==null)throw new IllegalArgumentException("config_dependencies");
        this.file=file;this.cipher=cipher;
    }
    public ConfigSnapshot load() throws ConfigException {
        return file.locked(new AtomicConfigFile.Work<ConfigSnapshot>() {public ConfigSnapshot run() throws Exception {
            try(State s=read()){return snapshot(s);}
        }});
    }
    /** null password preserves an existing password; caller retains ownership of its char[]. */
    public ConfigSnapshot upsert(final CameraDraft draft,final char[] passwordOrNull,final long expectedRevision) throws ConfigException {
        final CameraConfig candidate=ConfigRules.validate(draft,true);
        if(passwordOrNull!=null)ConfigRules.password(passwordOrNull);
        final char[] input=passwordOrNull==null?null:passwordOrNull.clone();
        try {
            return file.locked(new AtomicConfigFile.Work<ConfigSnapshot>() {public ConfigSnapshot run() throws Exception {
                try(State s=read()) {
                    expected(s,expectedRevision);int index=index(s,candidate.id);
                    if(index<0&&s.entries.size()>=ConfigRules.MAX_CAMERAS)throw new ConfigException(ConfigException.Code.LIMIT);
                    if(index<0&&input==null)throw new ConfigException(ConfigException.Code.INVALID_INPUT);
                    char[] secret=input!=null?input.clone():s.entries.get(index).password.clone();
                    if(index>=0) {Entry old=s.entries.set(index,new Entry(candidate,secret));Arrays.fill(old.password,'\0');}
                    else s.entries.add(new Entry(candidate,secret));
                    unique(s);commit(s);return snapshot(s);
                }
            }});
        } finally {if(input!=null)Arrays.fill(input,'\0');}
    }
    public ConfigSnapshot remove(final String id,final long expectedRevision) throws ConfigException {
        return file.locked(new AtomicConfigFile.Work<ConfigSnapshot>() {public ConfigSnapshot run() throws Exception {
            try(State s=read()) {expected(s,expectedRevision);int index=index(s,id);
                if(index<0)throw new ConfigException(ConfigException.Code.NOT_FOUND);
                Entry removed=s.entries.remove(index);Arrays.fill(removed.password,'\0');commit(s);return snapshot(s);
            }
        }});
    }
    public ConfigSnapshot reorder(List<String> ids,final long expectedRevision) throws ConfigException {
        if(ids==null||ids.size()>ConfigRules.MAX_CAMERAS)throw new ConfigException(ConfigException.Code.INVALID_INPUT);
        final List<String> order=new ArrayList<String>(ids);
        return file.locked(new AtomicConfigFile.Work<ConfigSnapshot>() {public ConfigSnapshot run() throws Exception {
            try(State s=read()) {expected(s,expectedRevision);
                if(order.size()!=s.entries.size()||new HashSet<String>(order).size()!=order.size())throw new ConfigException(ConfigException.Code.INVALID_INPUT);
                List<Entry> sorted=new ArrayList<Entry>();for(String id:order){int i=index(s,id);if(i<0)throw new ConfigException(ConfigException.Code.INVALID_INPUT);sorted.add(s.entries.get(i));}
                s.entries.clear();s.entries.addAll(sorted);commit(s);return snapshot(s);
            }
        }});
    }
    /** Caller must Arrays.fill(result,'\0') after authentication; never put it into a URL or log. */
    public char[] readPassword(final String id) throws ConfigException {
        return file.locked(new AtomicConfigFile.Work<char[]>() {public char[] run() throws Exception {
            try(State s=read()){int i=index(s,id);if(i<0)throw new ConfigException(ConfigException.Code.NOT_FOUND);return s.entries.get(i).password.clone();}
        }});
    }
    /** Metadata export deliberately excludes username, password, cipher/IV and auth headers. */
    public String exportRedacted() throws ConfigException {
        ConfigSnapshot s=load();try {
            JSONObject root=new JSONObject();root.put("schema_version",1);root.put("revision",s.revision);
            JSONArray cameras=new JSONArray();for(CameraConfig c:s.cameras){JSONObject o=metadata(c);o.remove("username");o.put("username_configured",!c.username.isEmpty());o.put("password_configured",c.hasPassword);cameras.put(o);}
            root.put("cameras",cameras);return root.toString();
        }catch(JSONException e){throw new ConfigException(ConfigException.Code.CORRUPT);}
    }
    /** Initial import ONLY. Caller supplies an explicitly selected local stream; it is not closed here.
     * Accepted schema: {schema_version:1,cameras:[metadata plus password]}. No URLs or vault blobs.
     * This does not test RTSP: UI must label successful persistence separately from playback verification.
     */
    public ConfigSnapshot importProvision(InputStream input,final long expectedRevision) throws ConfigException {
        if(input==null)throw new ConfigException(ConfigException.Code.INVALID_INPUT);
        final byte[] bytes;
        try {bytes=boundedRead(input,ConfigRules.MAX_IMPORT_BYTES);}catch(Exception e){throw new ConfigException(ConfigException.Code.INVALID_INPUT);}
        try {
            return file.locked(new AtomicConfigFile.Work<ConfigSnapshot>() {public ConfigSnapshot run() throws Exception {
                try(State existing=read()) {expected(existing,expectedRevision);
                    if(existing.revision!=0||!existing.entries.isEmpty())throw new ConfigException(ConfigException.Code.CONFLICT);
                    try(State imported=decode(bytes,0,true)){commit(imported);return snapshot(imported);}
                }
            }});
        }finally{Arrays.fill(bytes,(byte)0);}
    }
    private State read() throws Exception {
        byte[] envelope=file.read();if(envelope.length==0)return new State(0);
        VaultCipher.Opened opened=cipher.open(envelope);
        try{return decode(opened.plaintext,opened.revision,false);}finally{Arrays.fill(opened.plaintext,(byte)0);}
    }
    private void commit(State s) throws Exception {
        if(s.revision==Long.MAX_VALUE)throw new ConfigException(ConfigException.Code.CONFLICT);
        long revision=s.revision+1;byte[] plain=null;
        try {JSONObject root=new JSONObject();root.put("schema_version",1);root.put("revision",revision);JSONArray array=new JSONArray();
            for(Entry e:s.entries){JSONObject o=metadata(e.camera);o.put("password",new String(e.password));array.put(o);}
            root.put("cameras",array);plain=root.toString().getBytes(StandardCharsets.UTF_8);file.replace(cipher.seal(revision,plain));s.revision=revision;
        }finally{if(plain!=null)Arrays.fill(plain,(byte)0);}
    }
    private static State decode(byte[] bytes,long revision,boolean provision) throws ConfigException {
        State s=new State(revision);
        try {
            JSONObject root=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
            allowed(root,provision?new String[]{"schema_version","cameras"}:new String[]{"schema_version","revision","cameras"});
            if(exactLong(root.get("schema_version"))!=1)throw new JSONException("schema");
            if(!provision&&exactLong(root.get("revision"))!=revision)throw new JSONException("revision");
            JSONArray array=root.getJSONArray("cameras");if(array.length()>ConfigRules.MAX_CAMERAS)throw new ConfigException(ConfigException.Code.LIMIT);
            for(int i=0;i<array.length();i++) {
                JSONObject o=array.getJSONObject(i);allowed(o,provision?new String[]{"id","label","host","port","path","username","mac","onvif_uuid","model","enabled","password"}:new String[]{"id","label","host","port","path","username","mac","onvif_uuid","model","enabled","password","needs_verification","verified_at_millis"});
                CameraDraft d=new CameraDraft();d.id=string(o,"id","");d.label=string(o,"label","");d.host=string(o,"host","");
                d.username=string(o,"username","");d.path=string(o,"path","/stream2");d.mac=string(o,"mac","");d.onvifUuid=string(o,"onvif_uuid","");d.model=string(o,"model","");
                if(!provision&&d.id.isEmpty())throw new JSONException("id");
                if(o.has("port")){Object n=o.get("port");if(!(n instanceof Number)||((Number)n).doubleValue()!=((Number)n).intValue())throw new JSONException("port");d.port=((Number)n).intValue();}
                if(o.has("enabled")){Object b=o.get("enabled");if(!(b instanceof Boolean))throw new JSONException("enabled");d.enabled=(Boolean)b;}
                if(!provision){Object b=o.opt("needs_verification"),time=o.opt("verified_at_millis");
                    if(b!=null&&!(b instanceof Boolean)||time!=null&&!(time instanceof Number))throw new JSONException("verification");
                    d.needsVerification=b==null||(Boolean)b;d.verifiedAtMillis=time==null?0:exactLong(time);}
                char[] password=string(o,"password","").toCharArray();
                try {ConfigRules.password(password);s.entries.add(new Entry(ConfigRules.validate(d,true),password));}
                catch(Exception e){Arrays.fill(password,'\0');throw e;}
            }
            unique(s);return s;
        }catch(Exception e){s.close();if(provision&&e instanceof ConfigException)throw (ConfigException)e;
            throw new ConfigException(provision?ConfigException.Code.INVALID_INPUT:ConfigException.Code.CORRUPT);}
    }
    private static JSONObject metadata(CameraConfig c) throws JSONException {
        JSONObject o=new JSONObject();o.put("id",c.id);o.put("label",c.label);o.put("host",c.host);o.put("port",c.port);o.put("path",c.path);o.put("username",c.username);
        o.put("mac",c.mac);o.put("onvif_uuid",c.onvifUuid);o.put("model",c.model);o.put("enabled",c.enabled);
        o.put("needs_verification",c.needsVerification);o.put("verified_at_millis",c.verifiedAtMillis);return o;
    }
    private static String string(JSONObject object,String name,String fallback) throws JSONException {
        if(!object.has(name))return fallback;Object value=object.get(name);if(!(value instanceof String))throw new JSONException("type");return (String)value;
    }
    private static long exactLong(Object value) throws JSONException {
        if(!(value instanceof Byte||value instanceof Short||value instanceof Integer||value instanceof Long))throw new JSONException("integer");
        return ((Number)value).longValue();
    }
    private static void allowed(JSONObject object,String[] names) throws JSONException {
        Set<String> known=new HashSet<String>(Arrays.asList(names));Iterator<String> keys=object.keys();while(keys.hasNext())if(!known.contains(keys.next()))throw new JSONException("field");
    }
    private static byte[] boundedRead(InputStream in,int cap) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] block=new byte[4096];int n;
        while((n=in.read(block))!=-1){if(n==0)throw new IOException();if(out.size()+n>cap)throw new IOException();out.write(block,0,n);}return out.toByteArray();
    }
    private static void expected(State s,long revision) throws ConfigException {if(revision<0||s.revision!=revision)throw new ConfigException(ConfigException.Code.CONFLICT);}
    private static int index(State s,String id){for(int i=0;i<s.entries.size();i++)if(s.entries.get(i).camera.id.equals(id))return i;return -1;}
    private static void unique(State s) throws ConfigException {
        Set<String> ids=new HashSet<String>(),macs=new HashSet<String>(),uuids=new HashSet<String>();
        for(Entry e:s.entries){CameraConfig c=e.camera;if(!ids.add(c.id)||!c.mac.isEmpty()&&!macs.add(c.mac)||!c.onvifUuid.isEmpty()&&!uuids.add(c.onvifUuid))throw new ConfigException(ConfigException.Code.INVALID_INPUT);}
    }
    private static ConfigSnapshot snapshot(State s){List<CameraConfig> cameras=new ArrayList<CameraConfig>();for(Entry e:s.entries)cameras.add(e.camera);return new ConfigSnapshot(s.revision,cameras);}
}
