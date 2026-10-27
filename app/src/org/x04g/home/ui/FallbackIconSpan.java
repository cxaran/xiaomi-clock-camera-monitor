package org.x04g.home.ui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.style.ReplacementSpan;

/** Original simple line drawings. No font, bitmap or proprietary glyph data. */
final class FallbackIconSpan extends ReplacementSpan {
    private final char glyph;
    FallbackIconSpan(char glyph){
        if("\ue5cd\ue5c4\ue04b\ue04f\ue050\uf011".indexOf(glyph)<0)throw new IllegalArgumentException("unsupported_ui_icon");
        this.glyph=glyph;
    }
    @Override public int getSize(Paint paint,CharSequence text,int start,int end,Paint.FontMetricsInt metrics){return Math.round(paint.getTextSize());}
    @Override public void draw(Canvas canvas,CharSequence text,int start,int end,float x,int top,int baseline,int bottom,Paint textPaint){
        float size=textPaint.getTextSize();
        Paint paint=new Paint(textPaint);paint.setAntiAlias(true);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
        int saved=canvas.save();
        try {
            canvas.translate(x,baseline+(textPaint.ascent()+textPaint.descent())/2-size/2);
            canvas.scale(size/24,size/24);
            switch(glyph){
                case '\ue5cd':line(canvas,paint,5,5,19,19);line(canvas,paint,19,5,5,19);break;
                case '\ue5c4':line(canvas,paint,20,12,4,12);line(canvas,paint,4,12,11,5);line(canvas,paint,4,12,11,19);break;
                case '\ue04b':
                    canvas.drawRoundRect(new RectF(3,6,16,18),2,2,paint);
                    Path camera=new Path();camera.moveTo(16,10);camera.lineTo(21,7);camera.lineTo(21,17);camera.lineTo(16,14);canvas.drawPath(camera,paint);break;
                case '\ue04f':case '\ue050':
                    Path speaker=new Path();speaker.moveTo(3,9);speaker.lineTo(7,9);speaker.lineTo(12,5);speaker.lineTo(12,19);speaker.lineTo(7,15);speaker.lineTo(3,15);speaker.close();canvas.drawPath(speaker,paint);
                    if(glyph=='\ue04f'){line(canvas,paint,16,9,21,15);line(canvas,paint,21,9,16,15);}
                    else{canvas.drawArc(new RectF(10,7,18,17),-60,120,false,paint);canvas.drawArc(new RectF(8,3,22,21),-60,120,false,paint);}break;
                case '\uf011':canvas.drawArc(new RectF(4,4,20,21),-55,290,false,paint);line(canvas,paint,12,3,12,12);break;
                default:throw new AssertionError();
            }
        } finally {canvas.restoreToCount(saved);}
    }
    private static void line(Canvas canvas,Paint paint,float x1,float y1,float x2,float y2){canvas.drawLine(x1,y1,x2,y2,paint);}
}
