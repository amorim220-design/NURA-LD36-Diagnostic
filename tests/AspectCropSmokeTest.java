package com.nura.ld36diagnostic;

public final class AspectCropSmokeTest {
    private static void assertNear(float actual, float expected, String label) {
        if(Math.abs(actual-expected)>0.0002f) throw new AssertionError(label+" "+actual+" != "+expected);
    }
    private static void assertRatio(int sw,int sh,int dw,int dh) {
        float[] c=AspectCrop.centerCrop(sw,sh,dw,dh);
        float cropWidth=c[2]-c[0],cropHeight=c[3]-c[1];
        double shown=(sw*cropWidth)/(sh*cropHeight);
        if(Math.abs(shown-(double)dw/dh)>0.001) throw new AssertionError("distortion ratio "+shown);
        assertNear(c[0],1f-c[2],"center X");
        assertNear(c[1],1f-c[3],"center Y");
        if(c[0]<0||c[1]<0||c[2]>1||c[3]>1)throw new AssertionError("bounds");
    }
    public static void main(String[] args) {
        float[] exact=AspectCrop.centerCrop(1920,1080,1280,720);
        assertNear(exact[0],0f,"16:9 left");assertNear(exact[1],0f,"16:9 top");
        assertNear(exact[2],1f,"16:9 right");assertNear(exact[3],1f,"16:9 bottom");
        assertRatio(2400,1080,1280,720);
        assertRatio(2340,1080,1280,720);
        assertRatio(1600,1080,1280,720);
        assertRatio(1080,2400,720,1280);
        assertRatio(1080,1920,720,1280);
        boolean invalid=false;
        try{AspectCrop.centerCrop(0,1080,1280,720);}catch(IllegalArgumentException ok){invalid=true;}
        if(!invalid)throw new AssertionError("must reject zero dimension");
        System.out.println("Aspect crop geometry smoke tests passed");
    }
}
