package org.x04g.home.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.widget.TextView;

/** Native rendering of the resources audited from the user's stock launcher. */
public final class OemTheme {
    public static final int BACKGROUND=Color.BLACK, TEXT=0xffdadce0, SECONDARY=0xff9aa0a6;
    public static final int ACTION=0xff8ab4f8, BORDER=0xff455a7c, CONTROL=0xff202124;
    private static Typeface regular,medium,icons;
    private OemTheme(){}
    public static synchronized void load(Context context) {
        if(regular!=null)return;
        try {
            Context own=context.createPackageContext("org.x04g.home",Context.CONTEXT_IGNORE_SECURITY);
            regular=Typeface.createFromAsset(own.getAssets(),"fonts/GoogleSans-Regular.ttf");
            medium=Typeface.createFromAsset(own.getAssets(),"fonts/GoogleSans-Medium.ttf");
            icons=Typeface.createFromAsset(own.getAssets(),"fonts/GoogleMaterialIcons-Regular.otf");
        }catch(Exception e){regular=Typeface.SANS_SERIF;medium=Typeface.create("sans-serif-medium",0);icons=regular;}
    }
    public static Typeface regular(){return regular;} public static Typeface medium(){return medium;} public static Typeface icons(){return icons;}
    public static TextView label(Context c,String text,float size,int color){load(c);TextView v=new TextView(c);v.setText(text);v.setTextColor(color);v.setTypeface(regular);v.setTextSize(TypedValue.COMPLEX_UNIT_PX,size);v.setIncludeFontPadding(false);return v;}
    public static GradientDrawable surface(int color,float radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(radius);return d;}
}
