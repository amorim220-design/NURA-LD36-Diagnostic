package com.nura.ld36diagnostic;

public final class AspectFitSmokeTest {
    private static void verify(int sw,int sh,int dw,int dh) {
        float[] q=AspectFit.vertices(sw,sh,dw,dh);
        float width=(q[2]-q[0])/2f;
        float height=(q[5]-q[1])/2f;
        if(width<=0||height<=0||width>1.0001f||height>1.0001f)
            throw new AssertionError("out of bounds");
        double finalRatio=(double)(width*dw)/(height*dh);
        double originalRatio=(double)sw/sh;
        if(Math.abs(finalRatio-originalRatio)>0.0005)
            throw new AssertionError("aspect distorted "+finalRatio+" versus "+originalRatio);
    }
    public static void main(String[] args) {
        verify(2400,1080,1280,720);verify(1080,2400,1280,720);
        verify(1920,1080,1280,720);verify(1080,1920,1280,720);
        verify(1280,720,1280,720);
        boolean invalid=false;
        try{AspectFit.vertices(-1,480,1280,720);}catch(IllegalArgumentException e){invalid=true;}
        if(!invalid)throw new AssertionError("invalid dimensions");
        System.out.println("Aspect fit and orientation math passed");
    }
}
