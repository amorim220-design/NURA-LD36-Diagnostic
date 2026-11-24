package com.nura.ld36diagnostic;

/** Center-crop geometry for letterbox-free 16:9 output, never stretches. */
public final class AspectCrop {
    private AspectCrop() { }
    /** Returns normalized source UV rect: left, top, right, bottom. */
    public static float[] centerCrop(int sourceWidth, int sourceHeight, int outputWidth, int outputHeight) {
        if(sourceWidth<=0||sourceHeight<=0||outputWidth<=0||outputHeight<=0)
            throw new IllegalArgumentException("Invalid crop dimensions");
        double sourceRatio=(double)sourceWidth/sourceHeight;
        double outputRatio=(double)outputWidth/outputHeight;
        double width=1.0,height=1.0;
        if(sourceRatio>outputRatio)width=outputRatio/sourceRatio;
        else if(sourceRatio<outputRatio)height=sourceRatio/outputRatio;
        float left=(float)((1.0-width)*0.5);
        float top=(float)((1.0-height)*0.5);
        return new float[]{left,top,(float)(left+width),(float)(top+height)};
    }
}
