package org.x04g.home.config;

import java.io.*;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.util.concurrent.locks.ReentrantLock;

/** Small private file transaction. Atomic rename is mandatory; no non-atomic fallback. */
final class AtomicConfigFile {
    interface Work<T> { T run() throws Exception; }
    interface BeforeCommit { void run() throws IOException; }
    private static final ReentrantLock PROCESS_LOCK=new ReentrantLock();
    private final File dir,current,previous,lock;
    private final BeforeCommit hook;
    AtomicConfigFile(File dir) throws ConfigException { this(dir,null); }
    AtomicConfigFile(File dir,BeforeCommit hook) throws ConfigException {
        this.dir=dir;this.hook=hook;
        try {
            if(Files.isSymbolicLink(dir.toPath())||(!dir.exists()&&!dir.mkdirs())||!dir.isDirectory()) throw new IOException();
            ownerOnly(dir,true);
            current=new File(dir,"cameras.v1.enc");previous=new File(dir,"cameras.v1.previous.enc");lock=new File(dir,"config.lock");
        } catch(Exception e) { throw new ConfigException(ConfigException.Code.STORAGE); }
    }
    <T> T locked(Work<T> work) throws ConfigException {
        PROCESS_LOCK.lock();
        try {
            if(Files.isSymbolicLink(lock.toPath()))throw new IOException();
            try(FileOutputStream out=new FileOutputStream(lock,true);FileLock held=out.getChannel().lock()) {
                if(!held.isValid())throw new IOException();ownerOnly(lock,false);return work.run();
            }
        } catch(ConfigException e) { throw e; }
        catch(Exception e) { throw new ConfigException(ConfigException.Code.STORAGE); }
        finally { PROCESS_LOCK.unlock(); }
    }
    byte[] read() throws IOException { return read(current); }
    private byte[] read(File f) throws IOException {
        if(!f.exists())return new byte[0];
        if(Files.isSymbolicLink(f.toPath())||!f.isFile()||f.length()==0||f.length()>ConfigRules.MAX_FILE_BYTES)throw new IOException();
        try(InputStream in=new FileInputStream(f);ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] block=new byte[4096];int count;
            while((count=in.read(block))!=-1) { if(out.size()+count>ConfigRules.MAX_FILE_BYTES)throw new IOException();out.write(block,0,count); }
            return out.toByteArray();
        }
    }
    void replace(byte[] bytes) throws IOException {
        if(bytes.length==0||bytes.length>ConfigRules.MAX_FILE_BYTES)throw new IOException();
        byte[] old=read();
        if(old.length>0)atomic(previous,old,false);
        atomic(current,bytes,true);
    }
    private void atomic(File target,byte[] bytes,boolean commit) throws IOException {
        if(Files.isSymbolicLink(target.toPath()))throw new IOException();
        File temp=File.createTempFile("x04g-config-",".tmp",dir);
        try {
            ownerOnly(temp,false);
            try(FileOutputStream out=new FileOutputStream(temp)) { out.write(bytes);out.getFD().sync(); }
            if(commit&&hook!=null)hook.run(); // Fault-injection boundary used only by offline tests.
            Files.move(temp.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { if(temp.exists()&&!temp.delete())temp.deleteOnExit(); }
    }
    private static void ownerOnly(File file,boolean directory) throws IOException {
        if(!file.setReadable(false,false)||!file.setWritable(false,false)||!file.setExecutable(false,false)
                ||!file.setReadable(true,true)||!file.setWritable(true,true)||(directory&&!file.setExecutable(true,true)))throw new IOException();
    }
}
