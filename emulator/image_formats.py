"""ext4, GPT and liblp assembly primitives for NEW PC emulator images only.

Derived from the verified X04G integrated laboratory image builder.
The filesystem metadata audit mounts only generated images read-only.
"""
from pathlib import Path
import hashlib, json, os, shutil, stat, struct, subprocess, uuid, zlib
MIB = 1024 * 1024
FEATURES = 'none,ext_attr,resize_inode,dir_index,filetype,extent,sparse_super,large_file,huge_file,uninit_bg,dir_nlink,extra_isize'
EVIDENCE = None
PROFILE = 'portable-oem'
def digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()
def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + '\n')
def run(args,log,allowed=(0,)):
    r=subprocess.run([str(x) for x in args],capture_output=True,text=True)
    save(EVIDENCE/'commands'/f'{log}.json',{'argv':[str(x) for x in args],'rc':r.returncode,'stdout':r.stdout,'stderr':r.stderr})
    if r.returncode not in allowed:raise RuntimeError(f'{log} failed: {r.stderr[-2500:]}')
    if any(marker in r.stderr for marker in ["Couldn't parse",'File not found','Command not found','Filesystem not open']):raise RuntimeError(f'{log} reported an internal error: {r.stderr[-1500:]}')
    return r

def write_at(f,offset,data):f.seek(offset);f.write(data)
def copy_at(f,offset,source):
    f.seek(offset)
    with open(source,'rb') as s:shutil.copyfileobj(s,f,4*MIB)

def ext4(tree,out,size,label,symlinks):
    with out.open('xb') as f:f.truncate(size)
    run(['mke2fs','-F','-q','-t','ext4','-b','4096','-I','256','-m','0','-L',label,
         '-O',FEATURES,'-E','lazy_itable_init=0','-d',tree,out],out.stem+'_mke2fs')
    # A VFS symlink cannot carry mode0600/0644: restore the original inode
    # modes directly in the NEW filesystem, never in an input image.
    root_stat=tree.lstat()
    # mke2fs -d populates children/xattrs but initializes inode2 as root:root
    # 0755. A fresh /data must instead carry the reviewed numeric root metadata.
    commands=['set_inode_field "/" uid '+str(root_stat.st_uid),
              'set_inode_field "/" gid '+str(root_stat.st_gid),
              'set_inode_field "/" mode '+hex(root_stat.st_mode)]
    for r in symlinks:
        if any(c in r['path'] for c in ['"','\n','\r']):raise RuntimeError('Unsafe debugfs path')
        commands.append('set_inode_field "'+r['path']+'" mode '+hex(int(r['mode'],8)))
    batch=EVIDENCE/(out.stem+'_restore_symlink_modes.txt');batch.write_text('\n'.join(commands)+'\n')
    if commands:run(['debugfs','-w','-f',batch,out],out.stem+'_symlink_modes')
    run(['e2fsck','-fn',out],out.stem+'_e2fsck')

