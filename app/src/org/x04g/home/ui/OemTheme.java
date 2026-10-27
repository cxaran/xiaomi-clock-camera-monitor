package org.x04g.home.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.TypefaceSpan;
import android.util.TypedValue;
import android.widget.TextView;

/** Stock assets stay on the device; public builds also work without them. */
public final class OemTheme {
    public static final int BACKGROUND=Color.BLACK, TEXT=0xffdadce0, SECONDARY=0xff9aa0a6;
    public static final int ACTION=0xff8ab4f8, BORDER=0xff455a7c, CONTROL=0xff202124;
    private static Typeface regular,medium,icons;
    private OemTheme(){}
    public static synchronized void load(Context context) {
        if(regular!=null)return;
        Context stock=packageContext(context,"com.google.assistant.launcher");
        Context own=packageContext(context,"org.x04g.home");
        regular=face(stock,"dart_app.assets/GoogleSans-Regular.nohints.ttf");
        medium=face(stock,"dart_app.assets/GoogleSans-Medium.nohints.ttf");
        icons=face(stock,"dart_app.assets/googlematerialicons/GoogleMaterialIcons-Regular.otf");
        // Existing private builds may still carry their owner's extracted assets.
        if(regular==null)regular=face(own,"fonts/GoogleSans-Regular.ttf");
        if(medium==null)medium=face(own,"fonts/GoogleSans-Medium.ttf");
        if(icons==null)icons=face(own,"fonts/GoogleMaterialIcons-Regular.otf");
        if(regular==null)regular=Typeface.SANS_SERIF;
        if(medium==null)medium=Typeface.create("sans-serif-medium",0);
    }
    private static Context packageContext(Context context,String name){try{return context.createPackageContext(name,Context.CONTEXT_IGNORE_SECURITY);}catch(Exception unavailable){return null;}}
    private static Typeface face(Context context,String asset){if(context==null)return null;try{return Typeface.createFromAsset(context.getAssets(),asset);}catch(Exception unavailable){return null;}}
    public static Typeface regular(){return regular;} public static Typeface medium(){return medium;} public static Typeface icons(){return icons==null?regular:icons;}
    public static CharSequence icon(String glyph){SpannableString text=new SpannableString(glyph);styleIcon(text,0,text.length());return text;}
    public static void styleIcon(SpannableString text,int start,int end){text.setSpan(icons==null?new FallbackIconSpan(text.charAt(start)):new TypefaceSpan(icons),start,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);}
    public static TextView label(Context c,String text,float size,int color){load(c);TextView v=new TextView(c);v.setText(text);v.setTextColor(color);v.setTypeface(regular);v.setTextSize(TypedValue.COMPLEX_UNIT_PX,size);v.setIncludeFontPadding(false);return v;}
    public static GradientDrawable surface(int color,float radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(radius);return d;}
}
