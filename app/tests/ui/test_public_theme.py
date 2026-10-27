"""HOST policy/vector tests; synthetic Android facade, no device or Android renderer.

Run: python app/tests/ui/test_public_theme.py
The atlas is a Java2D rendering of the original vector commands for visual review.
"""
from pathlib import Path
import json
import os
import re
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts"))
from toolchain import resolve

# Deliberately independent facade: asset lookup can fail per font, and Canvas
# executes the same drawing commands through Java2D rather than Android stubs.
STUBS = {
    "android/content/Context.java": '''package android.content;
import android.content.res.AssetManager;import java.util.*;
public class Context {public static final int CONTEXT_IGNORE_SECURITY=2;private final Map<String,Context> packages;private final AssetManager assets;
public Context(){packages=new HashMap<String,Context>();assets=null;}private Context(Map<String,Context> p,AssetManager a){packages=p;assets=a;}
public void addPackage(String name,String... paths){packages.put(name,new Context(packages,new AssetManager(name,paths)));}
public Context createPackageContext(String name,int flags)throws Exception{Context c=packages.get(name);if(c==null)throw new Exception("missing package");return c;}
public AssetManager getAssets(){return assets;}}''',
    "android/content/res/AssetManager.java": '''package android.content.res;
import java.util.*;public class AssetManager {public final String owner;public final Set<String> paths;
public AssetManager(String owner,String[] paths){this.owner=owner;this.paths=new HashSet<String>(Arrays.asList(paths));}}''',
    "android/graphics/Typeface.java": '''package android.graphics;
import android.content.res.AssetManager;public class Typeface {public final String id;private Typeface(String id){this.id=id;}
public static final Typeface SANS_SERIF=new Typeface("sans-serif");public static Typeface create(String name,int style){return new Typeface(name);}
public static Typeface createFromAsset(AssetManager a,String path){if(!a.paths.contains(path))throw new RuntimeException("missing asset");return new Typeface(a.owner+":"+path);}}''',
    "android/graphics/Color.java": "package android.graphics;public class Color {public static final int BLACK=0xff000000;}",
    "android/graphics/Paint.java": '''package android.graphics;
public class Paint {public enum Style{STROKE,FILL}public enum Cap{ROUND,BUTT}public enum Join{ROUND,MITER}public static class FontMetricsInt {public int ascent,descent;}
private float size=44,width=1;private int color=0xffdadce0;private boolean aa;public Style style=Style.FILL;
public Paint(){}public Paint(Paint p){size=p.size;width=p.width;color=p.color;aa=p.aa;style=p.style;}
public void setTextSize(float s){size=s;}public float getTextSize(){return size;}public float ascent(){return -.8f*size;}public float descent(){return .2f*size;}
public void setColor(int c){color=c;}public int getColor(){return color;}public void setStrokeWidth(float w){width=w;}public float getStrokeWidth(){return width;}
public void setAntiAlias(boolean a){aa=a;}public boolean isAntiAlias(){return aa;}public void setStyle(Style s){style=s;}public void setStrokeCap(Cap c){}public void setStrokeJoin(Join j){}}''',
    "android/graphics/RectF.java": '''package android.graphics;public class RectF {public final float l,t,r,b;public RectF(float l,float t,float r,float b){this.l=l;this.t=t;this.r=r;this.b=b;}}''',
    "android/graphics/Path.java": '''package android.graphics;import java.awt.geom.Path2D;
public class Path {public final Path2D.Float shape=new Path2D.Float();public void moveTo(float x,float y){shape.moveTo(x,y);}public void lineTo(float x,float y){shape.lineTo(x,y);}public void close(){shape.closePath();}}''',
    "android/graphics/Canvas.java": '''package android.graphics;
import java.awt.*;import java.awt.geom.*;import java.awt.image.BufferedImage;import java.util.ArrayList;
public class Canvas implements AutoCloseable {private final Graphics2D g;private final ArrayList<AffineTransform> stack=new ArrayList<AffineTransform>();
public Canvas(BufferedImage image){g=image.createGraphics();}public int save(){stack.add(g.getTransform());return stack.size();}
public void restoreToCount(int count){g.setTransform(stack.get(count-1));while(stack.size()>=count)stack.remove(stack.size()-1);}
public boolean restored(){return stack.isEmpty()&&g.getTransform().isIdentity();}public void translate(float x,float y){g.translate(x,y);}public void scale(float x,float y){g.scale(x,y);}
private void draw(Shape s,Paint p){g.setColor(new java.awt.Color(p.getColor(),true));g.setStroke(new BasicStroke(p.getStrokeWidth(),BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,p.isAntiAlias()?RenderingHints.VALUE_ANTIALIAS_ON:RenderingHints.VALUE_ANTIALIAS_OFF);if(p.style==Paint.Style.STROKE)g.draw(s);else g.fill(s);}
public void drawLine(float a,float b,float c,float d,Paint p){draw(new Line2D.Float(a,b,c,d),p);}public void drawPath(Path path,Paint p){draw(path.shape,p);}
public void drawArc(RectF r,float start,float sweep,boolean center,Paint p){draw(new Arc2D.Float(r.l,r.t,r.r-r.l,r.b-r.t,-start,-sweep,center?Arc2D.PIE:Arc2D.OPEN),p);}
public void drawRoundRect(RectF r,float rx,float ry,Paint p){draw(new RoundRectangle2D.Float(r.l,r.t,r.r-r.l,r.b-r.t,rx*2,ry*2),p);}public void close(){g.dispose();}}''',
    "android/graphics/drawable/GradientDrawable.java": "package android.graphics.drawable;public class GradientDrawable {public void setColor(int c){}public void setCornerRadius(float r){}}",
    "android/text/Spanned.java": "package android.text;public interface Spanned {int SPAN_EXCLUSIVE_EXCLUSIVE=33;}",
    "android/text/SpannableString.java": '''package android.text;public class SpannableString implements CharSequence {private final String value;public Object span;public int spanStart,spanEnd;
public SpannableString(String s){value=s;}public void setSpan(Object o,int a,int b,int flags){span=o;spanStart=a;spanEnd=b;}public int length(){return value.length();}public char charAt(int i){return value.charAt(i);}public CharSequence subSequence(int a,int b){return value.subSequence(a,b);}public String toString(){return value;}}''',
    "android/text/style/TypefaceSpan.java": "package android.text.style;import android.graphics.Typeface;public class TypefaceSpan {public TypefaceSpan(Typeface t){}}",
    "android/text/style/ReplacementSpan.java": '''package android.text.style;import android.graphics.*;public abstract class ReplacementSpan {
public abstract int getSize(Paint paint,CharSequence text,int start,int end,Paint.FontMetricsInt metrics);
public abstract void draw(Canvas canvas,CharSequence text,int start,int end,float x,int top,int baseline,int bottom,Paint paint);}''',
    "android/util/TypedValue.java": "package android.util;public class TypedValue {public static final int COMPLEX_UNIT_PX=0;}",
    "android/widget/TextView.java": '''package android.widget;import android.content.Context;import android.graphics.Typeface;public class TextView {
public TextView(Context c){}public void setText(String s){}public void setTextColor(int c){}public void setTypeface(Typeface t){}public void setTextSize(int unit,float size){}public void setIncludeFontPadding(boolean b){}}''',
}