def lp_metadata(source,system_size,vendor_size):
    # Preserve the verified SDK geometry/tables and change ONLY two extents.
    with source.open('rb') as f:
        f.seek(2*MIB);geometry=f.read(MIB)
    header=bytearray(geometry[12288:12288+65536])
    hsize=struct.unpack_from('<I',header,8)[0];tsize=struct.unpack_from('<I',header,44)[0]
    tables=bytearray(header[hsize:hsize+tsize]);po,pc,ps=struct.unpack_from('<III',header,80)
    eo,ec,es=struct.unpack_from('<III',header,92)
    if pc!=2 or ec!=2 or es!=24:raise RuntimeError('Expected bounded source LP layout')
    changes={}
    for index in range(pc):
        part=tables[po+index*ps:po+(index+1)*ps]
        name=part[:36].split(b'\0')[0].decode();first,count=struct.unpack_from('<II',part,40)
        if count!=1 or name not in ('system','vendor'):raise RuntimeError('Unexpected source LP partition')
        size=system_size if name=='system' else vendor_size
        offset=MIB if name=='system' else MIB+system_size
        struct.pack_into('<QIQI',tables,eo+first*es,size//512,0,offset//512,0)
        changes[name]={'offset_within_super':offset,'size':size}
    header[hsize:hsize+tsize]=tables
    header[48:80]=hashlib.sha256(tables).digest()
    header[12:44]=bytes(32);header[12:44]=hashlib.sha256(header[:hsize]).digest()
    block=bytes(header)
    # Both slots and both primary/backup metadata copies are coherent.
    for offset in [12288,12288+65536,12288+2*65536,12288+3*65536]:geometry=geometry[:offset]+block+geometry[offset+65536:]
    return geometry,changes

def vbmeta_none():
    header=bytearray(256);header[:4]=b'AVB0'
    struct.pack_into('>II',header,4,1,0) # required libavb version
    struct.pack_into('>I',header,28,0) # algorithm NONE, auth and auxiliary sizes0
    struct.pack_into('>I',header,120,3) # hashtree+verification disabled in PC lab
    release=('x04g-PC-diagnostic'+PROFILE+'-unsigned').encode();header[128:128+len(release)]=release
    return bytes(header)

def gpt_vendor(source,out,payload):
    # Preserve names/type/GUID and exact outer layout of the SDK vendor disk.
    # Payload is enlarged only if its new filesystem does not fit the old GPT.
    # Use fresh lab GUIDs so the derivative cannot be confused with its source.
    size=payload.stat().st_size;total=size+2*MIB;sectors=total//512
    with source.open('rb') as f:prefix=f.read(MIB)
    entries=bytearray(prefix[1024:1024+128*128]);entries[16:32]=uuid.uuid4().bytes_le
    struct.pack_into('<QQ',entries,32,2048,2048+size//512-1)
    entry_crc=zlib.crc32(entries)
    primary=bytearray(prefix[512:1024]);primary[56:72]=uuid.uuid4().bytes_le
    struct.pack_into('<QQQQ',primary,24,1,sectors-1,2048,sectors-2049)
    struct.pack_into('<QIII',primary,72,2,128,128,entry_crc)
    primary[16:20]=bytes(4);struct.pack_into('<I',primary,16,zlib.crc32(primary[:92]))
    backup=bytearray(primary);struct.pack_into('<QQ',backup,24,sectors-1,1)
    struct.pack_into('<Q',backup,72,sectors-33);backup[16:20]=bytes(4)
    struct.pack_into('<I',backup,16,zlib.crc32(backup[:92]))
    mbr=bytearray(prefix[:512]);struct.pack_into('<I',mbr,458,min(sectors-1,0xffffffff))
    with out.open('xb') as f:
        f.truncate(total);write_at(f,0,mbr);write_at(f,512,primary);write_at(f,1024,entries)
        copy_at(f,MIB,payload);write_at(f,(sectors-33)*512,entries);write_at(f,(sectors-1)*512,backup)

def check_tree_image(image,tree,symlink_modes):
    # Read-only mount to independently inspect resulting metadata and content.
    mountpoint=image.parent/(image.stem+'_verify');mountpoint.mkdir()
    loop=run(['losetup','--find','--show','--read-only',image],image.stem+'_loop').stdout.strip()
    try:
        run(['mount','-o','ro,noload,nodev,nosuid,noexec',loop,mountpoint],image.stem+'_mount')
        failures=[];count=0;symlink_expected={r['path']:int(r['mode'],8) for r in symlink_modes}
        # rglob does not follow directory symlinks.
        for source in [tree]+sorted(tree.rglob('*')):
            rel='/'+str(source.relative_to(tree)) if source!=tree else '/'
            target=mountpoint/rel.lstrip('/');s=source.lstat();t=target.lstat();count+=1
            expected=symlink_expected.get(rel,s.st_mode)
            if (t.st_mode,t.st_uid,t.st_gid)!=(expected,s.st_uid,s.st_gid):failures.append({'path':rel,'field':'mode/owner','expected':[expected,s.st_uid,s.st_gid],'actual':[t.st_mode,t.st_uid,t.st_gid]})
            sx={k:os.getxattr(source,k,follow_symlinks=False).hex() for k in os.listxattr(source,follow_symlinks=False)}
            tx={k:os.getxattr(target,k,follow_symlinks=False).hex() for k in os.listxattr(target,follow_symlinks=False)}
            if sx!=tx:failures.append({'path':rel,'field':'xattrs','expected':sx,'actual':tx})
            if stat.S_ISREG(s.st_mode) and digest(source)!=digest(target):failures.append({'path':rel,'field':'content'})
            if stat.S_ISLNK(s.st_mode) and os.readlink(source)!=os.readlink(target):failures.append({'path':rel,'field':'symlink_target'})
        expected_paths={'/'+str(p.relative_to(tree)) for p in tree.rglob('*')}
        actual_paths={'/'+str(p.relative_to(mountpoint)) for p in mountpoint.rglob('*')}
        extra=sorted(actual_paths-expected_paths-{'/lost+found'})
        if extra:failures.append({'field':'unexpected_image_paths','paths':extra})
        save(EVIDENCE/(image.stem+'_filesystem_verification.json'),{'entries':count,'all_match':not failures,'failures':failures,'extra_paths_excluding_empty_lost_found':extra,'read_only_mount':True})
        if failures:raise RuntimeError('Image metadata/content mismatch: '+str(failures[:3]))
    finally:
        # An accepted filesystem audit also requires closing its exact mount
        # and loop. Never detach a loop while an unmount failure is unresolved.
        if os.path.ismount(mountpoint):
            subprocess.run(['umount', str(mountpoint)], capture_output=True, check=True)
        if os.path.ismount(mountpoint):
            raise RuntimeError('Generated-image audit mount remains active')
        subprocess.run(['losetup', '-d', loop], capture_output=True, check=True)
        mountpoint.rmdir()

