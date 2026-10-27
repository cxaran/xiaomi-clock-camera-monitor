package org.x04g.home.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.style.TypefaceSpan;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.HashSet;
import java.util.Set;
import javax.imageio.ImageIO;

/** Synthetic Android facade: exercises resolution policy and vector geometry only. */
public final class ThemeFallbackHostTest {
    private static void require(boolean ok,String label){if(!ok)throw new AssertionError(label);}
    public static void main(String[] args)throws Exception{
        String mode=args[0];Context context=new Context();
        String regular="dart_app.assets/GoogleSans-Regular.nohints.ttf",medium="dart_app.assets/GoogleSans-Medium.nohints.ttf",icons="dart_app.assets/googlematerialicons/GoogleMaterialIcons-Regular.otf";
        if(mode.equals("stock")){context.addPackage("com.google.assistant.launcher",regular,medium,icons);context.addPackage("org.x04g.home","fonts/GoogleSans-Regular.ttf","fonts/GoogleSans-Medium.ttf","fonts/GoogleMaterialIcons-Regular.otf");}
        if(mode.equals("private"))context.addPackage("org.x04g.home","fonts/GoogleSans-Regular.ttf","fonts/GoogleSans-Medium.ttf","fonts/GoogleMaterialIcons-Regular.otf");
        if(mode.equals("partial")){context.addPackage("com.google.assistant.launcher",regular);context.addPackage("org.x04g.home","fonts/GoogleMaterialIcons-Regular.otf");}
        if(mode.equals("missing-icons"))context.addPackage("com.google.assistant.launcher",regular,medium);
        OemTheme.load(context);
        boolean vector=mode.equals("empty")||mode.equals("missing-icons");
        if(mode.equals("empty")){require(OemTheme.regular()==Typeface.SANS_SERIF,"system regular fallback");require(OemTheme.medium().id.equals("sans-serif-medium"),"system medium fallback");}
        if(mode.equals("stock")){require(OemTheme.regular().id.startsWith("com.google.assistant.launcher:"),"prefer installed stock regular");require(OemTheme.medium().id.startsWith("com.google.assistant.launcher:"),"prefer installed stock medium");require(OemTheme.icons().id.startsWith("com.google.assistant.launcher:"),"prefer installed stock icons");}
        if(mode.equals("private"))require(OemTheme.regular().id.startsWith("org.x04g.home:"),"compatible private build");
        if(mode.equals("partial")){require(OemTheme.regular().id.startsWith("com.google.assistant.launcher:"),"keep available regular");require(OemTheme.medium().id.equals("sans-serif-medium"),"independent medium fallback");require(OemTheme.icons().id.startsWith("org.x04g.home:"),"independent icon source");}
        if(mode.equals("missing-icons"))require(OemTheme.regular().id.startsWith("com.google.assistant.launcher:"),"keep stock text without icons");
        String glyphs="\ue5cd\ue5c4\ue04b\ue04f\ue050\uf011";
        for(char glyph:glyphs.toCharArray()){
            SpannableString text=(SpannableString)OemTheme.icon(String.valueOf(glyph));
            require(text.span instanceof FallbackIconSpan==vector,"correct icon strategy");
            require(vector||text.span instanceof TypefaceSpan,"stock icon typeface span");
            require(text.spanStart==0&&text.spanEnd==1,"icon span bounds");
        }
        SpannableString choice=new SpannableString("\ue04b\nSynthetic camera");OemTheme.styleIcon(choice,0,1);
        require(choice.toString().endsWith("Synthetic camera")&&choice.spanEnd==1,"camera label remains ordinary text");
        boolean rejected=false;try{new FallbackIconSpan('?');}catch(IllegalArgumentException expected){rejected=true;}require(rejected,"reject unsupported icon");
        BufferedImage atlas=new BufferedImage(6*88,4*88,BufferedImage.TYPE_INT_ARGB);Graphics2D output=atlas.createGraphics();int row=0;
        for(int size:new int[]{16,32,44,64}){
            Set<Integer> images=new HashSet<Integer>();int col=0;
            for(char glyph:glyphs.toCharArray()){
                BufferedImage image=new BufferedImage(88,88,BufferedImage.TYPE_INT_ARGB);Canvas canvas=new Canvas(image);Paint paint=new Paint();paint.setTextSize(size);paint.setColor(0xffdadce0);paint.setStrokeWidth(7);
                FallbackIconSpan span=new FallbackIconSpan(glyph);Paint.FontMetricsInt metrics=new Paint.FontMetricsInt();metrics.ascent=-99;
                require(span.getSize(paint,"",0,0,metrics)==size&&metrics.ascent==-99,"inline size and font metrics");
                span.draw(canvas,"",0,0,12,0,60,88,paint);
                require(paint.getStrokeWidth()==7&&paint.getTextSize()==size&&paint.getColor()==0xffdadce0,"caller paint unchanged");require(canvas.restored(),"canvas transform restored");
                int colored=0,hash=1;for(int y=0;y<88;y++)for(int x=0;x<88;x++){int pixel=image.getRGB(x,y);hash=31*hash+pixel;if((pixel>>>24)!=0){colored++;require(x>0&&y>0&&x<87&&y<87,"vector fits without clipping");}}
                require(colored>=12,"visible nonempty vector");require(images.add(hash),"icons and audio states differ");output.drawImage(image,col++*88,row*88,null);canvas.close();
            }
            row++;
        }
        output.dispose();if(args.length>1)ImageIO.write(atlas,"PNG",new File(args[1]));
        System.out.println("{\"pass\":true,\"mode\":\""+mode+"\",\"icons\":6,\"sizes\":4,\"device_accessed\":false}");
    }
}
