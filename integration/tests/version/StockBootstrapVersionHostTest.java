package org.x04g.bootstrap;

import android.content.pm.PackageInfo;

/** Executes the actual bridge version gate with generated pins, never a device. */
public final class StockBootstrapVersionHostTest {
    private static int checks;
    private static void check(boolean value){if(!value)throw new AssertionError("companion_version_gate");checks++;}
    private static PackageInfo info(long code,String name){PackageInfo info=new PackageInfo();info.testVersionCode=code;info.versionName=name;return info;}
    public static void main(String[] args){
        long code=Long.parseLong(args[0]);String name=args[1];
        check(BuildPins.OWN_VERSION_CODE==code&&BuildPins.OWN_VERSION_NAME.equals(name));
        check(StockBootstrap.companionVersionMatches(info(code,name)));
        check(!StockBootstrap.companionVersionMatches(info(code-1,name)));
        check(!StockBootstrap.companionVersionMatches(info(code+1,name)));
        check(!StockBootstrap.companionVersionMatches(info(0,name)));
        check(!StockBootstrap.companionVersionMatches(info(code,name+".other")));
        check(!StockBootstrap.companionVersionMatches(info(code,null)));
        check(!StockBootstrap.companionVersionMatches(null));
        check(!StockBootstrap.companionVersionMatches(info(1,"0.1.0-candidate")));
        check(!StockBootstrap.companionVersionMatches(info(code==2?3:2,code==2?"0.1.0-rc.2":"0.1.0-rc.1")));
        System.out.println("{\"pass\":true,\"checks\":"+checks+",\"Android_runtime\":false}");
    }
}