class PublicThemeTest(unittest.TestCase):
    def test_policy_and_vectors(self):
        tools = resolve()
        out = ROOT / "build/tests/theme"; out.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="theme-", dir=out) as temporary:
            stage = Path(temporary); sources = []
            for name, text in STUBS.items():
                path = stage / name; path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(text, encoding="utf-8"); sources.append(path)
            ui = ROOT / "app/src/org/x04g/home/ui"
            sources += [ui / "OemTheme.java", ui / "FallbackIconSpan.java", Path(__file__).with_name("ThemeFallbackHostTest.java")]
            classes = stage / "classes"; classes.mkdir()
            subprocess.run([str(x) for x in [tools["java"], "-jar", tools["ecj"], "-17", "-encoding", "UTF-8", "-d", classes, *sources]], check=True, capture_output=True, timeout=90)
            reports = []
            for mode in ["empty", "stock", "private", "partial", "missing-icons"]:
                atlas = out / (mode + "-icons.png")
                result = subprocess.run([str(tools["java"]), "-Djava.awt.headless=true", "-cp", str(classes), "org.x04g.home.ui.ThemeFallbackHostTest", mode, str(atlas)], check=True, capture_output=True, text=True, timeout=30)
                reports.append(json.loads(result.stdout))
            (out / "report.json").write_text(json.dumps({"pass": True, "scenarios": reports,
                "limitations": ["Synthetic Android facade; actual Android typography and TextView layout are not exercised.", "Vector atlas is rendered by Java2D, not an Android device."]}, indent=2) + "\n", encoding="utf-8")

    def test_every_current_glyph_has_original_vector(self):
        ui = ROOT / "app/src/org/x04g/home/ui"
        implementation = (ui / "FallbackIconSpan.java").read_text("utf-8")
        handled = set(re.findall(r"case '\\u([a-f0-9]{4})'", implementation))
        used = set()
        for path in ui.rglob("*.java"):
            if path.name not in {"FallbackIconSpan.java", "OemTheme.java"}:
                used.update(re.findall(r"\\u([ef][a-f0-9]{3})", path.read_text("utf-8")))
        self.assertEqual(used, handled)
        camera = (ui / "CameraPage.java").read_text("utf-8")
        self.assertNotIn('audio.setText(sound?', camera)
        self.assertIn('OemTheme.styleIcon(text,0,1)', camera)


if __name__ == "__main__":
    unittest.main()
