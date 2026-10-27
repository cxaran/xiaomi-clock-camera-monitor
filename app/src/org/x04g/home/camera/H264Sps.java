package org.x04g.home.camera;

/** SPS dimensions checked before a remote SPS can reach MediaCodec allocations. */
final class H264Sps {
    final int codedWidth,codedHeight,width,height;
    private H264Sps(int cw,int ch,int w,int h){codedWidth=cw;codedHeight=ch;width=w;height=h;}
    static H264Sps parse(byte[] nal)throws RtspFailure {
        if(nal.length<5 || nal.length>65536 || (nal[0]&31)!=7 || (nal[0]&0x80)!=0)throw bad();
        byte[] rbsp=new byte[nal.length-1];int count=0,zeros=0;
        for(int i=1;i<nal.length;i++) {
            int value=nal[i]&255;
            if(zeros==2 && value==3){zeros=0;continue;}
            rbsp[count++]=nal[i];zeros=value==0?zeros+1:0;if(zeros>2)zeros=2;
        }
        Bits b=new Bits(rbsp,count);int profile=b.bits(8);b.bits(8);b.bits(8);b.ue();
        int chroma=1,separate=0;
        if(profile==100 || profile==110 || profile==122 || profile==244 || profile==44 || profile==83
                || profile==86 || profile==118 || profile==128 || profile==138 || profile==139 || profile==134 || profile==135) {
            chroma=b.ue();if(chroma>3)throw bad();if(chroma==3)separate=b.bits(1);
            if(b.ue()>6 || b.ue()>6)throw bad();b.bits(1);
            if(b.bits(1)!=0) {
                int lists=chroma!=3?8:12;
                for(int i=0;i<lists;i++)if(b.bits(1)!=0) {
                    int last=8,next=8,n=i<6?16:64;
                    for(int j=0;j<n;j++){if(next!=0)next=(last+b.se()+256)&255;last=next==0?last:next;}
                }
            }
        }
        if(b.ue()>12)throw bad();int poc=b.ue();
        if(poc==0){if(b.ue()>12)throw bad();}
        else if(poc==1){b.bits(1);b.se();b.se();int cycle=b.ue();if(cycle>256)throw bad();for(int i=0;i<cycle;i++)b.se();}
        else if(poc!=2)throw bad();
        if(b.ue()>32)throw bad();b.bits(1);
        int mbs=b.ue()+1,maps=b.ue()+1,frameOnly=b.bits(1);
        if(mbs<1 || maps<1 || mbs>512 || maps>512)throw bad();
        if(frameOnly==0)b.bits(1);b.bits(1);
        int left=0,right=0,top=0,bottom=0;
        if(b.bits(1)!=0){left=b.ue();right=b.ue();top=b.ue();bottom=b.ue();}
        int chromaArray=separate!=0?0:chroma;
        int cropX=chromaArray==0 || chromaArray==3?1:2;
        int cropY=(chromaArray==1?2:1)*(2-frameOnly);
        int cw=mbs*16,ch=maps*16*(2-frameOnly),w=cw-(left+right)*cropX,h=ch-(top+bottom)*cropY;
        if(w<1 || h<1 || cw>1280 || ch>736 || w>1280 || h>720)throw new RtspFailure(RtspFailure.Kind.TOO_LARGE);
        return new H264Sps(cw,ch,w,h);
    }
    private static final class Bits {
        final byte[] b;final int limit;int at;
        Bits(byte[] b,int count){this.b=b;limit=count*8;}
        int bits(int count)throws RtspFailure {
            if(count<0 || count>31 || at+count>limit)throw bad();int value=0;
            for(int i=0;i<count;i++){value=(value<<1)|((b[at>>3]>>(7-(at&7)))&1);at++;}return value;
        }
        int ue()throws RtspFailure {
            int zeros=0;while(bits(1)==0){if(++zeros>20)throw bad();}
            return ((1<<zeros)-1)+bits(zeros);
        }
        int se()throws RtspFailure {int v=ue();return (v&1)!=0?(v+1)/2:-v/2;}
    }
    private static RtspFailure bad(){return new RtspFailure(RtspFailure.Kind.PROTOCOL);}
}
